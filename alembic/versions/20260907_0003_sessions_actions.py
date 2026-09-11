"""Add continuous session results and human-reviewed actions."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "20260907_0003"
down_revision = "20260906_0002"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "continuous_sessions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_chunk_index", sa.Integer(), nullable=True),
        sa.Column("status", sa.String(length=16), server_default="open", nullable=False),
        sa.Column("analysis", postgresql.JSONB(), nullable=True),
        sa.Column("analysis_model", sa.String(length=128), nullable=True),
        sa.Column("source_fingerprint", sa.String(length=64), nullable=True),
        sa.Column("finalized_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_table(
        "proposed_actions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("source_key", sa.String(length=160), nullable=False),
        sa.Column("kind", sa.String(length=16), nullable=False),
        sa.Column("status", sa.String(length=16), server_default="pending", nullable=False),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("due_text", sa.Text(), nullable=True),
        sa.Column("start_at", sa.Text(), nullable=True),
        sa.Column("end_at", sa.Text(), nullable=True),
        sa.Column("all_day", sa.Boolean(), server_default=sa.false(), nullable=False),
        sa.Column("location", sa.Text(), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("evidence", sa.Text(), nullable=False),
        sa.Column("source_interaction_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("source_session_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("source_key"),
    )


def downgrade():
    op.drop_table("proposed_actions")
    op.drop_table("continuous_sessions")
