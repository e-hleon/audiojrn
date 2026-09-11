"""add capture metadata and idempotency key

Revision ID: 20260906_0002
Revises: 20260905_0001
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20260906_0002"
down_revision = "20260905_0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("interactions", sa.Column("capture_mode", sa.String(length=16), server_default="manual", nullable=False))
    op.add_column("interactions", sa.Column("capture_session_id", postgresql.UUID(as_uuid=True), nullable=True))
    op.add_column("interactions", sa.Column("chunk_index", sa.Integer(), nullable=True))
    op.add_column("interactions", sa.Column("capture_chunk_id", postgresql.UUID(as_uuid=True), nullable=True))
    op.create_unique_constraint("uq_interactions_capture_chunk_id", "interactions", ["capture_chunk_id"])
    op.create_index("ix_interactions_capture_session_id", "interactions", ["capture_session_id"])


def downgrade() -> None:
    op.drop_index("ix_interactions_capture_session_id", table_name="interactions")
    op.drop_constraint("uq_interactions_capture_chunk_id", "interactions", type_="unique")
    op.drop_column("interactions", "capture_chunk_id")
    op.drop_column("interactions", "chunk_index")
    op.drop_column("interactions", "capture_session_id")
    op.drop_column("interactions", "capture_mode")
