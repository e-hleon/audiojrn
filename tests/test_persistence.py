from datetime import date, datetime, timedelta, timezone
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import inspect as sqlalchemy_inspect, select
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session

from app.db import make_engine
from app.models import ContinuousSession, DailySummary, Interaction, ProposedAction, TaskItem
from app.repositories import (
    create_interaction,
    daily_source_fingerprint,
    get_daily_summary,
    get_interaction,
    interactions_fingerprint,
    list_interactions,
    join_continuous_transcriptions,
    upsert_daily_summary,
)
from app.settings import Settings
from app.time_utils import day_interval, ensure_aware, to_utc
import app.main as main_module
from app.main import create_app
from app.analysis import AnalysisNetworkFailed, AnalysisNotConfigured, DailySummaryGeneration
from app.schemas import AnalysisResult, DailySummaryResult, EventCandidate, Task


def test_settings_validate_timezone(monkeypatch):
    monkeypatch.delenv("APP_TIMEZONE", raising=False)
    assert Settings.from_env().app_timezone == "UTC"
    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    assert Settings.from_env().app_timezone == "Europe/Madrid"
    monkeypatch.setenv("APP_TIMEZONE", "Not/AZone")
    with pytest.raises(ValueError, match="zona IANA"):
        Settings.from_env()


def test_audit_sessions_retries_races_dates_and_day_boundary(db_engine, session, monkeypatch):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event
    import uuid
    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    analyzer = SessionFakeAnalyzer()
    transcriber = HttpFakeTranscriber()
    sid = str(uuid.uuid4())
    chunks = [dict(capture_mode="continuous", capture_session_id=sid, chunk_index=str(i),
                   capture_chunk_id=str(uuid.uuid4()), recorded_at=when)
              for i, when in enumerate(["2026-09-07T23:59:40+02:00", "2026-09-08T00:00:10+02:00"])]
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        def upload(data):
            return client.post("/process", data=data, files={"file": ("synthetic.wav", b"synthetic")})
        # Two simultaneous copies of the same upload do not call ASR/LLM twice.
        entered, release = Event(), Event()
        original = transcriber.transcribe
        def slow(file, language=None):
            entered.set()
            assert release.wait(5)
            return original(file, language)
        transcriber.transcribe = slow
        with ThreadPoolExecutor(2) as pool:
            first = pool.submit(upload, chunks[0])
            assert entered.wait(5)
            second = pool.submit(upload, chunks[0])
            release.set()
            a, b = first.result(), second.result()
        assert a.status_code == b.status_code == 200
        assert a.json()["interaction_id"] == b.json()["interaction_id"]
        assert len(transcriber.languages) == 1
        assert analyzer.analysis_inputs == []  # los chunks solo hacen ASR
        assert client.post(f"/continuous-sessions/{sid}/finalize", json={"last_chunk_index": 1}).status_code == 409
        assert client.post("/days/2026-09-07/summary").status_code == 200
        assert upload(chunks[1]).status_code == 200
        assert upload({**chunks[1], "capture_chunk_id": str(uuid.uuid4())}).status_code == 409
        calls_before = len(analyzer.analysis_inputs)
        with ThreadPoolExecutor(2) as pool:
            responses = list(pool.map(lambda _: client.post(f"/continuous-sessions/{sid}/finalize", json={"last_chunk_index": 1}), range(2)))
        assert [r.status_code for r in responses] == [200, 200]
        assert len(analyzer.analysis_inputs) == calls_before + 1
        assert analyzer.analysis_inputs[-1][0] == "Texto persistido Texto persistido"
        actions = client.get("/actions").json()
        assert {item["kind"] for item in actions} == {"task", "event"}
        assert client.post(f"/continuous-sessions/{sid}/finalize", json={"last_chunk_index": 0}).status_code == 409
        assert upload({**chunks[1], "chunk_index": "2", "capture_chunk_id": str(uuid.uuid4())}).status_code == 409
        # Empty, explicit nulls and invalid date updates must never become a 500.
        action = client.get("/actions").json()[0]
        for patch in ({"title": None}, {"status": None}, {"all_day": None}, {"title": " "}, {"start_at": "tomorrow"}):
            assert client.patch(f"/actions/{action['id']}", json=patch).status_code == 422
        assert client.patch(f"/actions/{action['id']}", json={"title": "Edited", "notes": "reviewed"}).status_code == 200


def test_audit_finalize_llm_failure_retry_and_silence(db_engine, session):
    import uuid
    analyzer = HttpFakeAnalyzer()
    transcriber = HttpFakeTranscriber()
    sid = str(uuid.uuid4())
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        assert client.post("/process", data={"capture_mode": "continuous", "capture_session_id": sid, "chunk_index": "0", "recorded_at": "2026-09-10T10:00:00+00:00"}, files={"file": ("a.wav", b"a")}).status_code == 200
        original = analyzer.analyze
        def fail(*args, **kwargs):
            raise AnalysisNetworkFailed("synthetic")
        analyzer.analyze = fail
        assert client.post(f"/continuous-sessions/{sid}/finalize", json={"last_chunk_index": 0}).status_code == 503
        assert client.get("/actions").json() == []
        analyzer.analyze = original
        assert client.post(f"/continuous-sessions/{sid}/finalize", json={"last_chunk_index": 0}).status_code == 200
        sid = str(uuid.uuid4())
        transcriber.transcribe = lambda file, language=None: {"text": "", "language": "es", **transcriber.details()}
        analyzer.analyze = fail  # silence must not block persistence on an LLM call
        assert client.post("/process", data={"capture_mode": "continuous", "capture_session_id": sid, "chunk_index": "0", "recorded_at": "2026-09-11T10:00:00+00:00"}, files={"file": ("a.wav", b"a")}).status_code == 200
        assert client.post(f"/continuous-sessions/{sid}/finalize", json={"last_chunk_index": 0}).status_code == 200
        assert client.get("/days/2026-09-11").json()["interactions"] == []
        assert all(item["capture_session_id"] != sid for item in client.get("/export-data").json()["interactions"])
        assert "2026-09-11" not in client.get("/days/activity", params={"from": "2026-09-01", "to": "2026-09-30"}).json()["days"]
    session.expire_all()
    assert session.scalars(select(Interaction).where(Interaction.capture_session_id == UUID(sid))).all() == []


@pytest.fixture(scope="session")
def db_engine():
    settings = Settings.from_env()
    engine = make_engine(settings)
    try:
        with engine.connect():
            pass
    except OperationalError as exc:
        engine.dispose()
        pytest.skip(f"PostgreSQL no disponible: {exc}")
    yield engine
    engine.dispose()


@pytest.fixture
def session(db_engine):
    with Session(db_engine) as session:
        session.execute(TaskItem.__table__.delete())
        session.execute(ProposedAction.__table__.delete())
        session.execute(ContinuousSession.__table__.delete())
        session.execute(Interaction.__table__.delete())
        session.execute(DailySummary.__table__.delete())
        session.commit()
        yield session
        session.rollback()
        session.execute(TaskItem.__table__.delete())
        session.execute(ProposedAction.__table__.delete())
        session.execute(ContinuousSession.__table__.delete())
        session.execute(Interaction.__table__.delete())
        session.execute(DailySummary.__table__.delete())
        session.commit()


def interaction_values(recorded_at):
    return {
        "recorded_at": recorded_at,
        "transcription": "Texto sintético",
        "language": "es",
        "transcription_model": "base",
        "transcription_device": "cuda",
        "transcription_compute_type": "int8_float16",
        "analysis": {"summary": "Resumen", "topics": ["prueba"], "decisions": [], "tasks": [], "reminders": []},
        "analysis_model": "test-model",
    }


