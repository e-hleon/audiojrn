"""Migration rehearsal in a separate schema of the disposable test database."""
import json
import os
import subprocess
import uuid

from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url


def test_upgrade_0002_with_historical_rows_preserves_data_and_backfills_actions():
    url = os.environ.get("DATABASE_URL")
    if not url:
        import pytest
        pytest.skip("Requires an explicitly configured disposable PostgreSQL")
    schema = "migration_audit_" + uuid.uuid4().hex
    engine = create_engine(url)
    with engine.begin() as connection:
        connection.execute(text(f'CREATE SCHEMA "{schema}"'))
    isolated_url = make_url(url).update_query_dict({"options": f"-csearch_path={schema}"})
    isolated = create_engine(isolated_url)
    env = {**os.environ, "DATABASE_URL": isolated_url.render_as_string(hide_password=False)}
    def migrate(target, operation="upgrade"):
        subprocess.run(["alembic", operation, target], env=env, check=True, capture_output=True)
    try:
        migrate("20260906_0002")
        interaction_id = uuid.uuid4()
        analysis = {"summary": "Synthetic", "topics": [], "decisions": [],
                    "tasks": [{"text": "Synthetic task", "evidence": "Synthetic", "assignee": None, "due_date": "2026-09-10"}], "reminders": []}
        with isolated.begin() as connection:
            connection.execute(text("""INSERT INTO interactions
                (id, recorded_at, transcription, transcription_model, analysis)
                VALUES (:id, '2026-09-07T17:00:00+02:00', 'Synthetic', 'fake', CAST(:analysis AS jsonb))"""),
                {"id": interaction_id, "analysis": json.dumps(analysis)})
            connection.execute(text("""INSERT INTO daily_summaries
                (id, day, timezone, result, source_fingerprint, generated_at)
                VALUES (:id, '2026-09-07', 'UTC', CAST(:result AS jsonb), :fingerprint, now())"""),
                {"id": uuid.uuid4(), "result": json.dumps({"summary": "Synthetic", "highlights": []}), "fingerprint": "a" * 64})
        migrate("head")
        migrate("head")
        with isolated.begin() as connection:
            assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20260909_0007"
            assert connection.scalar(text("SELECT manually_edited FROM daily_summaries LIMIT 1")) is False
            assert connection.scalar(text("SELECT count(*) FROM interactions")) == 1
            assert connection.scalar(text("SELECT capture_mode FROM interactions")) == "manual"
            assert connection.scalar(text("SELECT count(*) FROM proposed_actions")) == 1
            assert connection.scalar(text("SELECT start_at FROM proposed_actions")) == "2026-09-10"
            assert connection.scalar(text("SELECT all_day FROM proposed_actions")) is True
            connection.execute(text("UPDATE proposed_actions SET title='Reviewed', status='dismissed'"))
        migrate("20260907_0003", "downgrade")
        migrate("head")
        with isolated.connect() as connection:
            assert connection.scalar(text("SELECT title FROM proposed_actions")) == "Reviewed"
            assert connection.scalar(text("SELECT status FROM proposed_actions")) == "dismissed"
    finally:
        isolated.dispose()
        with engine.begin() as connection:
            connection.execute(text(f'DROP SCHEMA "{schema}" CASCADE'))
        engine.dispose()
