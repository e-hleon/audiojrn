"""Operaciones de persistencia sin lógica HTTP."""
from dataclasses import dataclass
from datetime import date, datetime
import hashlib
import uuid

from sqlalchemy import delete, func, or_, select, update
from sqlalchemy.orm import Session
from zoneinfo import ZoneInfo

from app.models import ContinuousSession, DailySummary, Interaction, ProposedAction, TaskItem
from app.schemas import AnalysisResult
from app.time_utils import ensure_aware, to_utc


@dataclass(frozen=True)
class DeletionCounts:
    interactions_deleted: int
    actions_deleted: int
    daily_summaries_deleted: int


def create_interaction(session: Session, **values) -> Interaction:
    values["recorded_at"] = to_utc(values["recorded_at"])
    interaction = Interaction(**values)
    session.add(interaction)
    session.flush()
    return interaction


def get_interaction(session: Session, interaction_id: uuid.UUID) -> Interaction | None:
    return session.get(Interaction, interaction_id)


def get_interaction_by_capture_chunk_id(session: Session, capture_chunk_id: uuid.UUID) -> Interaction | None:
    return session.scalar(
        select(Interaction).where(Interaction.capture_chunk_id == capture_chunk_id)
    )


def get_capture_session_language(session: Session, capture_session_id: uuid.UUID) -> str | None:
    statement = (
        select(Interaction.language)
        .where(
            Interaction.capture_session_id == capture_session_id,
            Interaction.capture_mode == "continuous",
            Interaction.language.is_not(None),
            Interaction.language != "",
        )
        .order_by(Interaction.chunk_index, Interaction.recorded_at, Interaction.id)
    )
    return session.scalar(statement)


def get_continuous_session(session: Session, session_id: uuid.UUID) -> ContinuousSession | None:
    return session.get(ContinuousSession, session_id)


def get_continuous_chunks(session: Session, session_id: uuid.UUID) -> list[Interaction]:
    statement = (
        select(Interaction)
        .where(
            Interaction.capture_session_id == session_id,
            Interaction.capture_mode == "continuous",
        )
        .order_by(Interaction.chunk_index, Interaction.recorded_at, Interaction.id)
    )
    return list(session.scalars(statement))


def join_continuous_transcriptions(transcriptions: list[str]) -> str:
    """Recompose one semantic block without exposing technical chunk boundaries."""
    return " ".join(text.strip() for text in transcriptions if text.strip())


def get_continuous_sessions(session: Session, session_ids: set[uuid.UUID]) -> dict[uuid.UUID, ContinuousSession]:
    if not session_ids:
        return {}
    statement = select(ContinuousSession).where(ContinuousSession.id.in_(session_ids))
    return {item.id: item for item in session.scalars(statement)}


def create_continuous_session(session: Session, session_id: uuid.UUID, started_at: datetime) -> ContinuousSession:
    result = ContinuousSession(id=session_id, started_at=to_utc(started_at))
    session.add(result)
    session.flush()
    return result


def session_source_fingerprint(chunks: list[Interaction]) -> str:
    entries = [
        f"{chunk.id}:{chunk.chunk_index}:{to_utc(ensure_aware(chunk.updated_at)).isoformat()}"
        for chunk in chunks
    ]
    return hashlib.sha256("\n".join(entries).encode("utf-8")).hexdigest()


def daily_source_fingerprint(
    interactions: list[Interaction], sessions: dict[uuid.UUID, ContinuousSession]
) -> str:
    entries = [
        f"interaction:{interaction.id}:{to_utc(ensure_aware(interaction.updated_at)).isoformat()}"
        for interaction in interactions
    ]
    entries.extend(
        f"session:{item.id}:{item.status}:{item.source_fingerprint}:{to_utc(ensure_aware(item.updated_at)).isoformat()}"
        for item in sessions.values()
    )
    return hashlib.sha256("\n".join(sorted(entries)).encode("utf-8")).hexdigest()


def concrete_calendar_fields(value: str | None) -> tuple[str | None, bool]:
    """Return only concrete dates usable by the calendar UI.

    The original temporal expression remains in ``due_text`` for traceability.
    A date-only value is deliberately all-day; a time needs an explicit offset.
    """
    if not value:
        return None, False
    try:
        return date.fromisoformat(value).isoformat(), True
    except ValueError:
        pass
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None, False
    if parsed.tzinfo is None:
        return None, False
    return parsed.isoformat(), False


