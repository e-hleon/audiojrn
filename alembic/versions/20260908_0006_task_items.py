"""Add user-owned tasks; proposals remain a separate review inbox."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20260908_0006"
down_revision = "20260908_0005"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "task_items",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("completed", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("group_name", sa.String(length=160)),
        sa.Column("parent_id", postgresql.UUID(as_uuid=True)),
        sa.Column("sort_order", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("due_at", sa.DateTime(timezone=True)),
        sa.Column("all_day", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("source_action_id", postgresql.UUID(as_uuid=True), unique=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("now()")),
        sa.ForeignKeyConstraint(["parent_id"], ["task_items.id"], name="fk_task_items_parent"),
        sa.ForeignKeyConstraint(["source_action_id"], ["proposed_actions.id"], name="fk_task_items_source_action"),
        sa.CheckConstraint("parent_id IS NULL OR parent_id <> id", name="ck_task_items_not_self_parent"),
    )
    op.create_index("ix_task_items_group_order", "task_items", ["group_name", "sort_order", "created_at"])


def downgrade():
    op.drop_index("ix_task_items_group_order", table_name="task_items")
    op.drop_table("task_items")
