"""API síncrona: la respuesta HTTP espera al texto final."""
from contextlib import asynccontextmanager
from asyncio import Lock as AsyncLock
from functools import wraps
from datetime import date, datetime, timezone
from threading import Lock
from typing import Annotated
import uuid
from zoneinfo import ZoneInfo

from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
from starlette.concurrency import run_in_threadpool
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from app.models import Interaction, TaskItem

from app.analysis import (
    AnalysisAuthenticationFailed,
    AnalysisIncomplete,
    AnalysisInvalidResponse,
    AnalysisNetworkFailed,
    AnalysisNotConfigured,
    AnalysisRateLimited,
    AnalysisTimedOut,
    OpenAIAnalyzer,
)
from app.schemas import (
    AnalysisRequest,
    AnalysisResult,
    ContinuousSessionFinalizeRequest,
    ContinuousSessionResponse,
    DailySummaryResult,
    DailySummaryState,
    DailySummaryUpdate,
    DeletedActionsResponse,
    DeletionResponse,
    DismissPendingActionsResponse,
    DayResponse,
    ExportBackendData,
    ExportContinuousSession,
    ExportDailySummary,
    ExportProposedAction,
    InteractionResponse,
    InteractionDeleteRequest,
    ProposedActionResponse,
    ProposedActionUpdate,
    TaskItemCreate,
    TaskItemResponse,
    TaskReorderRequest,
    TaskItemUpdate,
    validate_event_dates,
)
from app.db import make_session_factory
from app.repositories import (
    create_interaction,
    concrete_calendar_fields,
    create_continuous_session,
    create_proposed_actions,
    daily_source_fingerprint,
    delete_continuous_session_and_derived_data,
    delete_interaction_and_derived_data,
    delete_interactions_and_derived_data,
    dismiss_pending_actions,
    delete_pending_actions,
    delete_exported_actions,
    delete_proposed_action,
    get_capture_session_language,
    get_continuous_chunks,
    get_continuous_session,
    get_continuous_sessions,
    get_daily_summary,
    get_interaction,
    get_interaction_by_capture_chunk_id,
    get_proposed_action,
    interactions_fingerprint,
    activity_days,
    create_task_item,
    list_task_items,
    update_task_item,
    reorder_task_items,
    list_interactions,
    join_continuous_transcriptions,
    list_export_actions,
    list_export_continuous_sessions,
    list_export_daily_summaries,
    list_proposed_actions,
    session_source_fingerprint,
    upsert_daily_summary,
)
from app.settings import get_settings
from app.time_utils import day_interval, ensure_aware, to_utc
from app.transcription import AudioTooLong, InvalidAudio, Transcriber

# MediaRecorder uses AAC in M4A. 40 MiB leaves ample margin for a valid
# 30-minute recording while still rejecting unreasonable uploads.
MAX_BYTES = 40 * 1024 * 1024
MAX_DAILY_TRANSCRIPTION_CHARS = 120_000


