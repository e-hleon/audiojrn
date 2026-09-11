"""create persistence foundation

Revision ID: 20260905_0001
Revises:
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20260905_0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "interactions",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("transcription", sa.Text(), nullable=False),
        sa.Column("language", sa.String(length=32), nullable=True),
        sa.Column("transcription_model", sa.String(length=128), nullable=False),
        sa.Column("transcription_device", sa.String(length=32), nullable=True),
        sa.Column("transcription_compute_type", sa.String(length=64), nullable=True),
        sa.Column("analysis", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("analysis_model", sa.String(length=128), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_interactions_recorded_at", "interactions", ["recorded_at"])
    op.create_table(
        "daily_summaries",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("timezone", sa.String(length=64), nullable=False),
        sa.Column("result", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("source_fingerprint", sa.String(length=64), nullable=False),
        sa.Column("llm_model", sa.String(length=128), nullable=True),
        sa.Column("generated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("day"),
    )


def downgrade() -> None:
    op.drop_table("daily_summaries")
    op.drop_index("ix_interactions_recorded_at", table_name="interactions")
    op.drop_table("interactions")
