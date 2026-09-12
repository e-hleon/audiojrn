"""Migration rehearsal from an empty schema of the disposable test database."""
import os
import subprocess
import uuid

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import make_url


def test_upgrade_head_creates_current_schema_from_empty_database():
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
        migrate("head")
        migrate("head")
        inspector = inspect(isolated)
        assert {"interactions", "daily_summaries", "continuous_sessions", "proposed_actions", "task_items"}.issubset(
            inspector.get_table_names()
        )
        assert {column["name"] for column in inspector.get_columns("interactions")} == {
            "id", "capture_mode", "capture_session_id", "chunk_index", "capture_chunk_id",
            "recorded_at", "created_at", "updated_at", "transcription", "language",
            "transcription_model", "transcription_device", "transcription_compute_type",
            "analysis", "analysis_model",
        }
        assert {column["name"] for column in inspector.get_columns("daily_summaries")} == {
            "id", "day", "timezone", "result", "source_fingerprint", "llm_model",
            "generated_at", "manually_edited", "created_at", "updated_at",
        }
        assert {column["name"] for column in inspector.get_columns("continuous_sessions")} == {
            "id", "started_at", "last_chunk_index", "status", "analysis", "analysis_model",
            "source_fingerprint", "finalized_at", "created_at", "updated_at",
        }
        assert {column["name"] for column in inspector.get_columns("proposed_actions")} == {
            "id", "source_key", "kind", "status", "title", "due_text", "start_at", "end_at",
            "all_day", "location", "notes", "evidence", "source_interaction_id",
            "source_session_id", "created_at", "updated_at",
        }
        assert {column["name"] for column in inspector.get_columns("task_items")} == {
            "id", "text", "completed", "group_name", "parent_id", "sort_order", "due_at",
            "all_day", "source_action_id", "created_at", "updated_at",
        }
        with isolated.begin() as connection:
            assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20260912_0001"
        assert {"ix_interactions_recorded_at", "ix_interactions_capture_session_id"}.issubset({
            index["name"] for index in inspector.get_indexes("interactions")
        })
        assert {"ix_task_items_group_order"}.issubset({
            index["name"] for index in inspector.get_indexes("task_items")
        })
        assert ("capture_chunk_id",) in {
            tuple(constraint["column_names"]) for constraint in inspector.get_unique_constraints("interactions")
        }
        assert ("day",) in {
            tuple(constraint["column_names"]) for constraint in inspector.get_unique_constraints("daily_summaries")
        }
        assert ("source_key",) in {
            tuple(constraint["column_names"]) for constraint in inspector.get_unique_constraints("proposed_actions")
        }
        assert ("source_action_id",) in {
            tuple(constraint["column_names"]) for constraint in inspector.get_unique_constraints("task_items")
        }
        assert {
            (tuple(constraint["constrained_columns"]), constraint["referred_table"])
            for constraint in inspector.get_foreign_keys("task_items")
        } == {
            (("parent_id",), "task_items"),
            (("source_action_id",), "proposed_actions"),
        }
        assert "parent_id IS NULL OR parent_id <> id" in {
            constraint["sqltext"] for constraint in inspector.get_check_constraints("task_items")
        }
    finally:
        isolated.dispose()
        with engine.begin() as connection:
            connection.execute(text(f'DROP SCHEMA "{schema}" CASCADE'))
        engine.dispose()
