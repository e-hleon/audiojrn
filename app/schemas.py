"""Contratos validados de la API de análisis."""
from datetime import date, datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from zoneinfo import ZoneInfo


def validate_event_dates(start, end, all_day):
    def parse(value):
        if all_day:
            return date.fromisoformat(value)
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if result.tzinfo is None:
            raise ValueError("La hora requiere zona horaria")
        return result
    beginning = parse(start) if start else None
    ending = parse(end) if end else None
    if ending is not None and (beginning is None or ending <= beginning):
        raise ValueError("El fin debe ser posterior al inicio")


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Task(StrictModel):
    text: str
    due_at: str | None
    evidence: str


class EventCandidate(StrictModel):
    title: str
    start_at: str | None = None
    end_at: str | None = None
    all_day: bool = False
    location: str | None = None
    evidence: str

    @model_validator(mode="after")
    def dates(self):
        validate_event_dates(self.start_at, self.end_at, self.all_day)
        return self


class Highlight(StrictModel):
    text: str
    evidence: str


class AnalysisResult(StrictModel):
    highlights: list[Highlight] = Field(default_factory=list)
    tasks: list[Task] = Field(default_factory=list)
    events: list[EventCandidate] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def normalize_legacy_result(cls, value):
        """Read old JSONB rows without expanding the public/LLM schema again."""
        if not isinstance(value, dict):
            return value
        result = dict(value)
        highlights = []
        seen_highlights = set()
        for item in result.get("highlights") or []:
            if isinstance(item, Highlight):
                item = item.model_dump(mode="json")
            if not isinstance(item, dict):
                continue
            key = (item.get("text"), item.get("evidence"))
            if key not in seen_highlights:
                highlights.append(item)
                seen_highlights.add(key)
        for item in result.pop("decisions", []) or []:
            if isinstance(item, Highlight):
                item = item.model_dump(mode="json")
            if not isinstance(item, dict):
                continue
            converted = {"text": item.get("text"), "evidence": item.get("evidence")}
            key = (converted["text"], converted["evidence"])
            if key not in seen_highlights:
                highlights.append(converted)
                seen_highlights.add(key)
        tasks = []
        seen_tasks = set()
        for item in list(result.get("tasks") or []) + list(result.pop("reminders", []) or []):
            if isinstance(item, Task):
                item = item.model_dump(mode="json")
            if not isinstance(item, dict):
                continue
            converted = {
                "text": item.get("text"),
                "due_at": item.get("due_at", item.get("due_date", item.get("when"))),
                "evidence": item.get("evidence"),
            }
            key = (converted["text"], converted["due_at"], converted["evidence"])
            if key not in seen_tasks:
                tasks.append(converted)
                seen_tasks.add(key)
        result["highlights"] = highlights
        result["tasks"] = tasks
        result.pop("summary", None)
        result.pop("topics", None)
        return result



class DailySummaryResult(StrictModel):
    summary: str
    highlights: list[str] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def normalize_legacy_summary(cls, value):
        if isinstance(value, dict):
            value = dict(value)
            value.pop("topics", None)
            value.setdefault("highlights", [])
        return value


class InteractionTranscription(StrictModel):
    text: str
    language: str | None
    model: str
    device: str | None
    compute_type: str | None


class InteractionResponse(StrictModel):
    id: UUID
    capture_mode: str
    capture_session_id: UUID | None
    chunk_index: int | None
    capture_chunk_id: UUID | None
    recorded_at: datetime
    created_at: datetime
    transcription: InteractionTranscription
    analysis: AnalysisResult
    analysis_model: str | None


class DailySummaryState(StrictModel):
    status: Literal["missing", "ready", "stale"]
    result: DailySummaryResult | None
    generated_at: datetime | None
    model: str | None
    updated_at: datetime | None = None
    manually_edited: bool = False


class DayResponse(StrictModel):
    day: date
    timezone: str
    interactions: list[InteractionResponse]
    events: list[EventCandidate]
    highlights: list[Highlight] = Field(default_factory=list)
    summary: DailySummaryState


