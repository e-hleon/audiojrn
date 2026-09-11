"""Backfill calendar metadata for already-proposed concrete task/reminder dates."""
from datetime import date, datetime

from alembic import op
import sqlalchemy as sa


revision = "20260908_0005"
down_revision = "20260907_0004"
branch_labels = None
depends_on = None


def _calendar_fields(value: str | None) -> tuple[str | None, bool]:
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


def upgrade():
    connection = op.get_bind()
    rows = connection.execute(sa.text("""
        SELECT id, due_text FROM proposed_actions
        WHERE kind IN ('task', 'reminder') AND start_at IS NULL
    """))
    for row in rows:
        start_at, all_day = _calendar_fields(row.due_text)
        if start_at is not None:
            connection.execute(sa.text("""
                UPDATE proposed_actions
                SET start_at = :start_at, all_day = :all_day
                WHERE id = :id
            """), {"id": row.id, "start_at": start_at, "all_day": all_day})


def downgrade():
    # These values may have been reviewed or edited by the user. Preserve them.
    pass