def create_app(transcriber_factory=Transcriber, analyzer_factory=OpenAIAnalyzer, session_factory=None):
    @asynccontextmanager
    async def lifespan(app):
        app.state.transcriber = transcriber_factory()
        app.state.analyzer = analyzer_factory()
        app.state.session_factory = session_factory or make_session_factory()
        app.state.settings = get_settings()
        app.state.inference_lock = Lock()
        app.state.product_lock = AsyncLock()
        yield
        del app.state.transcriber

    app = FastAPI(title="AudioJrn — transcripción local", lifespan=lifespan)

    def serialized(operation):
        # Deployment has exactly one Uvicorn worker. Keep check/generate/commit
        # atomic relative to other product writes without blocking the event loop.
        @wraps(operation)
        async def wrapped(*args, **kwargs):
            try:
                async with app.state.product_lock:
                    return await operation(*args, **kwargs)
            except SQLAlchemyError as exc:
                raise HTTPException(503, "No se pudo completar la operación de persistencia") from exc
            finally:
                file = kwargs.get("file")
                if file is not None:
                    await file.close()
        return wrapped

    @app.get("/health")
    def health():
        return {
            "status": "ready",
            "analysis_configured": app.state.analyzer.available(),
            **app.state.transcriber.details(),
        }

    async def transcribe_upload(file: UploadFile, language: str | None = None):
        acquired = False
        try:
            if not file.size:
                raise HTTPException(400, "El archivo está vacío")
            if file.size > MAX_BYTES:
                raise HTTPException(413, "El archivo supera los 40 MiB")
            acquired = app.state.inference_lock.acquire(blocking=False)
            if not acquired:
                raise HTTPException(503, "Transcriptor ocupado; inténtalo de nuevo")
            try:
                if language is None:
                    return await run_in_threadpool(app.state.transcriber.transcribe, file.file)
                return await run_in_threadpool(
                    app.state.transcriber.transcribe, file.file, language=language
                )
            except InvalidAudio as exc:
                raise HTTPException(400, str(exc)) from exc
            except AudioTooLong as exc:
                raise HTTPException(413, str(exc)) from exc
        finally:
            if acquired:
                app.state.inference_lock.release()
            # Cierra y elimina el temporal creado por el parser multipart.
            await file.close()

    @app.post("/transcriptions")
    async def transcriptions(file: Annotated[UploadFile, File()]):
        return await transcribe_upload(file)

    def analyze_text(
        text: str, reference_datetime: str | None = None, timezone: str | None = None
    ) -> AnalysisResult:
        try:
            if reference_datetime and timezone:
                return app.state.analyzer.analyze(
                    text, reference_datetime=reference_datetime, timezone=timezone
                )
            return app.state.analyzer.analyze(text)
        except AnalysisNotConfigured as exc:
            raise HTTPException(503, "El análisis LLM no está configurado") from exc
        except AnalysisAuthenticationFailed as exc:
            raise HTTPException(502, "OpenAI rechazó las credenciales configuradas") from exc
        except AnalysisRateLimited as exc:
            raise HTTPException(
                429,
                "OpenAI no puede procesar la solicitud por límite de peticiones o cuota insuficiente",
            ) from exc
        except AnalysisTimedOut as exc:
            raise HTTPException(504, "OpenAI agotó el tiempo de espera") from exc
        except AnalysisNetworkFailed as exc:
            raise HTTPException(503, "No se pudo completar la llamada a OpenAI") from exc
        except (AnalysisInvalidResponse, AnalysisIncomplete) as exc:
            raise HTTPException(502, "OpenAI devolvió una respuesta no utilizable") from exc

    def summarize_day(transcriptions: list[dict]):
        try:
            return app.state.analyzer.summarize_day(transcriptions)
        except AnalysisNotConfigured as exc:
            raise HTTPException(503, "El análisis LLM no está configurado") from exc
        except AnalysisAuthenticationFailed as exc:
            raise HTTPException(502, "OpenAI rechazó las credenciales configuradas") from exc
        except AnalysisRateLimited as exc:
            raise HTTPException(
                429,
                "OpenAI no puede procesar la solicitud por límite de peticiones o cuota insuficiente",
            ) from exc
        except AnalysisTimedOut as exc:
            raise HTTPException(504, "OpenAI agotó el tiempo de espera") from exc
        except AnalysisNetworkFailed as exc:
            raise HTTPException(503, "No se pudo completar la llamada a OpenAI") from exc
        except (AnalysisInvalidResponse, AnalysisIncomplete) as exc:
            raise HTTPException(502, "OpenAI devolvió una respuesta no utilizable") from exc

    def parse_recorded_at(value: str | None, received_at: datetime) -> datetime:
        if value is None:
            return received_at
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            return to_utc(ensure_aware(parsed))
        except (TypeError, ValueError) as exc:
            raise HTTPException(422, "recorded_at debe ser un ISO-8601 con zona horaria") from exc

    def persist_interaction(
        recorded_at: datetime,
        transcription: dict,
        analysis: AnalysisResult,
        *,
        capture_mode: str,
        capture_session_id: uuid.UUID | None,
        chunk_index: int | None,
        capture_chunk_id: uuid.UUID | None,
    ):
        try:
            with app.state.session_factory() as session:
                try:
                    if capture_mode == "continuous" and capture_session_id is not None:
                        capture_session = get_continuous_session(session, capture_session_id)
                        if capture_session is None:
                            create_continuous_session(session, capture_session_id, recorded_at)
                        else:
                            capture_session.started_at = min(capture_session.started_at, recorded_at)
                    interaction = create_interaction(
                        session,
                        recorded_at=recorded_at,
                        capture_mode=capture_mode,
                        capture_session_id=capture_session_id,
                        chunk_index=chunk_index,
                        capture_chunk_id=capture_chunk_id,
                        transcription=transcription["text"],
                        language=transcription.get("language"),
                        transcription_model=transcription["model"],
                        transcription_device=transcription.get("device"),
                        transcription_compute_type=transcription.get("compute_type"),
                        analysis=analysis.model_dump(mode="json"),
                        analysis_model=getattr(app.state.analyzer, "model", None),
                    )
                    if capture_mode != "continuous" and (
                        analysis.tasks or analysis.events
                    ):
                        create_proposed_actions(
                            session,
                            analysis,
                            source_key_prefix=f"interaction:{interaction.id}",
                            source_interaction_id=interaction.id,
                        )
                    session.commit()
                    session.refresh(interaction)
                    session.expunge(interaction)
                    return interaction
                except IntegrityError:
                    session.rollback()
                    if capture_chunk_id is not None:
                        duplicate = get_interaction_by_capture_chunk_id(session, capture_chunk_id)
                        if duplicate is not None:
                            session.expunge(duplicate)
                            return duplicate
                    raise
                except SQLAlchemyError:
                    session.rollback()
                    raise
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo guardar la interacción") from exc

    def interaction_response(interaction) -> InteractionResponse:
        local_day = to_utc(interaction.recorded_at).astimezone(
            ZoneInfo(app.state.settings.app_timezone)
        ).date()
        return InteractionResponse(
            id=interaction.id,
            capture_mode=interaction.capture_mode,
            capture_session_id=interaction.capture_session_id,
            chunk_index=interaction.chunk_index,
            capture_chunk_id=interaction.capture_chunk_id,
            recorded_at=to_utc(interaction.recorded_at),
            created_at=to_utc(interaction.created_at),
            transcription={
                "text": interaction.transcription,
                "language": interaction.language,
                "model": interaction.transcription_model,
                "device": interaction.transcription_device,
                "compute_type": interaction.transcription_compute_type,
            },
            analysis=AnalysisResult.model_validate(interaction.analysis),
            analysis_model=interaction.analysis_model,
            day=local_day,
        )

    def daily_summary_state(summary, fingerprint: str) -> DailySummaryState:
        if summary is None:
            return DailySummaryState(status="missing", result=None, generated_at=None, model=None)
        return DailySummaryState(
            status=(
                "ready"
                if (
                    summary.source_fingerprint == fingerprint
                    and summary.timezone == app.state.settings.app_timezone
                )
                else "stale"
            ),
            result=DailySummaryResult.model_validate(summary.result),
            generated_at=to_utc(summary.generated_at),
            model=summary.llm_model,
            updated_at=to_utc(summary.updated_at),
            manually_edited=summary.manually_edited,
        )

    def semantic_entries(interactions: list, sessions: dict) -> list[tuple[object, AnalysisResult]]:
        """Devuelve una sola fuente semántica por sesión Continuous."""
        entries = []
        included_sessions = set()
        for interaction in interactions:
            if interaction.capture_mode == "continuous" and interaction.capture_session_id is not None:
                continuous_session = sessions.get(interaction.capture_session_id)
                if continuous_session is None:
                    # Compatibilidad con filas Continuous antiguas sin registro de sesión.
                    entries.append((interaction, AnalysisResult.model_validate(interaction.analysis)))
                elif continuous_session.status == "complete" and continuous_session.analysis is not None:
                    local_day = to_utc(interaction.recorded_at).astimezone(ZoneInfo(app.state.settings.app_timezone)).date()
                    session_day = to_utc(continuous_session.started_at).astimezone(ZoneInfo(app.state.settings.app_timezone)).date()
                    if interaction.capture_session_id not in included_sessions and local_day == session_day:
                        entries.append((interaction, AnalysisResult.model_validate(continuous_session.analysis)))
                        included_sessions.add(interaction.capture_session_id)
                # Las sesiones nuevas incompletas no entran en agregaciones de producto.
                continue
            entries.append((interaction, AnalysisResult.model_validate(interaction.analysis)))
        return entries

    def daily_transcriptions(interactions: list) -> list[dict]:
        """Entrada explícita del resumen: texto ASR completo y hora local, nunca audio."""
        local_zone = ZoneInfo(app.state.settings.app_timezone)
        result = []
        for interaction in interactions:
            result.append(
                {
                    "local_time": to_utc(interaction.recorded_at)
                    .astimezone(local_zone)
                    .isoformat(timespec="minutes"),
                    "text": interaction.transcription,
                }
            )
        return result

    def load_day(day: date):
        start, end = day_interval(day, app.state.settings.app_timezone)
        with app.state.session_factory() as session:
            interactions = list_interactions(session, start, end)
            session_ids = {
                item.capture_session_id
                for item in interactions
                if item.capture_mode == "continuous" and item.capture_session_id is not None
            }
            sessions = get_continuous_sessions(session, session_ids)
            fingerprint = daily_source_fingerprint(interactions, sessions)
            summary = get_daily_summary(session, day)
            return interactions, sessions, fingerprint, summary

    def day_response(day: date, interactions: list, sessions: dict, fingerprint: str, summary) -> DayResponse:
        analyses = [analysis for _, analysis in semantic_entries(interactions, sessions)]
        highlights = []
        seen_highlights = set()
        for analysis in analyses:
            for item in analysis.highlights:
                if item.text not in seen_highlights:
                    seen_highlights.add(item.text)
                    highlights.append(item)
        return DayResponse(
            day=day,
            timezone=app.state.settings.app_timezone,
            interactions=[interaction_response(item) for item in interactions],
            events=[event for analysis in analyses for event in analysis.events],
            highlights=highlights,
            summary=daily_summary_state(summary, fingerprint),
        )

    @app.post("/analyses", response_model=AnalysisResult)
    async def analyses(request: AnalysisRequest):
        reference = request.reference_datetime.isoformat() if request.reference_datetime else None
        return await run_in_threadpool(analyze_text, request.text, reference, request.timezone)

    @app.post("/process")
    @serialized
    async def process(
        file: Annotated[UploadFile, File()],
        recorded_at: Annotated[str | None, Form()] = None,
        capture_mode: Annotated[str, Form()] = "manual",
        capture_session_id: Annotated[str | None, Form()] = None,
        chunk_index: Annotated[int | None, Form()] = None,
        capture_chunk_id: Annotated[str | None, Form()] = None,
    ):
        received_at = datetime.now(timezone.utc)
        parsed_recorded_at = parse_recorded_at(recorded_at, received_at)
        if capture_mode not in {"manual", "continuous"}:
            raise HTTPException(422, "capture_mode no válido")
        try:
            parsed_session_id = uuid.UUID(capture_session_id) if capture_session_id else None
            parsed_chunk_id = uuid.UUID(capture_chunk_id) if capture_chunk_id else None
        except ValueError as exc:
            raise HTTPException(422, "Los identificadores de captura deben ser UUID") from exc
        if capture_mode == "continuous" and (parsed_session_id is None or chunk_index is None):
            raise HTTPException(422, "Continuo requiere capture_session_id y chunk_index")
        if chunk_index is not None and chunk_index < 0:
            raise HTTPException(422, "chunk_index no puede ser negativo")
        if capture_mode != "continuous" and (parsed_session_id is not None or chunk_index is not None):
            raise HTTPException(422, "Solo Continuo admite sesión e índice")
        if parsed_chunk_id is not None:
            with app.state.session_factory() as session:
                existing = await run_in_threadpool(
                    get_interaction_by_capture_chunk_id, session, parsed_chunk_id
                )
            if existing is not None:
                await file.close()
                return {
                    "interaction_id": existing.id,
                    "recorded_at": to_utc(existing.recorded_at),
                    "created_at": to_utc(existing.created_at),
                    "transcription": {
                        "text": existing.transcription,
                        "language": existing.language,
                        "model": existing.transcription_model,
                        "device": existing.transcription_device,
                        "compute_type": existing.transcription_compute_type,
                    },
                    "analysis": AnalysisResult.model_validate(existing.analysis),
                    "capture_mode": existing.capture_mode,
                    "capture_session_id": existing.capture_session_id,
                    "chunk_index": existing.chunk_index,
                    "capture_chunk_id": existing.capture_chunk_id,
                }
        inherited_language = None
        if capture_mode == "continuous":
            try:
                with app.state.session_factory() as session:
                    stored_session = get_continuous_session(session, parsed_session_id)
                    if stored_session is not None and stored_session.status == "complete":
                        raise HTTPException(409, "La sesión ya está completa")
                    if any(item.chunk_index == chunk_index for item in get_continuous_chunks(session, parsed_session_id)):
                        raise HTTPException(409, "Ese índice ya está ocupado; conserva el capture_chunk_id original")
            except SQLAlchemyError as exc:
                raise HTTPException(503, "No se pudo consultar la sesión") from exc
        if capture_mode == "continuous" and chunk_index is not None and chunk_index > 0 and parsed_session_id is not None:
            try:
                with app.state.session_factory() as session:
                    inherited_language = await run_in_threadpool(
                        get_capture_session_language, session, parsed_session_id
                    )
            except SQLAlchemyError as exc:
                raise HTTPException(503, "No se pudo consultar el idioma de la sesión") from exc
        transcription = await transcribe_upload(file, inherited_language)
        if capture_mode == "continuous" or not transcription["text"].strip():
            # Silence is valid Continuous audio; retain the index so finalize can
            # prove completeness even when one chunk has no recognized words.
            analysis = AnalysisResult()
        elif app.state.analyzer.available():
            reference = parsed_recorded_at.astimezone(ZoneInfo(app.state.settings.app_timezone)).isoformat()
            analysis = await run_in_threadpool(
                analyze_text, transcription["text"], reference, app.state.settings.app_timezone
            )
        else:
            # La ausencia de proveedor LLM no debe bloquear la captura básica.
            analysis = AnalysisResult()
        interaction = await run_in_threadpool(
            persist_interaction,
            parsed_recorded_at,
            transcription,
            analysis,
            capture_mode=capture_mode,
            capture_session_id=parsed_session_id,
            chunk_index=chunk_index,
            capture_chunk_id=parsed_chunk_id,
        )
        return {
            "interaction_id": interaction.id,
            "recorded_at": to_utc(interaction.recorded_at),
            "created_at": to_utc(interaction.created_at),
            "transcription": transcription,
            "analysis": analysis,
            "capture_mode": interaction.capture_mode,
            "capture_session_id": interaction.capture_session_id,
            "chunk_index": interaction.chunk_index,
            "capture_chunk_id": interaction.capture_chunk_id,
        }

    def continuous_session_response(item) -> ContinuousSessionResponse:
        return ContinuousSessionResponse(
            session_id=item.id,
            status=item.status,
            last_chunk_index=item.last_chunk_index,
            analysis=AnalysisResult.model_validate(item.analysis) if item.analysis else None,
            finalized_at=to_utc(item.finalized_at) if item.finalized_at else None,
        )

    @app.post(
        "/continuous-sessions/{session_id}/finalize",
        response_model=ContinuousSessionResponse,
    )
    @serialized
    async def finalize_continuous_session(
        session_id: uuid.UUID, request: ContinuousSessionFinalizeRequest
    ):
        try:
            with app.state.session_factory() as session:
                continuous_session = get_continuous_session(session, session_id)
                chunks = get_continuous_chunks(session, session_id)
                if continuous_session is None:
                    if not chunks:
                        raise HTTPException(409, "La sesión Continua no está registrada")
                    continuous_session = create_continuous_session(session, session_id, chunks[0].recorded_at)
                if continuous_session.status == "complete":
                    if continuous_session.last_chunk_index != request.last_chunk_index:
                        raise HTTPException(409, "La sesión se finalizó con otro último índice")
                    return continuous_session_response(continuous_session)
                actual = [item.chunk_index for item in chunks]
                if len(actual) != request.last_chunk_index + 1 or any(index != value for index, value in enumerate(actual)):
                    raise HTTPException(
                        409,
                        "La sesión está incompleta; espera a que todos los chunks estén persistidos",
                    )
                text = join_continuous_transcriptions([item.transcription for item in chunks])
                first = chunks[0]
                reference = to_utc(first.recorded_at).astimezone(
                    ZoneInfo(app.state.settings.app_timezone)
                ).isoformat()
                analysis = (
                    await run_in_threadpool(
                        analyze_text,
                        text,
                        reference,
                        app.state.settings.app_timezone,
                    )
                    if text.strip() and app.state.analyzer.available()
                    else AnalysisResult()
                )
                continuous_session.last_chunk_index = request.last_chunk_index
                continuous_session.status = "complete"
                continuous_session.analysis = analysis.model_dump(mode="json")
                continuous_session.analysis_model = getattr(app.state.analyzer, "model", None)
                # Empty ASR rows were needed until the consecutive indexes were
                # verified above. Once complete they are no longer user data.
                blank_ids = [item.id for item in chunks if not item.transcription.strip()]
                if blank_ids:
                    session.execute(delete(Interaction).where(Interaction.id.in_(blank_ids)))
                    session.flush()
                retained_chunks = [item for item in chunks if item.id not in blank_ids]
                continuous_session.source_fingerprint = session_source_fingerprint(retained_chunks)
                continuous_session.finalized_at = datetime.now(timezone.utc)
                create_proposed_actions(
                    session,
                    analysis,
                    source_key_prefix=f"session:{session_id}",
                    source_session_id=session_id,
                )
                session.commit()
                session.refresh(continuous_session)
                session.expunge(continuous_session)
                return continuous_session_response(continuous_session)
        except HTTPException:
            raise
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo finalizar la sesión Continua") from exc

    def proposed_action_response(item) -> ProposedActionResponse:
        return ProposedActionResponse(
            id=item.id,
            kind=item.kind,
            status=item.status,
            title=item.title,
            due_text=item.due_text,
            start_at=item.start_at,
            end_at=item.end_at,
            all_day=item.all_day,
            location=item.location,
            notes=item.notes,
            evidence=item.evidence,
            source_interaction_id=item.source_interaction_id,
            source_session_id=item.source_session_id,
            created_at=to_utc(item.created_at),
        )

    @app.get("/export-data", response_model=ExportBackendData)
    async def export_data():
        """Read persisted diary data only; export never invokes ASR or an LLM."""
        try:
            with app.state.session_factory() as session:
                interactions = await run_in_threadpool(
                    list_interactions, session, None, None, None, 0, False
                )
                continuous_sessions = await run_in_threadpool(list_export_continuous_sessions, session)
                summaries = await run_in_threadpool(list_export_daily_summaries, session)
                actions = await run_in_threadpool(list_export_actions, session)
                return ExportBackendData(
                    timezone=app.state.settings.app_timezone,
                    interactions=[interaction_response(item) for item in interactions],
                    continuous_sessions=[ExportContinuousSession(
                        id=item.id,
                        started_at=to_utc(item.started_at),
                        last_chunk_index=item.last_chunk_index,
                        status=item.status,
                        analysis=AnalysisResult.model_validate(item.analysis) if item.analysis is not None else None,
                        finalized_at=to_utc(item.finalized_at) if item.finalized_at is not None else None,
                        created_at=to_utc(item.created_at),
                        updated_at=to_utc(item.updated_at),
                    ) for item in continuous_sessions],
                    daily_summaries=[ExportDailySummary(
                        day=item.day,
                        timezone=item.timezone,
                        summary=DailySummaryResult.model_validate(item.result).summary,
                        highlights=DailySummaryResult.model_validate(item.result).highlights,
                        generated_at=to_utc(item.generated_at),
                        updated_at=to_utc(item.updated_at),
                        manually_edited=item.manually_edited,
                    ) for item in summaries],
                    actions=[ExportProposedAction(
                        **proposed_action_response(item).model_dump(),
                        updated_at=to_utc(item.updated_at),
                    ) for item in actions],
                )
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudieron consultar los datos de exportación") from exc

    @app.get("/actions", response_model=list[ProposedActionResponse])
    async def actions(
        limit: Annotated[int, Query(ge=1, le=100)] = 50,
        offset: Annotated[int, Query(ge=0)] = 0,
        include_resolved: bool = Query(False),
        include_dismissed: bool | None = Query(None),
    ):
        try:
            with app.state.session_factory() as session:
                # ``include_dismissed`` is retained as a legacy alias. It has always
                # returned both dismissed and exported proposals.
                items = await run_in_threadpool(
                    list_proposed_actions,
                    session,
                    include_resolved or bool(include_dismissed),
                    limit,
                    offset,
                    bool(include_dismissed),
                )
                return [proposed_action_response(item) for item in items]
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudieron consultar las acciones") from exc

    @app.post("/actions/dismiss-pending", response_model=DismissPendingActionsResponse)
    @serialized
    async def dismiss_all_pending_actions():
        try:
            with app.state.session_factory() as session:
                count = dismiss_pending_actions(session)
                session.commit()
                return DismissPendingActionsResponse(dismissed_count=count)
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudieron descartar las acciones pendientes") from exc

    @app.delete("/actions/pending", response_model=DeletedActionsResponse)
    @serialized
    async def delete_all_pending_actions():
        try:
            with app.state.session_factory() as session:
                count = delete_pending_actions(session)
                session.commit()
                return DeletedActionsResponse(deleted_count=count)
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudieron eliminar las acciones pendientes") from exc

    @app.delete("/actions/exported", response_model=DeletedActionsResponse)
    @serialized
    async def delete_all_exported_actions():
        try:
            with app.state.session_factory() as session:
                count = delete_exported_actions(session)
                session.commit()
                return DeletedActionsResponse(deleted_count=count)
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudieron limpiar las acciones gestionadas") from exc

    @app.delete("/actions/{action_id}", response_model=DeletedActionsResponse)
    @serialized
    async def delete_action(action_id: uuid.UUID):
        try:
            with app.state.session_factory() as session:
                if not delete_proposed_action(session, action_id):
                    raise HTTPException(404, "Acción no encontrada")
                session.commit()
                return DeletedActionsResponse(deleted_count=1)
        except HTTPException:
            raise
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo eliminar la acción") from exc

    @app.patch("/actions/{action_id}", response_model=ProposedActionResponse)
    @serialized
    async def update_action(action_id: uuid.UUID, request: ProposedActionUpdate):
        values = request.model_dump(exclude_unset=True)
        try:
            with app.state.session_factory() as session:
                item = get_proposed_action(session, action_id)
                if item is None:
                    raise HTTPException(404, "Acción no encontrada")
                if item.kind in {"task", "reminder"} and "due_text" in values and not values.get("start_at"):
                    values["start_at"], values["all_day"] = concrete_calendar_fields(values["due_text"])
                try:
                    validate_event_dates(values.get("start_at", item.start_at), values.get("end_at", item.end_at), values.get("all_day", item.all_day))
                except ValueError as exc:
                    raise HTTPException(422, "Fechas inválidas; usa fecha completa y offset horario") from exc
                for key, value in values.items():
                    setattr(item, key, value)
                session.commit()
                session.refresh(item)
                session.expunge(item)
                return proposed_action_response(item)
        except HTTPException:
            raise
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo actualizar la acción") from exc

    def deletion_response(counts) -> DeletionResponse:
        return DeletionResponse(
            interactions_deleted=counts.interactions_deleted,
            actions_deleted=counts.actions_deleted,
            daily_summaries_deleted=counts.daily_summaries_deleted,
        )

    @app.delete("/continuous-sessions/{session_id}", response_model=DeletionResponse)
    @serialized
    async def delete_continuous_session(session_id: uuid.UUID):
        try:
            with app.state.session_factory() as session:
                try:
                    counts = delete_continuous_session_and_derived_data(
                        session, session_id, app.state.settings.app_timezone
                    )
                except LookupError as exc:
                    raise HTTPException(404, str(exc)) from exc
                session.commit()
                return deletion_response(counts)
        except HTTPException:
            raise
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo eliminar la sesión Continua") from exc

    @app.get("/interactions/{interaction_id}", response_model=InteractionResponse)
    async def interaction_detail(interaction_id: uuid.UUID):
        try:
            with app.state.session_factory() as session:
                interaction = await run_in_threadpool(get_interaction, session, interaction_id)
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo consultar el histórico") from exc
        if interaction is None:
            raise HTTPException(404, "Interacción no encontrada")
        return interaction_response(interaction)

    @app.delete("/interactions/{interaction_id}", response_model=DeletionResponse)
    @serialized
    async def delete_interaction(interaction_id: uuid.UUID):
        try:
            with app.state.session_factory() as session:
                interaction = get_interaction(session, interaction_id)
                if interaction is None:
                    raise HTTPException(404, "Interacción no encontrada")
                try:
                    counts = delete_interactions_and_derived_data(
                        session, [interaction], app.state.settings.app_timezone
                    )
                except ValueError as exc:
                    raise HTTPException(409, str(exc)) from exc
                session.commit()
                return deletion_response(counts)
        except HTTPException:
            raise
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo eliminar la interacción") from exc

    @app.post("/interactions/delete", response_model=DeletionResponse)
    @serialized
    async def delete_interactions(request: InteractionDeleteRequest):
        if not request.interaction_ids or len(set(request.interaction_ids)) != len(request.interaction_ids):
            raise HTTPException(422, "La selección debe contener identificadores únicos")
        try:
            with app.state.session_factory() as session:
                interactions = [get_interaction(session, item) for item in request.interaction_ids]
                if any(item is None for item in interactions):
                    raise HTTPException(404, "Alguna interacción no existe")
                try:
                    counts = delete_interactions_and_derived_data(
                        session, interactions, app.state.settings.app_timezone
                    )
                except ValueError as exc:
                    raise HTTPException(409, str(exc)) from exc
                session.commit()
                return deletion_response(counts)
        except HTTPException:
            raise
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo eliminar la selección") from exc

    @app.get("/interactions", response_model=list[InteractionResponse])
    async def interaction_history(
        limit: Annotated[int, Query(ge=1, le=100)] = 50,
        offset: Annotated[int, Query(ge=0)] = 0,
        from_: Annotated[datetime | None, Query(alias="from")] = None,
        to: datetime | None = None,
        q: Annotated[str | None, Query(min_length=1, max_length=500)] = None,
    ):
        try:
            if q is not None and not q.strip():
                return []
            if from_ is not None:
                from_ = to_utc(ensure_aware(from_))
            if to is not None:
                to = to_utc(ensure_aware(to))
            if from_ is not None and to is not None and from_ > to:
                raise HTTPException(422, "from debe ser anterior o igual a to")
            with app.state.session_factory() as session:
                items = await run_in_threadpool(
                    list_interactions, session, from_, to, limit, offset, True, q
                )
        except HTTPException:
            raise
        except ValueError as exc:
            raise HTTPException(422, "from y to deben incluir zona horaria") from exc
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo consultar el histórico") from exc
        return [interaction_response(item) for item in items]

    @app.get("/days/activity")
    async def days_activity(
        from_: Annotated[date, Query(alias="from")], to: date,
    ):
        if to < from_:
            raise HTTPException(422, "to debe ser posterior a from")
        start, _ = day_interval(from_, app.state.settings.app_timezone)
        _, end = day_interval(to, app.state.settings.app_timezone)
        try:
            with app.state.session_factory() as session:
                days = await run_in_threadpool(activity_days, session, start, end, app.state.settings.app_timezone)
            return {"days": days}
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo consultar la actividad") from exc

    @app.get("/days/{day}", response_model=DayResponse)
    async def day_detail(day: date):
        try:
            interactions, sessions, fingerprint, summary = await run_in_threadpool(load_day, day)
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo consultar el diario") from exc
        return day_response(day, interactions, sessions, fingerprint, summary)

    def save_daily_summary(day: date, expected_fingerprint: str, generation):
        start, end = day_interval(day, app.state.settings.app_timezone)
        try:
            with app.state.session_factory() as session:
                try:
                    current = list_interactions(session, start, end)
                    session_ids = {
                        item.capture_session_id
                        for item in current
                        if item.capture_mode == "continuous" and item.capture_session_id is not None
                    }
                    sessions = get_continuous_sessions(session, session_ids)
                    if daily_source_fingerprint(current, sessions) != expected_fingerprint:
                        return None
                    summary = upsert_daily_summary(
                        session,
                        day=day,
                        timezone=app.state.settings.app_timezone,
                        result=generation.result.model_dump(mode="json"),
                        source_fingerprint=expected_fingerprint,
                        llm_model=generation.model,
                        generated_at=datetime.now(timezone.utc),
                    )
                    session.commit()
                    session.refresh(summary)
                    session.expunge(summary)
                    return summary
                except SQLAlchemyError:
                    session.rollback()
                    raise
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo guardar el resumen diario") from exc

    @app.post("/days/{day}/summary", response_model=DailySummaryState)
    @serialized
    async def generate_day_summary(day: date):
        try:
            interactions, sessions, fingerprint, _ = await run_in_threadpool(load_day, day)
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo consultar el diario") from exc
        if not interactions:
            raise HTTPException(409, "No hay interacciones para resumir ese día")
        text_size = sum(len(item.transcription) for item in interactions)
        if text_size > MAX_DAILY_TRANSCRIPTION_CHARS:
            raise HTTPException(413, "El día supera el límite de texto para resumen directo; el resumen jerárquico queda pendiente en el MVP")
        generation = await run_in_threadpool(summarize_day, daily_transcriptions(interactions))
        summary = await run_in_threadpool(save_daily_summary, day, fingerprint, generation)
        if summary is None:
            raise HTTPException(409, "El día cambió durante la generación; inténtalo de nuevo")
        return daily_summary_state(summary, fingerprint)

    @app.patch("/days/{day}/summary", response_model=DailySummaryState)
    @serialized
    async def update_day_summary(day: date, request: DailySummaryUpdate):
        values = request.model_dump(exclude_unset=True)
        try:
            with app.state.session_factory() as session:
                summary = get_daily_summary(session, day)
                if summary is None:
                    raise HTTPException(404, "No hay resumen para editar")
                result = DailySummaryResult.model_validate(summary.result).model_dump(mode="json")
                result.update(values)
                summary.result = DailySummaryResult.model_validate(result).model_dump(mode="json")
                summary.manually_edited = True
                session.commit()
                start, end = day_interval(day, app.state.settings.app_timezone)
                interactions = list_interactions(session, start, end)
                session_ids = {item.capture_session_id for item in interactions if item.capture_session_id}
                state = daily_summary_state(summary, daily_source_fingerprint(interactions, get_continuous_sessions(session, session_ids)))
                return state
        except HTTPException:
            raise
        except SQLAlchemyError as exc:
            raise HTTPException(503, "No se pudo editar el resumen") from exc

    def task_response(item) -> TaskItemResponse:
        return TaskItemResponse(id=item.id, text=item.text, completed=item.completed, group_name=item.group_name, parent_id=item.parent_id, sort_order=item.sort_order, due_at=to_utc(item.due_at) if item.due_at else None, all_day=item.all_day, source_action_id=item.source_action_id, created_at=to_utc(item.created_at), updated_at=to_utc(item.updated_at))

    @app.get("/tasks", response_model=list[TaskItemResponse])
    async def tasks():
        with app.state.session_factory() as session:
            return [task_response(item) for item in await run_in_threadpool(list_task_items, session)]

    @app.post("/tasks", response_model=TaskItemResponse)
    @serialized
    async def create_task(request: TaskItemCreate):
        try:
            with app.state.session_factory() as session:
                values = request.model_dump()
                requested_id = values.get("id")
                if requested_id is not None:
                    existing = session.get(TaskItem, requested_id)
                    if existing is not None:
                        return task_response(existing)
                item = create_task_item(session, **values)
                session.commit(); session.refresh(item); session.expunge(item)
                return task_response(item)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    @app.patch("/tasks/{task_id}", response_model=TaskItemResponse)
    @serialized
    async def patch_task(task_id: uuid.UUID, request: TaskItemUpdate):
        try:
            with app.state.session_factory() as session:
                item = session.get(TaskItem, task_id)
                if item is None: raise HTTPException(404, "Tarea no encontrada")
                update_task_item(session, item, request.model_dump(exclude_unset=True))
                session.commit(); session.refresh(item); session.expunge(item)
                return task_response(item)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    @app.post("/tasks/reorder", response_model=list[TaskItemResponse])
    @serialized
    async def reorder_tasks(request: TaskReorderRequest):
        try:
            with app.state.session_factory() as session:
                items = reorder_task_items(session, [item.model_dump() for item in request.items])
                session.commit()
                return [task_response(item) for item in items]
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    @app.delete("/tasks/{task_id}")
    @serialized
    async def delete_task(task_id: uuid.UUID):
        with app.state.session_factory() as session:
            item = session.get(TaskItem, task_id)
            if item is None: raise HTTPException(404, "Tarea no encontrada")
            if session.scalar(select(TaskItem.id).where(TaskItem.parent_id == task_id)):
                raise HTTPException(409, "Primero elimina o desindenta las subtareas")
            session.delete(item); session.commit()
        return {"deleted": True}

    @app.post("/actions/{action_id}/add-to-tasks", response_model=TaskItemResponse)
    @serialized
    async def add_action_to_tasks(action_id: uuid.UUID):
        try:
            with app.state.session_factory() as session:
                action = get_proposed_action(session, action_id)
                if action is None: raise HTTPException(404, "Acción no encontrada")
                if action.kind not in {"task", "reminder"}: raise HTTPException(422, "Solo una tarea puede añadirse a Tareas")
                item = session.scalar(select(TaskItem).where(TaskItem.source_action_id == action_id))
                if item is None:
                    due_at = None
                    if action.start_at:
                        if action.all_day:
                            local_date = date.fromisoformat(action.start_at)
                            due_at = datetime.combine(
                                local_date,
                                datetime.min.time(),
                                tzinfo=ZoneInfo(app.state.settings.app_timezone),
                            )
                        else:
                            due_at = datetime.fromisoformat(action.start_at.replace("Z", "+00:00"))
                    item = create_task_item(session, text=action.title, due_at=due_at, all_day=action.all_day, source_action_id=action.id)
                action.status = "exported"
                session.commit(); session.refresh(item); session.expunge(item)
                return task_response(item)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc

    return app


app = create_app()
