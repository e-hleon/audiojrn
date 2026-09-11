from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from threading import Event
import uuid

import pytest
from fastapi.testclient import TestClient

from app.analysis import (
    AnalysisAuthenticationFailed,
    AnalysisIncomplete,
    AnalysisInvalidResponse,
    AnalysisNetworkFailed,
    AnalysisNotConfigured,
    AnalysisRateLimited,
    AnalysisTimedOut,
)
from app.main import MAX_BYTES, create_app
from app.schemas import AnalysisResult
from app.transcription import AudioTooLong, InvalidAudio


class FakeSession:
    def __init__(self, store, fail_commit=False):
        self.store = store
        self.fail_commit = fail_commit
        self.pending = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def add(self, item):
        self.pending.append(item)

    def flush(self):
        for item in self.pending:
            item.id = item.id or uuid.uuid4()
            item.created_at = item.created_at or datetime.now(timezone.utc)
            item.updated_at = item.updated_at or item.created_at

    def commit(self):
        if self.fail_commit:
            from sqlalchemy.exc import OperationalError
            raise OperationalError("commit", {}, RuntimeError("db down"))
        self.store.extend(self.pending)
        self.pending.clear()

    def refresh(self, item):
        return None

    def expunge(self, item):
        return None

    def rollback(self):
        self.pending.clear()


class FakeTranscriber:
    def __init__(self):
        self.files = []
        self.error = None

    def details(self):
        return {"model": "fake", "device": "cuda", "compute_type": "int8_float16"}

    def transcribe(self, file):
        self.files.append(file)
        if self.error:
            raise self.error
        return {"text": "Texto de prueba", "language": "es", **self.details()}


class FakeAnalyzer:
    def __init__(self):
        self.texts = []
        self.error = None

    def available(self):
        return True

    def analyze(self, text, reference_datetime=None, timezone=None):
        self.texts.append(text)
        if self.error:
            raise self.error
        return AnalysisResult(highlights=[], tasks=[], events=[])


@pytest.fixture
def api():
    transcriber = FakeTranscriber()
    analyzer = FakeAnalyzer()
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: FakeSession([]))) as client:
        yield client, transcriber, analyzer


def test_model_loaded_once_and_result(api):
    client, transcriber, _ = api
    assert client.get("/health").json()["status"] == "ready"
    for _ in range(2):
        response = client.post("/transcriptions", files={"file": ("test.wav", b"audio" * 300_000)})
        assert response.status_code == 200
        assert response.json()["text"] == "Texto de prueba"
    assert len(transcriber.files) == 2
    assert all(file.closed for file in transcriber.files)


@pytest.mark.parametrize("error,status", [(InvalidAudio("inválido"), 400), (AudioTooLong("largo"), 413)])
def test_expected_errors_close_upload(api, error, status):
    client, transcriber, _ = api
    transcriber.error = error
    response = client.post("/transcriptions", files={"file": ("test.wav", b"x" * 2_000_000)})
    assert response.status_code == status
    assert transcriber.files[0].closed
    transcriber.error = None
    assert client.post("/transcriptions", files={"file": ("test.wav", b"audio")}).status_code == 200


def test_missing_empty_oversize(api):
    client, transcriber, _ = api
    assert client.post("/transcriptions").status_code == 422
    assert client.post("/transcriptions", files={"file": ("empty.wav", b"")}).status_code == 400
    assert client.post("/transcriptions", files={"file": ("large.wav", b"x" * (MAX_BYTES + 1))}).status_code == 413
    assert not transcriber.files


def test_upload_cap_fits_thirty_minutes_of_configured_android_aac():
    expected_bytes = 128_000 // 8 * 30 * 60
    assert expected_bytes < MAX_BYTES <= 64 * 1024 * 1024


def test_unexpected_failure_closes_file_and_releases_lock(api):
    client, transcriber, _ = api
    transcriber.error = RuntimeError("GPU failure")
    with pytest.raises(RuntimeError):
        client.post("/transcriptions", files={"file": ("test.wav", b"audio")})
    assert transcriber.files[0].closed
    transcriber.error = None
    assert client.post("/transcriptions", files={"file": ("test.wav", b"audio")}).status_code == 200


def test_busy_request_rejected(api):
    client, transcriber, _ = api
    entered, release = Event(), Event()
    original = transcriber.transcribe

    def slow(file):
        entered.set()
        assert release.wait(10)
        return original(file)

    transcriber.transcribe = slow
    with ThreadPoolExecutor() as pool:
        first = pool.submit(client.post, "/transcriptions", files={"file": ("test.wav", b"audio")})
        try:
            assert entered.wait(10)
            assert client.post("/transcriptions", files={"file": ("test.wav", b"audio")}).status_code == 503
            assert client.get("/health").status_code == 200
        finally:
            release.set()
        assert first.result().status_code == 200


