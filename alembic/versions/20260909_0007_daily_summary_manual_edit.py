"""Track manual edits to a generated daily summary explicitly."""
from alembic import op
import sqlalchemy as sa

revision = "20260909_0007"
down_revision = "20260908_0006"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column(
        "daily_summaries",
        sa.Column("manually_edited", sa.Boolean(), nullable=False, server_default=sa.false()),
    )


def downgrade():
    op.drop_column("daily_summaries", "manually_edited")