def create_proposed_actions(
    session: Session,
    analysis: AnalysisResult,
    *,
    source_key_prefix: str,
    source_interaction_id: uuid.UUID | None = None,
    source_session_id: uuid.UUID | None = None,
) -> list[ProposedAction]:
    values = []
    for index, item in enumerate(analysis.tasks):
        start_at, all_day = concrete_calendar_fields(item.due_at)
        values.append({
            "source_key": f"{source_key_prefix}:task:{index}",
            "kind": "task", "title": item.text, "due_text": item.due_at,
            "start_at": start_at, "all_day": all_day,
            "evidence": item.evidence,
        })
    for index, item in enumerate(analysis.events):
        values.append({
            "source_key": f"{source_key_prefix}:event:{index}",
            "kind": "event", "title": item.title, "start_at": item.start_at,
            "end_at": item.end_at, "all_day": item.all_day, "location": item.location,
            "evidence": item.evidence,
        })
    created = []
    for value in values:
        existing = session.scalar(select(ProposedAction).where(ProposedAction.source_key == value["source_key"]))
        if existing is not None:
            created.append(existing)
            continue
        action = ProposedAction(
            **value,
            source_interaction_id=source_interaction_id,
            source_session_id=source_session_id,
        )
        session.add(action)
        created.append(action)
    session.flush()
    return created


def list_proposed_actions(
    session: Session,
    include_resolved: bool = False,
    limit: int = 50,
    offset: int = 0,
    include_legacy_dismissed: bool = False,
) -> list[ProposedAction]:
    statement = select(ProposedAction)
    if include_legacy_dismissed:
        pass
    elif include_resolved:
        statement = statement.where(ProposedAction.status.in_(("pending", "exported")))
    else:
        statement = statement.where(ProposedAction.status == "pending")
    statement = statement.order_by(ProposedAction.created_at.desc(), ProposedAction.id.desc())
    return list(session.scalars(statement.offset(offset).limit(limit)))


def list_export_actions(session: Session) -> list[ProposedAction]:
    """Current user-visible actions, without legacy dismissed rows."""
    statement = (
        select(ProposedAction)
        .where(ProposedAction.status.in_(("pending", "exported")))
        .order_by(ProposedAction.created_at, ProposedAction.id)
    )
    return list(session.scalars(statement))


def dismiss_pending_actions(session: Session) -> int:
    result = session.execute(
        update(ProposedAction)
        .where(ProposedAction.status == "pending")
        .values(status="dismissed", updated_at=func.now())
    )
    return result.rowcount or 0


def delete_pending_actions(session: Session) -> int:
    result = session.execute(delete(ProposedAction).where(ProposedAction.status == "pending"))
    return result.rowcount or 0


def delete_exported_actions(session: Session) -> int:
    # TaskItem.source_action_id uses a FK. Preserve the user-owned task while
    # detaching its historical proposal before deleting only exported rows.
    session.execute(
        update(TaskItem)
        .where(TaskItem.source_action_id.in_(
            select(ProposedAction.id).where(ProposedAction.status == "exported")
        ))
        .values(source_action_id=None, updated_at=func.now())
    )
    result = session.execute(delete(ProposedAction).where(ProposedAction.status == "exported"))
    return result.rowcount or 0


def delete_proposed_action(session: Session, action_id: uuid.UUID) -> bool:
    return bool(session.execute(
        delete(ProposedAction).where(
            ProposedAction.id == action_id,
            ProposedAction.status == "pending",
        )
    ).rowcount)


def delete_interaction_and_derived_data(
    session: Session, interaction: Interaction, timezone_name: str
) -> DeletionCounts:
    action_ids = select(ProposedAction.id).where(
        ProposedAction.source_interaction_id == interaction.id
    )
    session.execute(
        update(TaskItem)
        .where(TaskItem.source_action_id.in_(action_ids))
        .values(source_action_id=None, updated_at=func.now())
    )
    actions = session.execute(
        delete(ProposedAction).where(ProposedAction.source_interaction_id == interaction.id)
    ).rowcount or 0
    interactions = session.execute(delete(Interaction).where(Interaction.id == interaction.id)).rowcount or 0
    return DeletionCounts(interactions, actions, 0)