def test_factory_called_once_per_lifespan():
    calls = []

    def factory():
        calls.append(True)
        return FakeTranscriber()

    with TestClient(create_app(factory, FakeAnalyzer)) as client:
        for _ in range(2):
            assert client.post("/transcriptions", files={"file": ("test.wav", b"audio")}).status_code == 200
        assert len(calls) == 1


def test_analyses_returns_structured_result_and_only_text(api):
    client, _, analyzer = api
    response = client.post("/analyses", json={"text": "Ana preparará el informe."})
    assert response.status_code == 200
    assert response.json() == {"highlights": [], "tasks": [], "events": []}
    assert analyzer.texts == ["Ana preparará el informe."]


def test_analyses_rejects_blank_text(api):
    client, _, _ = api
    assert client.post("/analyses", json={"text": "  "}).status_code == 422


@pytest.mark.parametrize("error,status", [
    (AnalysisNotConfigured(""), 503),
    (AnalysisAuthenticationFailed(""), 502),
    (AnalysisRateLimited(""), 429),
    (AnalysisTimedOut(""), 504),
    (AnalysisNetworkFailed(""), 503),
    (AnalysisInvalidResponse(""), 502),
    (AnalysisIncomplete(""), 502),
])
def test_analyses_maps_provider_errors_without_details(api, error, status):
    client, _, analyzer = api
    analyzer.error = error
    response = client.post("/analyses", json={"text": "Texto público"})
    assert response.status_code == status
    assert "Texto público" not in response.text
    if status == 429:
        assert "límite de peticiones o cuota insuficiente" in response.json()["detail"]


def test_process_reuses_transcription_and_sends_its_text_only(api):
    client, transcriber, analyzer = api
    response = client.post("/process", files={"file": ("test.wav", b"audio")})
    assert response.status_code == 200
    body = response.json()
    assert body["transcription"]["text"] == "Texto de prueba"
    assert body["analysis"] == {"highlights": [], "tasks": [], "events": []}
    assert analyzer.texts == ["Texto de prueba"]
    assert all(file.closed for file in transcriber.files)


def test_process_persists_and_returns_compatibility_fields():
    transcriber, analyzer, store = FakeTranscriber(), FakeAnalyzer(), []
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: FakeSession(store))) as client:
        response = client.post(
            "/process",
            files={"file": ("test.wav", b"audio")},
            data={"recorded_at": "2026-09-05T12:00:00+02:00"},
        )
    assert response.status_code == 200
    body = response.json()
    assert body["transcription"]["text"] == "Texto de prueba"
    assert body["analysis"] == {"highlights": [], "tasks": [], "events": []}
    assert body["interaction_id"]
    assert body["recorded_at"] == "2026-09-05T10:00:00+00:00"
    assert len(store) == 1
    assert store[0].analysis == {"highlights": [], "tasks": [], "events": []}


def test_process_recorded_at_falls_back_to_aware_receipt_and_rejects_naive():
    transcriber, analyzer, store = FakeTranscriber(), FakeAnalyzer(), []
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: FakeSession(store))) as client:
        fallback = client.post("/process", files={"file": ("test.wav", b"audio")})
        naive = client.post(
            "/process", files={"file": ("test.wav", b"audio")}, data={"recorded_at": "2026-09-05T12:00:00"}
        )
    assert fallback.status_code == 200
    assert fallback.json()["recorded_at"].endswith("+00:00")
    assert naive.status_code == 422
    assert len(store) == 1


@pytest.mark.parametrize("failure", [InvalidAudio("fallo ASR"), AnalysisInvalidResponse("fallo LLM")])
def test_process_does_not_persist_when_asr_or_analysis_fails(failure):
    transcriber, analyzer, store = FakeTranscriber(), FakeAnalyzer(), []
    if isinstance(failure, InvalidAudio):
        transcriber.error = failure
    else:
        analyzer.error = failure
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: FakeSession(store))) as client:
        response = client.post("/process", files={"file": ("test.wav", b"audio")})
    assert response.status_code in (400, 502)
    assert store == []


def test_process_returns_safe_error_when_commit_fails():
    transcriber, analyzer = FakeTranscriber(), FakeAnalyzer()
    with TestClient(create_app(lambda: transcriber, lambda: analyzer, lambda: FakeSession([], fail_commit=True))) as client:
        response = client.post("/process", files={"file": ("test.wav", b"audio")})
    assert response.status_code == 503
    assert "DATABASE_URL" not in response.text
    assert "db down" not in response.text