class AnalysisRequest(StrictModel):
    # El límite acota coste y contexto sin imponer una persistencia.
    text: Annotated[str, Field(max_length=20_000)]
    reference_datetime: datetime | None = None
    timezone: str | None = None

    @model_validator(mode="after")
    def temporal_context(self):
        if self.reference_datetime and self.reference_datetime.tzinfo is None:
            raise ValueError("reference_datetime requiere zona horaria")
        if self.timezone:
            try:
                ZoneInfo(self.timezone)
            except (KeyError, ValueError) as exc:
                raise ValueError("timezone debe ser IANA") from exc
        if bool(self.reference_datetime) != bool(self.timezone):
            raise ValueError("Proporciona reference_datetime y timezone conjuntamente")
        return self

    @field_validator("text")
    @classmethod
    def text_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("El texto no puede estar vacío")
        return value


class ContinuousSessionFinalizeRequest(StrictModel):
    last_chunk_index: Annotated[int, Field(ge=0)]


class ContinuousSessionResponse(StrictModel):
    session_id: UUID
    status: Literal["open", "complete"]
    last_chunk_index: int | None
    analysis: AnalysisResult | None
    finalized_at: datetime | None


class ProposedActionResponse(StrictModel):
    id: UUID
    kind: Literal["task", "reminder", "event"]
    status: Literal["pending", "dismissed", "exported"]
    title: str
    due_text: str | None
    start_at: str | None
    end_at: str | None
    all_day: bool
    location: str | None
    notes: str | None
    evidence: str
    source_interaction_id: UUID | None
    source_session_id: UUID | None
    created_at: datetime


class ExportDailySummary(StrictModel):
    day: date
    timezone: str
    summary: str
    highlights: list[str] = Field(default_factory=list)
    generated_at: datetime
    updated_at: datetime
    manually_edited: bool


class ExportContinuousSession(StrictModel):
    id: UUID
    started_at: datetime
    last_chunk_index: int | None
    status: Literal["open", "complete", "invalidated"]
    analysis: AnalysisResult | None
    finalized_at: datetime | None
    created_at: datetime
    updated_at: datetime


class ExportProposedAction(ProposedActionResponse):
    updated_at: datetime


class ExportBackendData(StrictModel):
    timezone: str
    interactions: list[InteractionResponse]
    continuous_sessions: list[ExportContinuousSession]
    daily_summaries: list[ExportDailySummary]
    actions: list[ExportProposedAction]


class ProposedActionUpdate(StrictModel):
    title: str | None = None
    due_text: str | None = None
    start_at: str | None = None
    end_at: str | None = None
    all_day: bool | None = None
    location: str | None = None
    notes: str | None = None
    status: Literal["pending", "dismissed", "exported"] | None = None

    @field_validator("start_at", "end_at", "due_text", "location", "notes")
    @classmethod
    def blank_clears_optional_value(cls, value):
        return value if value is None or value.strip() else None

    @field_validator("title", "status", "all_day")
    @classmethod
    def non_nullable_when_present(cls, value):
        if value is None or (isinstance(value, str) and not value.strip()):
            raise ValueError("El campo no admite null ni texto vacío")
        return value


class DismissPendingActionsResponse(StrictModel):
    dismissed_count: int


class DeletedActionsResponse(StrictModel):
    deleted_count: int


class DailySummaryUpdate(StrictModel):
    summary: str | None = None
    highlights: list[str] | None = None


class TaskItemCreate(StrictModel):
    id: UUID | None = None
    text: Annotated[str, Field(min_length=1, max_length=10_000)]
    group_name: str | None = None
    parent_id: UUID | None = None
    sort_order: int = 0
    due_at: datetime | None = None
    all_day: bool = False


class TaskItemUpdate(StrictModel):
    text: Annotated[str, Field(min_length=1, max_length=10_000)] | None = None
    completed: bool | None = None
    group_name: str | None = None
    parent_id: UUID | None = None
    sort_order: int | None = None
    due_at: datetime | None = None
    all_day: bool | None = None


class TaskItemResponse(StrictModel):
    id: UUID
    text: str
    completed: bool
    group_name: str | None
    parent_id: UUID | None
    sort_order: int
    due_at: datetime | None
    all_day: bool
    source_action_id: UUID | None
    created_at: datetime
    updated_at: datetime


class TaskOrder(StrictModel):
    id: UUID
    group_name: str | None
    sort_order: int


class TaskReorderRequest(StrictModel):
    items: list[TaskOrder]


class DeletionResponse(StrictModel):
    interactions_deleted: int
    actions_deleted: int
    daily_summaries_deleted: int


class InteractionDeleteRequest(StrictModel):
    interaction_ids: list[UUID]