def delete_interactions_and_derived_data(
    session: Session, interactions: list[Interaction], timezone_name: str
) -> DeletionCounts:
    # A persisted "open" marker can be historical after an Android crash.
    # Actual active/pending-finalize sessions are protected by Android preflight.
    deleted_interactions = 0
    deleted_actions = 0
    affected_sessions = {
        item.capture_session_id for item in interactions
        if item.capture_mode == "continuous" and item.capture_session_id is not None
    }
    manual = [item for item in interactions if item.capture_session_id not in affected_sessions]
    for interaction in manual:
        counts = delete_interaction_and_derived_data(session, interaction, timezone_name)
        deleted_interactions += counts.interactions_deleted
        deleted_actions += counts.actions_deleted
    for session_id in affected_sessions:
        action_ids = select(ProposedAction.id).where(ProposedAction.source_session_id == session_id)
        session.execute(
            update(TaskItem).where(TaskItem.source_action_id.in_(action_ids))
            .values(source_action_id=None, updated_at=func.now())
        )
        deleted_actions += session.execute(
            delete(ProposedAction).where(ProposedAction.source_session_id == session_id)
        ).rowcount or 0
        ids = [item.id for item in interactions if item.capture_session_id == session_id]
        deleted_interactions += session.execute(
            delete(Interaction).where(Interaction.id.in_(ids))
        ).rowcount or 0
        block = get_continuous_session(session, session_id)
        remaining = get_continuous_chunks(session, session_id)
        if not remaining:
            if block is not None:
                session.delete(block)
        elif block is not None:
            block.status = "invalidated"
            block.analysis = None
            block.analysis_model = None
            block.source_fingerprint = None
            block.updated_at = func.now()
    return DeletionCounts(deleted_interactions, deleted_actions, 0)


def delete_continuous_session_and_derived_data(
    session: Session, session_id: uuid.UUID, timezone_name: str
) -> DeletionCounts:
    chunks = get_continuous_chunks(session, session_id)
    continuous_session = get_continuous_session(session, session_id)
    if continuous_session is None and not chunks:
        raise LookupError("Sesión Continua no encontrada")
    chunk_ids = [item.id for item in chunks]
    action_filters = [ProposedAction.source_session_id == session_id]
    if chunk_ids:
        action_filters.append(ProposedAction.source_interaction_id.in_(chunk_ids))
    action_ids = select(ProposedAction.id).where(or_(*action_filters))
    session.execute(
        update(TaskItem)
        .where(TaskItem.source_action_id.in_(action_ids))
        .values(source_action_id=None, updated_at=func.now())
    )
    actions = session.execute(delete(ProposedAction).where(or_(*action_filters))).rowcount or 0
    interactions = session.execute(
        delete(Interaction).where(
            Interaction.capture_mode == "continuous",
            Interaction.capture_session_id == session_id,
        )
    ).rowcount or 0
    if continuous_session is not None:
        session.execute(delete(ContinuousSession).where(ContinuousSession.id == session_id))
    return DeletionCounts(interactions, actions, 0)


def get_proposed_action(session: Session, action_id: uuid.UUID) -> ProposedAction | None:
    return session.get(ProposedAction, action_id)


def list_interactions(
    session: Session,
    start: datetime | None = None,
    end: datetime | None = None,
    limit: int | None = None,
    offset: int = 0,
    newest_first: bool = False,
    query: str | None = None,
) -> list[Interaction]:
    statement = select(Interaction).where(
        or_(
            Interaction.capture_mode != "continuous",
            func.trim(Interaction.transcription) != "",
        )
    )
    if start is not None:
        statement = statement.where(Interaction.recorded_at >= to_utc(start))
    if end is not None:
        statement = statement.where(Interaction.recorded_at < to_utc(end))
    if query is not None:
        escaped = query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        statement = statement.where(
            func.trim(Interaction.transcription) != "",
            Interaction.transcription.ilike(f"%{escaped}%", escape="\\"),
        )
    order = (
        (Interaction.recorded_at.desc(), Interaction.id.desc())
        if newest_first
        else (Interaction.recorded_at, Interaction.id)
    )
    statement = statement.order_by(*order).offset(offset)
    if limit is not None:
        statement = statement.limit(limit)
    return list(session.scalars(statement))


def list_export_daily_summaries(session: Session) -> list[DailySummary]:
    return list(session.scalars(select(DailySummary).order_by(DailySummary.day)))


def list_export_continuous_sessions(session: Session) -> list[ContinuousSession]:
    statement = select(ContinuousSession).order_by(ContinuousSession.started_at, ContinuousSession.id)
    return list(session.scalars(statement))


def upsert_daily_summary(
    session: Session,
    *,
    day: date,
    timezone: str,
    result: dict,
    source_fingerprint: str,
    generated_at: datetime,
    llm_model: str | None = None,
) -> DailySummary:
    summary = session.scalar(select(DailySummary).where(DailySummary.day == day))
    values = {
        "timezone": timezone,
        "result": result,
        "source_fingerprint": source_fingerprint,
        "generated_at": to_utc(generated_at),
        "llm_model": llm_model,
        "manually_edited": False,
    }
    if summary is None:
        summary = DailySummary(day=day, **values)
        session.add(summary)
    else:
        for key, value in values.items():
            setattr(summary, key, value)
    session.flush()
    return summary


def get_daily_summary(session: Session, day: date) -> DailySummary | None:
    return session.scalar(select(DailySummary).where(DailySummary.day == day))