def test_export_endpoint_returns_complete_persisted_data_without_llm(db_engine, session, monkeypatch):
    import uuid

    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    now = datetime(2026, 9, 8, 10, 0, tzinfo=timezone.utc)
    interaction = Interaction(id=uuid.uuid4(), **interaction_values(now))
    summary = DailySummary(
        day=date(2026, 9, 8), timezone="Europe/Madrid",
        result={"summary": "Día completo", "highlights": ["Avance"]},
        source_fingerprint="fingerprint", generated_at=now, manually_edited=True,
    )
    pending = ProposedAction(
        source_key="export:pending", kind="task", status="pending", title="Pendiente",
        evidence="evidencia", all_day=False,
    )
    dismissed = ProposedAction(
        source_key="export:dismissed", kind="task", status="dismissed", title="Descartada",
        evidence="evidencia", all_day=False,
    )
    session.add_all([interaction, summary, pending, dismissed])
    session.commit()
    analyzer = SessionFakeAnalyzer()

    with TestClient(create_app(HttpFakeTranscriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        response = client.get("/export-data")

    assert response.status_code == 200
    body = response.json()
    assert body["timezone"] == "Europe/Madrid"
    assert [item["id"] for item in body["interactions"]] == [str(interaction.id)]
    assert body["daily_summaries"][0]["summary"] == "Día completo"
    assert body["daily_summaries"][0]["manually_edited"] is True
    assert [item["title"] for item in body["actions"]] == ["Pendiente"]
    assert analyzer.analysis_inputs == []


class HttpFakeTranscriber:
    def __init__(self):
        self.languages = []

    def details(self):
        return {"model": "fake", "device": "cuda", "compute_type": "int8_float16"}

    def transcribe(self, file, language=None):
        self.languages.append(language)
        return {"text": "Texto persistido", "language": "es", **self.details()}


class HttpFakeAnalyzer:
    model = "fake-llm"

    def __init__(self, on_summary=None):
        self.daily_inputs = []
        self.on_summary = on_summary
        self.summary_error = None

    def available(self):
        return True

    def analyze(self, text, reference_datetime=None, timezone=None):
        return AnalysisResult(highlights=[], tasks=[], events=[])

    def summarize_day(self, interactions):
        self.daily_inputs.append(interactions)
        if self.summary_error:
            raise self.summary_error
        if self.on_summary:
            self.on_summary()
        return DailySummaryGeneration(
            result=DailySummaryResult(summary="Resumen del día", highlights=[]),
            model="fake-daily-llm",
        )


class UnconfiguredAnalyzer:
    model = "unconfigured"

    def available(self):
        return False

    def analyze(self, *args, **kwargs):
        raise AnalysisNotConfigured("El análisis LLM no está configurado")

    def summarize_day(self, *args, **kwargs):
        raise AnalysisNotConfigured("El análisis LLM no está configurado")


def test_basic_capture_and_continuous_finalize_work_without_openai(db_engine, session):
    analyzer = UnconfiguredAnalyzer()
    session_id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    with TestClient(create_app(HttpFakeTranscriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        assert client.get("/health").json()["analysis_configured"] is False
        manual = client.post(
            "/process", files={"file": ("manual.wav", b"audio")},
            data={"recorded_at": "2026-09-12T10:00:00+00:00"},
        )
        assert manual.status_code == 200
        assert manual.json()["transcription"]["text"] == "Texto persistido"
        assert manual.json()["analysis"] == {"highlights": [], "tasks": [], "events": []}
        chunk = client.post(
            "/process", files={"file": ("continuous.wav", b"audio")}, data={
                "capture_mode": "continuous", "capture_session_id": session_id,
                "chunk_index": "0", "recorded_at": "2026-09-12T11:00:00+00:00",
            },
        )
        assert chunk.status_code == 200
        finalized = client.post(f"/continuous-sessions/{session_id}/finalize", json={"last_chunk_index": 0})
        assert finalized.status_code == 200
        assert finalized.json()["status"] == "complete"
        assert finalized.json()["analysis"] == {"highlights": [], "tasks": [], "events": []}
        day = client.get("/days/2026-09-12")
        assert day.status_code == 200
        assert len(day.json()["interactions"]) == 2
        assert {item["transcription"]["text"] for item in day.json()["interactions"]} == {"Texto persistido"}
        assert client.post("/analyses", json={"text": "Texto persistido"}).status_code == 503
        assert client.post("/days/2026-09-12/summary").status_code == 503


def test_search_returns_the_backend_canonical_day(db_engine, session, monkeypatch):
    monkeypatch.setenv("APP_TIMEZONE", "UTC")
    interaction = Interaction(**interaction_values(datetime(2026, 3, 29, 22, 30, tzinfo=timezone.utc)))
    interaction.transcription = "Buscar frontera"
    session.add(interaction)
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        search = client.get("/interactions", params={"q": "frontera"})
        assert search.status_code == 200
        assert search.json()[0]["day"] == "2026-03-29"
        detail = client.get("/days/2026-03-29")
        assert [item["id"] for item in detail.json()["interactions"]] == [str(interaction.id)]


class SessionFakeAnalyzer(HttpFakeAnalyzer):
    def __init__(self):
        super().__init__()
        self.analysis_inputs = []

    def analyze(self, text, reference_datetime=None, timezone=None):
        self.analysis_inputs.append((text, reference_datetime, timezone))
        if reference_datetime is None:
            return super().analyze(text)
        return AnalysisResult(
            highlights=[],
            tasks=[Task(text="Preparar gráficas", due_at=None, evidence="terminar gráficas")],
            events=[EventCandidate(
                title="Tutoría TFM",
                start_at="2026-09-07T17:00:00+02:00",
                end_at=None,
                all_day=False,
                location=None,
                evidence="reunión con el tutor",
            )],
        )


def rich_interaction_values(recorded_at, transcription="Texto sintético"):
    values = interaction_values(recorded_at)
    values["transcription"] = transcription
    values["analysis"] = {
        "summary": "Se aprobó el plan.",
        "topics": ["plan"],
        "decisions": [{"text": "Aprobar el plan", "evidence": "Aprobamos el plan."}],
        "tasks": [{"text": "Enviar el plan", "assignee": "Ana", "due_date": None,
                   "evidence": "Ana enviará el plan."}],
        "reminders": [{"text": "Revisar el plan", "when": "lunes",
                       "evidence": "Recuérdame el plan el lunes."}],
    }
    return values


def test_migration_created_expected_tables(db_engine):
    inspector = sqlalchemy_inspect(db_engine)
    assert {"interactions", "daily_summaries", "alembic_version"}.issubset(inspector.get_table_names())
    columns = {column["name"] for column in inspector.get_columns("interactions")}
    assert "audio" not in columns
    assert "filename" not in columns


def test_create_get_jsonb_and_timezone_aware(session):
    item = create_interaction(session, **interaction_values(datetime(2026, 9, 5, 10, tzinfo=timezone.utc)))
    session.commit()
    found = get_interaction(session, item.id)
    assert found is not None
    assert found.analysis["summary"] == "Resumen"
    assert found.analysis["topics"] == ["prueba"]
    assert found.recorded_at.tzinfo is not None
    assert found.created_at.tzinfo is not None
    assert found.updated_at.tzinfo is not None


def test_list_is_chronological_and_filters_half_open_interval(session):
    late = create_interaction(session, **interaction_values(datetime(2026, 9, 5, 12, tzinfo=timezone.utc)))
    early = create_interaction(session, **interaction_values(datetime(2026, 9, 5, 8, tzinfo=timezone.utc)))
    session.commit()
    assert [item.id for item in list_interactions(session)] == [early.id, late.id]
    result = list_interactions(
        session,
        datetime(2026, 9, 5, 9, tzinfo=timezone.utc),
        datetime(2026, 9, 5, 13, tzinfo=timezone.utc),
    )
    assert [item.id for item in result] == [late.id]


def test_daily_summary_unique_and_upsert(session):
    day = date(2026, 9, 5)
    summary = upsert_daily_summary(
        session,
        day=day,
        timezone="UTC",
        result={"summary": "Uno", "topics": []},
        source_fingerprint="a" * 64,
        generated_at=datetime.now(timezone.utc),
    )
    session.commit()
    updated = upsert_daily_summary(
        session,
        day=day,
        timezone="UTC",
        result={"summary": "Dos", "topics": ["tema"]},
        source_fingerprint="b" * 64,
        generated_at=datetime.now(timezone.utc),
    )
    session.commit()
    assert updated.id == summary.id
    assert get_daily_summary(session, day).result["summary"] == "Dos"


def test_duplicate_daily_summary_rolls_back_without_losing_original(session):
    day = date(2026, 9, 5)
    upsert_daily_summary(session, day=day, timezone="UTC", result={"summary": "Uno", "topics": []},
                         source_fingerprint="a" * 64, generated_at=datetime.now(timezone.utc))
    session.commit()
    duplicate = DailySummary(day=day, timezone="UTC", result={"summary": "Dos", "topics": []},
                             source_fingerprint="b" * 64, generated_at=datetime.now(timezone.utc))
    session.add(duplicate)
    with pytest.raises(IntegrityError):
        session.commit()
    session.rollback()
    assert get_daily_summary(session, day).result["summary"] == "Uno"


def test_fingerprint_is_order_independent_and_changes_on_add_or_update(session):
    first = create_interaction(session, **interaction_values(datetime(2026, 9, 5, 8, tzinfo=timezone.utc)))
    second = create_interaction(session, **interaction_values(datetime(2026, 9, 5, 9, tzinfo=timezone.utc)))
    session.commit()
    original = interactions_fingerprint([first, second])
    assert interactions_fingerprint([second, first]) == original
    third = create_interaction(session, **interaction_values(datetime(2026, 9, 5, 10, tzinfo=timezone.utc)))
    session.commit()
    assert interactions_fingerprint([first, second, third]) != original
    first.updated_at = first.updated_at + timedelta(seconds=1)
    assert interactions_fingerprint([first, second]) != original


def test_temporal_utilities_reject_naive_and_handle_utc_midnight():
    naive = datetime(2026, 9, 5, 12)
    with pytest.raises(ValueError):
        ensure_aware(naive)
    assert to_utc(datetime(2026, 9, 5, 12, tzinfo=timezone(timedelta(hours=2)))) == datetime(2026, 9, 5, 10, tzinfo=timezone.utc)
    start, end = day_interval("2026-09-05", "UTC")
    assert start == datetime(2026, 9, 5, tzinfo=timezone.utc)
    assert end == datetime(2026, 9, 6, tzinfo=timezone.utc)


def test_europe_madrid_midnight_and_dst():
    start, end = day_interval("2026-03-29", "Europe/Madrid")
    assert start.hour == 23 and start.day == 28
    assert end - start == timedelta(hours=23)
    start, end = day_interval("2026-10-25", "Europe/Madrid")
    assert end - start == timedelta(hours=25)


def test_interaction_model_has_no_audio_fields():
    columns = set(Interaction.__table__.columns.keys())
    assert not columns.intersection({"audio", "audio_path", "filename", "prompt"})


def test_http_process_and_history_against_postgresql(db_engine, session):
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        first = client.post(
            "/process",
            files={"file": ("ignored.wav", b"synthetic")},
            data={"recorded_at": "2026-09-05T10:00:00+00:00"},
        )
        assert first.status_code == 200
        body = first.json()
        assert body["transcription"]["text"] == "Texto persistido"
        assert body["analysis"] == {"highlights": [], "tasks": [], "events": []}
        interaction_id = body["interaction_id"]

        detail = client.get(f"/interactions/{interaction_id}")
        assert detail.status_code == 200
        assert detail.json()["analysis_model"] == "fake-llm"
        assert detail.json()["transcription"]["model"] == "fake"

        history = client.get("/interactions", params={"from": "2026-09-05T10:00:00Z", "to": "2026-09-05T11:00:00Z"})
        assert history.status_code == 200
        assert len(history.json()) == 1
        assert client.get("/interactions/00000000-0000-0000-0000-000000000000").status_code == 404
        assert client.get("/interactions/not-a-uuid").status_code == 422


def test_continuous_metadata_and_retry_are_idempotent(db_engine, session):
    session_id = "11111111-1111-1111-1111-111111111111"
    chunk_id = "22222222-2222-2222-2222-222222222222"
    data = {
        "recorded_at": "2026-09-05T10:00:00Z",
        "capture_mode": "continuous",
        "capture_session_id": session_id,
        "chunk_index": "3",
        "capture_chunk_id": chunk_id,
    }
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        first = client.post("/process", files={"file": ("chunk.wav", b"synthetic")}, data=data)
        second = client.post("/process", files={"file": ("chunk.wav", b"synthetic")}, data=data)
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["interaction_id"] == second.json()["interaction_id"]
    assert second.json()["capture_session_id"] == session_id
    assert second.json()["chunk_index"] == 3
    assert second.json()["capture_chunk_id"] == chunk_id
    assert session.query(Interaction).filter(Interaction.capture_chunk_id == chunk_id).count() == 1


def test_continuous_chunks_inherit_first_persisted_language_only_server_side(db_engine, session):
    session_a = "11111111-1111-1111-1111-111111111111"
    session_b = "33333333-3333-3333-3333-333333333333"
    session_c = "55555555-5555-5555-5555-555555555555"
    transcriber = HttpFakeTranscriber()
    data = lambda session_id, index, chunk_id, mode="continuous": {
        "recorded_at": "2026-09-05T10:00:00Z",
        "capture_mode": mode,
        "capture_session_id": session_id,
        "chunk_index": str(index),
        "capture_chunk_id": chunk_id,
    }
    with TestClient(create_app(lambda: transcriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        first = client.post("/process", files={"file": ("chunk.wav", b"one")}, data=data(session_a, 0, "22222222-2222-2222-2222-222222222222"))
        second = client.post("/process", files={"file": ("chunk.wav", b"two")}, data=data(session_a, 1, "44444444-4444-4444-4444-444444444444"))
        third = client.post("/process", files={"file": ("chunk.wav", b"three")}, data=data(session_a, 7, "66666666-6666-6666-6666-666666666666"))
        new_session = client.post("/process", files={"file": ("chunk.wav", b"new")}, data=data(session_b, 1, "77777777-7777-7777-7777-777777777777"))
        no_previous = client.post("/process", files={"file": ("chunk.wav", b"none")}, data=data(session_c, 4, "88888888-8888-8888-8888-888888888888"))
        manual = client.post("/process", files={"file": ("chunk.wav", b"manual")}, data={"capture_mode": "manual", "capture_chunk_id": "99999999-9999-9999-9999-999999999999"})
        retry = client.post("/process", files={"file": ("chunk.wav", b"retry")}, data=data(session_a, 1, "44444444-4444-4444-4444-444444444444"))
    assert [response.status_code for response in (first, second, third, new_session, no_previous, manual, retry)] == [200] * 7
    assert transcriber.languages == [None, "es", "es", None, None, None]
    assert retry.json()["interaction_id"] == second.json()["interaction_id"]


def test_continuous_finalize_is_complete_idempotent_and_authoritative_for_day(db_engine, session):
    session_id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    analyzer = SessionFakeAnalyzer()
    transcriber = HttpFakeTranscriber()
    data = lambda index, chunk_id: {
        "recorded_at": f"2026-09-07T10:0{index}:00+00:00",
        "capture_mode": "continuous",
        "capture_session_id": session_id,
        "chunk_index": str(index),
        "capture_chunk_id": chunk_id,
    }
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        first = client.post("/process", files={"file": ("chunk.wav", b"terminar")}, data=data(0, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001"))
        second = client.post("/process", files={"file": ("chunk.wav", b" graficas")}, data=data(1, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0002"))
        finalized = client.post(f"/continuous-sessions/{session_id}/finalize", json={"last_chunk_index": 1})
        repeated = client.post(f"/continuous-sessions/{session_id}/finalize", json={"last_chunk_index": 1})
        day = client.get("/days/2026-09-07")
        actions = client.get("/actions", params={"include_dismissed": True})
    assert first.status_code == second.status_code == 200
    assert finalized.status_code == repeated.status_code == 200
    assert finalized.json() == repeated.json()
    assert finalized.json()["status"] == "complete"
    assert len(analyzer.analysis_inputs) == 1
    assert analyzer.analysis_inputs[-1][0] == "Texto persistido Texto persistido"
    assert day.status_code == 200
    assert len(day.json()["events"]) == 1
    assert actions.status_code == 200
    assert {item["kind"] for item in actions.json()} == {"task", "event"}


def test_continuous_finalize_recomposes_semantic_text_exactly_once(db_engine):
    session_id = "abababab-abab-abab-abab-abababababab"
    transcriber = HttpFakeTranscriber()
    texts = iter([
        "  mañana tengo que  ",
        "   ",
        "limpiar las mesas del taller y pasado mañana tengo que probar la pizarra digital del taller",
    ])
    transcriber.transcribe = lambda file, language=None: {"text": next(texts), "language": "es", **transcriber.details()}
    analyzer = SessionFakeAnalyzer()
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        for index in range(3):
            response = client.post("/process", files={"file": ("chunk.wav", b"audio")}, data={
                "capture_mode": "continuous", "capture_session_id": session_id, "chunk_index": str(index),
                "capture_chunk_id": f"abababab-abab-abab-abab-ababababab{index:02d}",
            })
            assert response.status_code == 200
        assert client.post(f"/continuous-sessions/{session_id}/finalize", json={"last_chunk_index": 2}).status_code == 200
    assert [item[0] for item in analyzer.analysis_inputs] == [
        "mañana tengo que limpiar las mesas del taller y pasado mañana tengo que probar la pizarra digital del taller"
    ]
    assert join_continuous_transcriptions([" ", " uno ", "", "dos "]) == "uno dos"


def test_continuous_finalize_rejects_missing_chunk_and_can_retry_after_it_arrives(db_engine, session):
    session_id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    transcriber = HttpFakeTranscriber()
    analyzer = SessionFakeAnalyzer()
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        first = client.post(
            "/process",
            files={"file": ("chunk.wav", b"first")},
            data={"capture_mode": "continuous", "capture_session_id": session_id, "chunk_index": "1", "capture_chunk_id": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0001"},
        )
        incomplete = client.post(f"/continuous-sessions/{session_id}/finalize", json={"last_chunk_index": 1})
    assert first.status_code == 200
    assert incomplete.status_code == 409


def test_continuous_finalize_uses_blank_placeholder_for_completeness_then_purges_it(db_engine, session):
    session_id = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    transcriber = HttpFakeTranscriber()
    analyzer = SessionFakeAnalyzer()
    texts = iter(["", "Contenido real"])
    transcriber.transcribe = lambda file, language=None: {
        "text": next(texts), "language": "es", **transcriber.details()
    }
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        responses = [client.post(
            "/process", files={"file": ("chunk.wav", b"audio")}, data={
                "capture_mode": "continuous", "capture_session_id": session_id,
                "chunk_index": str(index), "capture_chunk_id": f"cccccccc-cccc-cccc-cccc-cccccccc000{index}",
                "recorded_at": f"2026-09-12T10:0{index}:00+00:00",
            },
        ) for index in range(2)]
        finalized = client.post(f"/continuous-sessions/{session_id}/finalize", json={"last_chunk_index": 1})
        day = client.get("/days/2026-09-12").json()
        exported = client.get("/export-data").json()
    assert [response.status_code for response in responses] == [200, 200]
    assert finalized.status_code == 200
    assert analyzer.analysis_inputs[-1][0] == "Contenido real"
    assert [item["transcription"]["text"] for item in day["interactions"]] == ["Contenido real"]
    assert all(item["transcription"]["text"].strip() for item in exported["interactions"])
    session.expire_all()
    remaining = session.scalars(select(Interaction).where(Interaction.capture_session_id == UUID(session_id))).all()
    assert [item.transcription for item in remaining] == ["Contenido real"]


def test_http_history_limit_offset_and_timezone_filters(db_engine, session):
    for hour in (8, 9, 10):
        create_interaction(session, **interaction_values(datetime(2026, 9, 5, hour, tzinfo=timezone.utc)))
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        limited = client.get("/interactions", params={"limit": 1, "offset": 1})
        assert limited.status_code == 200
        assert limited.json()[0]["recorded_at"].startswith("2026-09-05T09:00:00")
        semi_open = client.get(
            "/interactions",
            params={"from": "2026-09-05T09:00:00+02:00", "to": "2026-09-05T12:00:00+02:00"},
        )
        assert semi_open.status_code == 200
        assert len(semi_open.json()) == 2
        assert client.get("/interactions", params={"from": "2026-09-05T12:00:00"}).status_code == 422


def test_http_history_first_page_contains_fifty_newest_rows_and_paginates_deterministically(db_engine, session):
    base = datetime(2026, 9, 5, tzinfo=timezone.utc)
    rows = []
    for minute in range(55):
        rows.append(create_interaction(session, **interaction_values(base + timedelta(minutes=minute))))
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        first = client.get("/interactions", params={"limit": 50, "offset": 0})
        second = client.get("/interactions", params={"limit": 50, "offset": 50})
    assert first.status_code == second.status_code == 200
    assert [item["id"] for item in first.json()] == [str(item.id) for item in reversed(rows[5:])]
    assert [item["id"] for item in second.json()] == [str(item.id) for item in reversed(rows[:5])]


def test_http_history_global_search_is_case_insensitive_paginated_and_excludes_blanks(db_engine, session):
    rows = []
    for when, text in [
        (datetime(2026, 9, 8, 8, tzinfo=timezone.utc), "Probar la PIZARRA digital"),
        (datetime(2026, 9, 9, 8, tzinfo=timezone.utc), "Comprar pizarra nueva"),
        (datetime(2026, 9, 10, 8, tzinfo=timezone.utc), "   "),
        (datetime(2026, 9, 11, 8, tzinfo=timezone.utc), "No coincide"),
    ]:
        values = interaction_values(when); values["transcription"] = text
        rows.append(create_interaction(session, **values))
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        first = client.get("/interactions", params={"q": "pIzArRa", "limit": 1, "offset": 0})
        second = client.get("/interactions", params={"q": "PIZARRA", "limit": 1, "offset": 1})
        blank = client.get("/interactions", params={"q": "   ", "limit": 10})
    assert [item["id"] for item in first.json()] == [str(rows[1].id)]
    assert [item["id"] for item in second.json()] == [str(rows[0].id)]
    assert blank.json() == []


def test_actions_are_newest_first_with_deterministic_id_tiebreaker(db_engine, session):
    created = datetime(2026, 9, 8, 10, tzinfo=timezone.utc)
    older = ProposedAction(
        id=UUID("00000000-0000-0000-0000-000000000001"), source_key="order:older", kind="task",
        status="pending", title="Older", evidence="Older", created_at=created - timedelta(minutes=1),
    )
    same_time_low = ProposedAction(
        id=UUID("00000000-0000-0000-0000-000000000002"), source_key="order:low", kind="task",
        status="dismissed", title="Low", evidence="Low", created_at=created,
    )
    same_time_high = ProposedAction(
        id=UUID("00000000-0000-0000-0000-000000000003"), source_key="order:high", kind="reminder",
        status="pending", title="High", evidence="High", created_at=created,
    )
    session.add_all([older, same_time_low, same_time_high])
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        pending = client.get("/actions")
        all_actions = client.get("/actions", params={"include_dismissed": True})
    assert [item["title"] for item in pending.json()] == ["High", "Older"]
    assert [item["title"] for item in all_actions.json()] == ["High", "Low", "Older"]


def test_actions_are_paginated_in_sql_and_include_resolved_only_when_requested(db_engine, session):
    created = datetime(2026, 9, 8, 10, tzinfo=timezone.utc)
    for index in range(4):
        session.add(ProposedAction(
            source_key=f"page:pending:{index}", kind="task", status="pending",
            title=f"Pending {index}", evidence="Synthetic", created_at=created + timedelta(minutes=index),
        ))
    session.add(ProposedAction(
        source_key="page:exported", kind="task", status="exported", title="Exported",
        evidence="Synthetic", created_at=created + timedelta(minutes=5),
    ))
    session.add(ProposedAction(
        source_key="page:dismissed", kind="task", status="dismissed", title="Dismissed",
        evidence="Synthetic", created_at=created + timedelta(minutes=6),
    ))
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        first = client.get("/actions", params={"limit": 2, "offset": 0})
        second = client.get("/actions", params={"limit": 2, "offset": 2})
        resolved = client.get("/actions", params={"limit": 10, "include_resolved": True})
    assert [item["title"] for item in first.json()] == ["Pending 3", "Pending 2"]
    assert [item["title"] for item in second.json()] == ["Pending 1", "Pending 0"]
    assert [item["title"] for item in resolved.json()] == ["Exported", "Pending 3", "Pending 2", "Pending 1", "Pending 0"]


def test_bulk_dismiss_pending_actions_keeps_other_statuses_and_is_idempotent(db_engine, session):
    pending = [ProposedAction(source_key=f"bulk:pending:{index}", kind="task", status="pending", title="Pending", evidence="Synthetic") for index in range(2)]
    exported = ProposedAction(source_key="bulk:exported", kind="task", status="exported", title="Exported", evidence="Synthetic")
    dismissed = ProposedAction(source_key="bulk:dismissed", kind="task", status="dismissed", title="Dismissed", evidence="Synthetic")
    session.add_all([*pending, exported, dismissed])
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        first = client.post("/actions/dismiss-pending")
        second = client.post("/actions/dismiss-pending")
        all_actions = client.get("/actions", params={"include_dismissed": True})
    assert first.status_code == second.status_code == 200
    assert first.json() == {"dismissed_count": 2}
    assert second.json() == {"dismissed_count": 0}
    assert {item["title"]: item["status"] for item in all_actions.json()} == {
        "Pending": "dismissed", "Exported": "exported", "Dismissed": "dismissed",
    }


def test_new_delete_actions_endpoints_remove_pending_and_keep_exported(db_engine, session):
    first = ProposedAction(source_key="delete:first", kind="task", status="pending", title="First", evidence="Synthetic")
    second = ProposedAction(source_key="delete:second", kind="task", status="pending", title="Second", evidence="Synthetic")
    exported = ProposedAction(source_key="delete:exported", kind="task", status="exported", title="Exported", evidence="Synthetic")
    dismissed = ProposedAction(source_key="delete:legacy", kind="task", status="dismissed", title="Legacy", evidence="Synthetic")
    session.add_all([first, second, exported, dismissed])
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        single = client.delete(f"/actions/{first.id}")
        bulk = client.delete("/actions/pending")
        resolved = client.get("/actions", params={"include_resolved": True})
        legacy = client.get("/actions", params={"include_dismissed": True})
    assert single.status_code == 200 and single.json() == {"deleted_count": 1}
    assert bulk.status_code == 200 and bulk.json() == {"deleted_count": 1}
    assert [item["title"] for item in resolved.json()] == ["Exported"]
    assert {item["title"] for item in legacy.json()} == {"Exported", "Legacy"}


def test_delete_exported_actions_keeps_pending_and_associated_task(db_engine, session):
    pending = ProposedAction(source_key="clean:pending", kind="task", status="pending", title="Pending", evidence="Synthetic")
    exported = ProposedAction(source_key="clean:exported", kind="task", status="exported", title="Exported", evidence="Synthetic")
    session.add_all([pending, exported])
    session.flush()
    task = TaskItem(text="Persisted task", source_action_id=exported.id)
    session.add(task)
    session.commit()
    task_id, pending_id, exported_id = task.id, pending.id, exported.id

    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.delete("/actions/exported")

    assert response.status_code == 200
    assert response.json() == {"deleted_count": 1}
    session.expire_all()
    assert session.get(ProposedAction, pending_id) is not None
    assert session.get(ProposedAction, exported_id) is None
    assert session.get(TaskItem, task_id) is not None
    assert session.get(TaskItem, task_id).source_action_id is None


def test_delete_action_returns_not_found(db_engine):
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.delete("/actions/00000000-0000-0000-0000-000000000001")
    assert response.status_code == 404


def test_editing_task_or_reminder_with_a_concrete_date_refreshes_calendar_metadata(db_engine, session):
    task = ProposedAction(source_key="edit:task", kind="task", status="pending", title="Compra", evidence="Compra")
    reminder = ProposedAction(source_key="edit:reminder", kind="reminder", status="pending", title="Ordenador", evidence="Ordenador")
    session.add_all([task, reminder])
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        task_update = client.patch(f"/actions/{task.id}", json={"due_text": "2026-09-10"})
        reminder_update = client.patch(
            f"/actions/{reminder.id}", json={"due_text": "2026-09-10T06:00:00+02:00"}
        )
    assert task_update.status_code == reminder_update.status_code == 200
    assert task_update.json()["start_at"] == "2026-09-10"
    assert task_update.json()["all_day"] is True
    assert reminder_update.json()["start_at"] == "2026-09-10T06:00:00+02:00"
    assert reminder_update.json()["all_day"] is False


def test_concrete_calendar_fields_promotes_normalized_dates_and_keeps_relative_text_conservative():
    from app.repositories import concrete_calendar_fields

    assert concrete_calendar_fields("2026-09-11") == ("2026-09-11", True)
    assert concrete_calendar_fields("2026-09-09T16:00:00+02:00") == (
        "2026-09-09T16:00:00+02:00", False
    )
    assert concrete_calendar_fields("dentro de tres días") == (None, False)


def _stored_summary(session, day):
    upsert_daily_summary(
        session, day=day, timezone="Europe/Madrid", result={"summary": "Private", "topics": []},
        source_fingerprint="d" * 64, generated_at=datetime.now(timezone.utc),
    )


def test_delete_manual_interaction_preserves_summary_and_marks_it_stale(db_engine, session, monkeypatch):
    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    when = datetime(2026, 9, 7, 10, tzinfo=timezone.utc)
    manual = create_interaction(session, **interaction_values(when), capture_mode="manual")
    other = create_interaction(session, **interaction_values(when + timedelta(minutes=1)), capture_mode="manual")
    manual_id, other_id = manual.id, other.id
    session.add(ProposedAction(source_key="delete:manual", kind="task", status="pending", title="Manual", evidence="Synthetic", source_interaction_id=manual.id))
    _stored_summary(session, date(2026, 9, 7))
    session.flush()
    get_daily_summary(session, date(2026, 9, 7)).source_fingerprint = daily_source_fingerprint(
        [manual, other], {}
    )
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        assert client.get("/days/2026-09-07").json()["summary"]["status"] == "ready"
        response = client.delete(f"/interactions/{manual_id}")
        day = client.get("/days/2026-09-07")
        missing = client.delete(f"/interactions/{manual_id}")
    assert response.status_code == 200
    assert response.json() == {"interactions_deleted": 1, "actions_deleted": 1, "daily_summaries_deleted": 0}
    session.expire_all()
    assert session.get(Interaction, manual_id) is None
    assert session.get(Interaction, other_id) is not None
    assert session.scalar(select(ProposedAction).where(ProposedAction.source_key == "delete:manual")) is None
    assert get_daily_summary(session, date(2026, 9, 7)) is not None
    assert day.json()["summary"]["status"] == "stale"
    assert day.json()["summary"]["result"]["summary"] == "Private"
    assert missing.status_code == 404


def test_multiple_manual_deletions_preserve_one_stale_summary(db_engine, session, monkeypatch):
    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    first = create_interaction(session, **interaction_values(datetime(2026, 9, 7, 10, tzinfo=timezone.utc)), capture_mode="manual")
    second = create_interaction(session, **interaction_values(datetime(2026, 9, 7, 11, tzinfo=timezone.utc)), capture_mode="manual")
    _stored_summary(session, date(2026, 9, 7))
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        assert client.delete(f"/interactions/{first.id}").json()["daily_summaries_deleted"] == 0
        assert client.delete(f"/interactions/{second.id}").json()["daily_summaries_deleted"] == 0
        summary = client.get("/days/2026-09-07").json()["summary"]
    assert summary["status"] == "stale"
    assert summary["result"]["summary"] == "Private"


def test_delete_finalized_continuous_chunk_invalidates_block_but_preserves_task_and_summary(db_engine, session, monkeypatch):
    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    sid = UUID("88888888-8888-8888-8888-888888888888")
    first = create_interaction(session, **interaction_values(datetime(2026, 9, 7, 10, tzinfo=timezone.utc)), capture_mode="continuous", capture_session_id=sid, chunk_index=0)
    second = create_interaction(session, **interaction_values(datetime(2026, 9, 7, 11, tzinfo=timezone.utc)), capture_mode="continuous", capture_session_id=sid, chunk_index=1)
    block = ContinuousSession(id=sid, started_at=first.recorded_at, status="complete", analysis=AnalysisResult().model_dump(mode="json"))
    action = ProposedAction(source_key="session:delete", kind="task", status="exported", title="Aceptada", evidence="Synthetic", source_session_id=sid)
    session.add_all([block, action]); session.flush()
    task = TaskItem(text="Aceptada", source_action_id=action.id); session.add(task); _stored_summary(session, date(2026, 9, 7)); session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.delete(f"/interactions/{first.id}")
        day = client.get("/days/2026-09-07").json()
        exported = client.get("/export-data")
    assert response.status_code == 200
    session.expire_all(); assert session.get(Interaction, second.id) is not None
    assert session.get(ContinuousSession, sid).status == "invalidated"
    assert session.get(ContinuousSession, sid).analysis is None
    assert exported.status_code == 200
    assert next(item for item in exported.json()["continuous_sessions"] if item["id"] == str(sid))["status"] == "invalidated"
    assert session.get(TaskItem, task.id) is not None and session.get(TaskItem, task.id).source_action_id is None
    assert day["summary"]["status"] == "stale"


def test_batch_delete_allows_historical_open_continuous_block(db_engine, session):
    sid = UUID("77777777-7777-7777-7777-777777777777")
    manual = create_interaction(session, **interaction_values(datetime(2026, 9, 7, 10, tzinfo=timezone.utc)), capture_mode="manual")
    chunk = create_interaction(session, **interaction_values(datetime(2026, 9, 7, 11, tzinfo=timezone.utc)), capture_mode="continuous", capture_session_id=sid, chunk_index=0)
    manual_id, chunk_id = manual.id, chunk.id
    session.add(ContinuousSession(id=sid, started_at=chunk.recorded_at, status="open")); session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.post("/interactions/delete", json={"interaction_ids": [str(manual.id), str(chunk.id)]})
    assert response.status_code == 200
    session.expire_all(); assert session.get(Interaction, manual_id) is None; assert session.get(Interaction, chunk_id) is None
    assert session.get(ContinuousSession, sid) is None


def test_delete_last_finalized_chunk_removes_empty_session(db_engine, session):
    sid = UUID("66666666-6666-6666-6666-666666666666")
    chunk = create_interaction(session, **interaction_values(datetime(2026, 9, 7, 11, tzinfo=timezone.utc)), capture_mode="continuous", capture_session_id=sid, chunk_index=0)
    chunk_id = chunk.id
    session.add(ContinuousSession(id=sid, started_at=chunk.recorded_at, status="complete", analysis=AnalysisResult().model_dump(mode="json"))); session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.delete(f"/interactions/{chunk_id}")
    assert response.status_code == 200
    session.expire_all(); assert session.get(Interaction, chunk_id) is None; assert session.get(ContinuousSession, sid) is None


def test_delete_continuous_session_preserves_all_affected_summaries(db_engine, session, monkeypatch):
    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    session_id = UUID("99999999-9999-9999-9999-999999999999")
    first = create_interaction(
        session, **interaction_values(datetime(2026, 9, 7, 21, 59, tzinfo=timezone.utc)),
        capture_mode="continuous", capture_session_id=session_id, chunk_index=0,
    )
    second = create_interaction(
        session, **interaction_values(datetime(2026, 9, 7, 22, 1, tzinfo=timezone.utc)),
        capture_mode="continuous", capture_session_id=session_id, chunk_index=1,
    )
    first_id, second_id = first.id, second.id
    session.add(ContinuousSession(id=session_id, started_at=first.recorded_at, status="complete"))
    session.add_all([
        ProposedAction(source_key="delete:session", kind="task", status="pending", title="Session", evidence="Synthetic", source_session_id=session_id),
        ProposedAction(source_key="delete:legacy-chunk", kind="task", status="pending", title="Legacy", evidence="Synthetic", source_interaction_id=second.id),
    ])
    _stored_summary(session, date(2026, 9, 7))
    _stored_summary(session, date(2026, 9, 8))
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.delete(f"/continuous-sessions/{session_id}")
        first_day = client.get("/days/2026-09-07").json()
        second_day = client.get("/days/2026-09-08").json()
        missing = client.delete(f"/continuous-sessions/{session_id}")
    assert response.status_code == 200
    assert response.json() == {"interactions_deleted": 2, "actions_deleted": 2, "daily_summaries_deleted": 0}
    assert missing.status_code == 404
    assert first_day["summary"]["status"] == "stale"
    assert second_day["summary"]["status"] == "stale"
    session.expire_all()
    assert session.get(ContinuousSession, session_id) is None
    assert session.get(Interaction, first_id) is None
    assert session.get(Interaction, second_id) is None
    assert session.scalar(select(ProposedAction).where(ProposedAction.source_session_id == session_id)) is None
    assert get_daily_summary(session, date(2026, 9, 7)) is not None
    assert get_daily_summary(session, date(2026, 9, 8)) is not None


def test_day_empty_is_missing_and_summary_rejects_empty_day(db_engine, session):
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.get("/days/2026-09-05")
        assert response.status_code == 200
        body = response.json()
        assert body["interactions"] == []
        assert body["events"] == []
        assert body["highlights"] == []
        assert body["summary"]["status"] == "missing"
        assert client.post("/days/2026-09-05/summary").status_code == 409


def test_day_aggregates_chronologically_and_persists_ready_summary(db_engine, session):
    late = create_interaction(session, **rich_interaction_values(datetime(2026, 9, 5, 11, tzinfo=timezone.utc)))
    early = create_interaction(session, **rich_interaction_values(datetime(2026, 9, 5, 8, tzinfo=timezone.utc)))
    session.commit()
    analyzer = HttpFakeAnalyzer()
    with TestClient(create_app(HttpFakeTranscriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        missing = client.get("/days/2026-09-05")
        assert missing.json()["summary"]["status"] == "missing"
        assert [item["id"] for item in missing.json()["interactions"]] == [str(early.id), str(late.id)]
        assert len(missing.json()["highlights"]) == 1
        assert missing.json()["highlights"][0]["text"] == "Aprobar el plan"

        generated = client.post("/days/2026-09-05/summary")
        assert generated.status_code == 200
        assert generated.json()["status"] == "ready"
        assert generated.json()["model"] == "fake-daily-llm"
        ready = client.get("/days/2026-09-05")
        assert ready.json()["summary"]["status"] == "ready"
        assert ready.json()["summary"]["result"]["summary"] == "Resumen del día"


def test_day_summary_receives_full_transcriptions_and_stales_then_regenerates(db_engine, session):
    create_interaction(
        session,
        **rich_interaction_values(
            datetime(2026, 9, 5, 10, tzinfo=timezone.utc),
            transcription="TRANSCRIPCION COMPLETA PARA EL RESUMEN",
        ),
    )
    session.commit()
    analyzer = HttpFakeAnalyzer()
    with TestClient(create_app(HttpFakeTranscriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        assert client.post("/days/2026-09-05/summary").status_code == 200
        derived = analyzer.daily_inputs[0][0]
        assert set(derived) == {"local_time", "text"}
        assert derived["text"] == "TRANSCRIPCION COMPLETA PARA EL RESUMEN"

        added = create_interaction(
            session,
            **rich_interaction_values(datetime(2026, 9, 5, 12, tzinfo=timezone.utc)),
        )
        session.commit()
        assert client.get("/days/2026-09-05").json()["summary"]["status"] == "stale"
        assert client.post("/days/2026-09-05/summary").json()["status"] == "ready"

        added.updated_at = added.updated_at + timedelta(seconds=1)
        session.commit()
        assert client.get("/days/2026-09-05").json()["summary"]["status"] == "stale"


def test_daily_summary_manual_edit_flag_and_regeneration(db_engine, session):
    create_interaction(
        session,
        **rich_interaction_values(datetime(2026, 9, 5, 10, tzinfo=timezone.utc)),
    )
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        generated = client.post("/days/2026-09-05/summary").json()
        assert generated["manually_edited"] is False

        edited_summary = client.patch(
            "/days/2026-09-05/summary", json={"summary": "Resumen manual"}
        ).json()
        assert edited_summary["manually_edited"] is True
        edited_highlights = client.patch(
            "/days/2026-09-05/summary", json={"highlights": ["Destacado manual"]}
        ).json()
        assert edited_highlights["manually_edited"] is True

        create_interaction(
            session,
            **rich_interaction_values(datetime(2026, 9, 5, 11, tzinfo=timezone.utc)),
        )
        session.commit()
        stale = client.get("/days/2026-09-05").json()["summary"]
        assert stale["status"] == "stale"
        assert stale["manually_edited"] is True

        regenerated = client.post("/days/2026-09-05/summary").json()
        assert regenerated["status"] == "ready"
        assert regenerated["manually_edited"] is False


def test_task_reorder_moves_root_and_children_atomically_between_groups(db_engine, session):
    root_a = TaskItem(text="A", group_name="Trabajo", sort_order=0)
    root_b = TaskItem(text="B", group_name="Trabajo", sort_order=1)
    root_c = TaskItem(text="C", group_name="Casa", sort_order=0)
    session.add_all([root_a, root_b, root_c])
    session.flush()
    child = TaskItem(text="B child", group_name="Trabajo", parent_id=root_b.id, sort_order=0)
    session.add(child)
    session.commit()

    payload = {"items": [
        {"id": str(root_a.id), "group_name": "Trabajo", "sort_order": 0},
        {"id": str(root_b.id), "group_name": "Casa", "sort_order": 0},
        {"id": str(child.id), "group_name": "Casa", "sort_order": 0},
        {"id": str(root_c.id), "group_name": "Casa", "sort_order": 1},
    ]}
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.post("/tasks/reorder", json=payload)
        incoherent = client.post("/tasks/reorder", json={"items": [
            {**item, "group_name": "Trabajo"} if item["id"] == str(child.id) else item
            for item in payload["items"]
        ]})
    assert response.status_code == 200
    assert incoherent.status_code == 422
    session.expire_all()
    assert session.get(TaskItem, root_b.id).group_name == "Casa"
    assert session.get(TaskItem, child.id).group_name == "Casa"
    assert session.get(TaskItem, child.id).parent_id == root_b.id
    assert session.get(TaskItem, root_c.id).sort_order == 1


def test_client_task_uuid_is_idempotent_and_parent_group_is_authoritative(db_engine, session):
    parent = TaskItem(text="Padre", group_name="Trabajo")
    session.add(parent); session.commit()
    requested = UUID("00000000-0000-0000-0000-000000000123")
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        first = client.post("/tasks", json={"id": str(requested), "text": "Hija", "group_name": "Casa", "parent_id": str(parent.id)})
        repeated = client.post("/tasks", json={"id": str(requested), "text": "Hija distinta", "group_name": "Casa", "parent_id": str(parent.id)})
        moved = client.patch(f"/tasks/{parent.id}", json={"group_name": "Casa"})
    assert first.status_code == repeated.status_code == moved.status_code == 200
    assert first.json()["id"] == str(requested)
    assert repeated.json()["text"] == "Hija"
    session.expire_all()
    assert session.get(TaskItem, requested).group_name == "Casa"


def test_task_creation_accepts_completed_and_defaults_to_pending(db_engine, session):
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        completed = client.post("/tasks", json={"text": "Terminada", "completed": True})
        defaulted = client.post("/tasks", json={"text": "Pendiente"})
    assert completed.status_code == defaulted.status_code == 200
    assert completed.json()["completed"] is True
    assert defaulted.json()["completed"] is False


@pytest.mark.parametrize("field", ("text", "completed", "sort_order", "all_day"))
def test_task_patch_rejects_explicit_null_for_non_nullable_fields(db_engine, session, field):
    task = TaskItem(text="Pendiente")
    session.add(task)
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.patch(f"/tasks/{task.id}", json={field: None})
    assert response.status_code == 422
    session.expire_all()
    assert session.get(TaskItem, task.id).text == "Pendiente"


def test_task_with_children_cannot_become_a_subtask(db_engine, session):
    parent = TaskItem(text="Padre")
    destination = TaskItem(text="Destino")
    session.add_all([parent, destination])
    session.flush()
    session.add(TaskItem(text="Hija", parent_id=parent.id))
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.patch(f"/tasks/{parent.id}", json={"parent_id": str(destination.id)})
    assert response.status_code == 422
    session.expire_all()
    assert session.get(TaskItem, parent.id).parent_id is None


def test_days_activity_includes_the_requested_last_day_only(db_engine, session, monkeypatch):
    monkeypatch.setenv("APP_TIMEZONE", "UTC")
    session.add_all([
        Interaction(**interaction_values(datetime(2026, 9, 12, 12, tzinfo=timezone.utc))),
        Interaction(**interaction_values(datetime(2026, 9, 13, 12, tzinfo=timezone.utc))),
    ])
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.get("/days/activity", params={"from": "2026-09-12", "to": "2026-09-12"})
    assert response.status_code == 200
    assert response.json()["days"] == ["2026-09-12"]


def test_day_uses_madrid_local_day_and_dst(db_engine, session, monkeypatch):
    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    before_local_midnight = create_interaction(
        session, **interaction_values(datetime(2026, 9, 4, 21, 59, tzinfo=timezone.utc))
    )
    in_local_day = create_interaction(
        session, **interaction_values(datetime(2026, 9, 4, 22, tzinfo=timezone.utc))
    )
    dst_day = create_interaction(
        session, **interaction_values(datetime(2026, 3, 29, 21, 30, tzinfo=timezone.utc))
    )
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        result = client.get("/days/2026-09-05").json()
        assert result["timezone"] == "Europe/Madrid"
        assert [item["id"] for item in result["interactions"]] == [str(in_local_day.id)]
        dst = client.get("/days/2026-03-29").json()
        assert [item["id"] for item in dst["interactions"]] == [str(dst_day.id)]
    assert before_local_midnight.id != in_local_day.id


def test_day_summary_stales_when_timezone_changes_with_same_fingerprint(db_engine, session, monkeypatch):
    # El mediodía UTC pertenece al mismo 2026-09-05 tanto en UTC como en Madrid.
    # Así se comprueba que stale proviene de la zona, no de otro conjunto de datos.
    item = create_interaction(
        session, **rich_interaction_values(datetime(2026, 9, 5, 12, tzinfo=timezone.utc))
    )
    session.commit()
    monkeypatch.setenv("APP_TIMEZONE", "UTC")
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        assert client.post("/days/2026-09-05/summary").json()["status"] == "ready"

    summary = get_daily_summary(session, date(2026, 9, 5))
    assert summary.timezone == "UTC"
    original_fingerprint = summary.source_fingerprint
    assert original_fingerprint == daily_source_fingerprint([item], {})

    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        stale = client.get("/days/2026-09-05")
        assert stale.status_code == 200
        assert stale.json()["summary"]["status"] == "stale"
        assert [entry["id"] for entry in stale.json()["interactions"]] == [str(item.id)]
        assert client.post("/days/2026-09-05/summary").json()["status"] == "ready"

    session.expire_all()
    regenerated = get_daily_summary(session, date(2026, 9, 5))
    assert regenerated.source_fingerprint == original_fingerprint
    assert regenerated.timezone == "Europe/Madrid"


def test_day_rejects_outdated_generation_when_data_changes_during_llm_call(db_engine, session):
    create_interaction(session, **rich_interaction_values(datetime(2026, 9, 5, 10, tzinfo=timezone.utc)))
    session.commit()

    def add_during_generation():
        with Session(db_engine) as concurrent:
            create_interaction(concurrent, **rich_interaction_values(datetime(2026, 9, 5, 11, tzinfo=timezone.utc)))
            concurrent.commit()

    analyzer = HttpFakeAnalyzer(on_summary=add_during_generation)
    with TestClient(create_app(HttpFakeTranscriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        response = client.post("/days/2026-09-05/summary")
        assert response.status_code == 409
        assert "cambió durante la generación" in response.json()["detail"]
        assert client.get("/days/2026-09-05").json()["summary"]["status"] == "missing"


def test_daily_llm_failure_keeps_previous_summary(db_engine, session):
    create_interaction(session, **rich_interaction_values(datetime(2026, 9, 5, 10, tzinfo=timezone.utc)))
    session.commit()
    analyzer = HttpFakeAnalyzer()
    with TestClient(create_app(HttpFakeTranscriber, lambda: analyzer, lambda: Session(db_engine))) as client:
        assert client.post("/days/2026-09-05/summary").status_code == 200
        analyzer.summary_error = AnalysisNetworkFailed("network")
        assert client.post("/days/2026-09-05/summary").status_code == 503
        assert client.get("/days/2026-09-05").json()["summary"]["status"] == "ready"


def test_daily_summary_database_failure_is_safe(db_engine, session, monkeypatch):
    create_interaction(session, **rich_interaction_values(datetime(2026, 9, 5, 10, tzinfo=timezone.utc)))
    session.commit()

    def fail_upsert(*args, **kwargs):
        raise OperationalError("insert", {}, RuntimeError("database detail"))

    monkeypatch.setattr(main_module, "upsert_daily_summary", fail_upsert)
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        response = client.post("/days/2026-09-05/summary")
    assert response.status_code == 503
    assert "database detail" not in response.text
    assert "DATABASE_URL" not in response.text


def test_add_proposed_tasks_preserves_timed_and_all_day_due_dates_and_is_idempotent(db_engine, session, monkeypatch):
    monkeypatch.setenv("APP_TIMEZONE", "Europe/Madrid")
    timed = ProposedAction(
        source_key="task:timed", kind="task", status="pending", title="Reunión",
        evidence="Synthetic", start_at="2026-09-09T10:30:00+02:00", all_day=False,
    )
    all_day = ProposedAction(
        source_key="task:all-day", kind="task", status="pending", title="Entregar memoria",
        evidence="Synthetic", start_at="2026-09-10", all_day=True,
    )
    session.add_all([timed, all_day, TaskItem(text="Manual reciente", sort_order=-2)])
    session.commit()
    with TestClient(create_app(HttpFakeTranscriber, HttpFakeAnalyzer, lambda: Session(db_engine))) as client:
        timed_response = client.post(f"/actions/{timed.id}/add-to-tasks")
        all_day_response = client.post(f"/actions/{all_day.id}/add-to-tasks")
        repeated = client.post(f"/actions/{all_day.id}/add-to-tasks")
    assert timed_response.status_code == all_day_response.status_code == repeated.status_code == 200
    assert timed_response.json()["due_at"] == "2026-09-09T08:30:00Z"
    assert timed_response.json()["all_day"] is False
    assert all_day_response.json()["due_at"] == "2026-09-09T22:00:00Z"
    assert all_day_response.json()["all_day"] is True
    assert repeated.json()["id"] == all_day_response.json()["id"]
    assert repeated.json()["source_action_id"] == str(all_day.id)
    assert all_day_response.json()["sort_order"] < -2
