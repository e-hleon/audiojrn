"""Modelos persistentes mínimos del diario."""
import uuid
from datetime import date, datetime

from sqlalchemy import Boolean, CheckConstraint, Date, DateTime, ForeignKey, Index, Integer, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base


class Interaction(Base):
    __tablename__ = "interactions"
    __table_args__ = (
        Index("ix_interactions_recorded_at", "recorded_at"),
        Index("ix_interactions_capture_session_id", "capture_session_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    capture_mode: Mapped[str] = mapped_column(String(16), nullable=False, server_default="manual")
    capture_session_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    chunk_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    capture_chunk_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True, unique=True)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now())
    transcription: Mapped[str] = mapped_column(Text, nullable=False)
    language: Mapped[str | None] = mapped_column(String(32))
    transcription_model: Mapped[str] = mapped_column(String(128), nullable=False)
    transcription_device: Mapped[str | None] = mapped_column(String(32))
    transcription_compute_type: Mapped[str | None] = mapped_column(String(64))
    analysis: Mapped[dict] = mapped_column(JSONB, nullable=False)
    analysis_model: Mapped[str | None] = mapped_column(String(128))


class DailySummary(Base):
    __tablename__ = "daily_summaries"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    day: Mapped[date] = mapped_column(Date, nullable=False, unique=True)
    timezone: Mapped[str] = mapped_column(String(64), nullable=False)
    result: Mapped[dict] = mapped_column(JSONB, nullable=False)
    source_fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    llm_model: Mapped[str | None] = mapped_column(String(128))
    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    manually_edited: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now())


class ContinuousSession(Base):
    __tablename__ = "continuous_sessions"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    last_chunk_index: Mapped[int | None] = mapped_column(Integer, nullable=True)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default="open")
    analysis: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    analysis_model: Mapped[str | None] = mapped_column(String(128), nullable=True)
    source_fingerprint: Mapped[str | None] = mapped_column(String(64), nullable=True)
    finalized_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now())


class ProposedAction(Base):
    __tablename__ = "proposed_actions"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    source_key: Mapped[str] = mapped_column(String(160), nullable=False, unique=True)
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default="pending")
    title: Mapped[str] = mapped_column(Text, nullable=False)
    due_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    start_at: Mapped[str | None] = mapped_column(Text, nullable=True)
    end_at: Mapped[str | None] = mapped_column(Text, nullable=True)
    all_day: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    location: Mapped[str | None] = mapped_column(Text, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    evidence: Mapped[str] = mapped_column(Text, nullable=False)
    source_interaction_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    source_session_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now())


class TaskItem(Base):
    __tablename__ = "task_items"
    __table_args__ = (
        CheckConstraint("parent_id IS NULL OR parent_id <> id", name="ck_task_items_not_self_parent"),
        Index("ix_task_items_group_order", "group_name", "sort_order", "created_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    completed: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    group_name: Mapped[str | None] = mapped_column(String(160), nullable=True)
    parent_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("task_items.id", name="fk_task_items_parent"),
        nullable=True,
    )
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    due_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    all_day: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    source_action_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("proposed_actions.id", name="fk_task_items_source_action"),
        nullable=True,
        unique=True,
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now())