def interactions_fingerprint(interactions: list[Interaction]) -> str:
    entries = sorted(
        f"{interaction.id}:{to_utc(ensure_aware(interaction.updated_at)).isoformat()}"
        for interaction in interactions
    )
    return hashlib.sha256("\n".join(entries).encode("utf-8")).hexdigest()


def activity_days(session: Session, start: datetime, end: datetime, timezone_name: str) -> list[date]:
    rows = list(session.scalars(select(Interaction.recorded_at).where(
        Interaction.recorded_at >= to_utc(start),
        Interaction.recorded_at < to_utc(end),
        or_(Interaction.capture_mode != "continuous", func.trim(Interaction.transcription) != ""),
    )))
    zone = ZoneInfo(timezone_name)
    return sorted({to_utc(ensure_aware(item)).astimezone(zone).date() for item in rows})


def list_task_items(session: Session) -> list[TaskItem]:
    return list(session.scalars(select(TaskItem).order_by(TaskItem.group_name.nulls_last(), TaskItem.sort_order, TaskItem.created_at, TaskItem.id)))


def validate_task_parent(session: Session, task_id: uuid.UUID | None, parent_id: uuid.UUID | None) -> None:
    if parent_id is None:
        return
    parent = session.get(TaskItem, parent_id)
    if parent is None:
        raise ValueError("La tarea padre no existe")
    if task_id == parent_id or parent.parent_id is not None:
        raise ValueError("Solo se permite un nivel de subtareas")
    if task_id is not None and session.scalar(
        select(TaskItem.id).where(TaskItem.parent_id == task_id).limit(1)
    ) is not None:
        raise ValueError("Una tarea con subtareas no puede convertirse en subtarea")


def create_task_item(session: Session, **values) -> TaskItem:
    validate_task_parent(session, None, values.get("parent_id"))
    if values.get("parent_id") is not None:
        values["group_name"] = session.get(TaskItem, values["parent_id"]).group_name
    if values.get("due_at") is not None:
        values["due_at"] = to_utc(values["due_at"])
    if "sort_order" not in values:
        minimum = session.scalar(select(func.min(TaskItem.sort_order)).where(
            TaskItem.group_name.is_(values.get("group_name")) if values.get("group_name") is None
            else TaskItem.group_name == values.get("group_name"),
            TaskItem.parent_id.is_(values.get("parent_id")) if values.get("parent_id") is None
            else TaskItem.parent_id == values.get("parent_id"),
        ))
        values["sort_order"] = (minimum if minimum is not None else 0) - 1
    item = TaskItem(**values)
    session.add(item)
    session.flush()
    return item


def update_task_item(session: Session, item: TaskItem, values: dict) -> TaskItem:
    if "parent_id" in values:
        validate_task_parent(session, item.id, values["parent_id"])
    if "due_at" in values and values["due_at"] is not None:
        values["due_at"] = to_utc(values["due_at"])
    parent_id = values.get("parent_id", item.parent_id)
    if parent_id is not None:
        values["group_name"] = session.get(TaskItem, parent_id).group_name
    for key, value in values.items():
        setattr(item, key, value)
    if "group_name" in values and item.parent_id is None:
        for child in session.scalars(select(TaskItem).where(TaskItem.parent_id == item.id)):
            child.group_name = item.group_name
    session.flush()
    return item


def reorder_task_items(session: Session, requested: list[dict]) -> list[TaskItem]:
    """Apply one complete drag result atomically without changing parent links."""
    items = list_task_items(session)
    by_id = {item.id: item for item in items}
    requested_ids = [entry["id"] for entry in requested]
    if len(requested_ids) != len(set(requested_ids)) or set(requested_ids) != set(by_id):
        raise ValueError("La reordenación debe incluir cada tarea exactamente una vez")

    requested_by_id = {entry["id"]: entry for entry in requested}
    for item in items:
        entry = requested_by_id[item.id]
        if item.parent_id is not None:
            parent_group = requested_by_id[item.parent_id]["group_name"]
            if entry["group_name"] != parent_group:
                raise ValueError("Una subtarea debe permanecer en el grupo de su padre")

    for item in items:
        item.group_name = requested_by_id[item.id]["group_name"]

    peers: dict[tuple[str | None, uuid.UUID | None], list[TaskItem]] = {}
    for item in items:
        peers.setdefault((item.group_name, item.parent_id), []).append(item)
    for peer_items in peers.values():
        ordered = sorted(
            peer_items,
            key=lambda item: (
                requested_by_id[item.id]["sort_order"],
                ensure_aware(item.created_at),
                item.id,
            ),
        )
        for sort_order, item in enumerate(ordered):
            item.sort_order = sort_order
    session.flush()
    return list_task_items(session)
