"""Backfill proposals for historical Manual/Smart analyses, without calling LLM."""
from alembic import op
import sqlalchemy as sa
import uuid

revision = "20260907_0004"
down_revision = "20260907_0003"
branch_labels = None
depends_on = None


def upgrade():
    connection = op.get_bind()
    rows = connection.execute(sa.text(
        "SELECT id, analysis FROM interactions WHERE capture_mode IN ('manual', 'smart')"
    ))
    for row in rows:
        for category, kind, due in (("tasks", "task", "due_date"), ("reminders", "reminder", "when")):
            for index, item in enumerate(row.analysis.get(category, [])):
                connection.execute(sa.text("""
                    INSERT INTO proposed_actions
                        (id, source_key, kind, title, due_text, evidence, source_interaction_id)
                    VALUES (:id, :key, :kind, :title, :due, :evidence, :source)
                    ON CONFLICT (source_key) DO NOTHING
                """), dict(id=uuid.uuid4(), key=f"interaction:{row.id}:{kind}:{index}",
                           kind=kind, title=item["text"], due=item.get(due),
                           evidence=item["evidence"], source=row.id))


def downgrade():
    # Proposals may already have been edited by the user. Preserve them.
    pass
