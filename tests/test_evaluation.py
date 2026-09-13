import json
from pathlib import Path

import pytest

from evaluation.common import (
    ANALYSIS_CATEGORIES, corpus_wer, evidence_matches, field_accuracy, match_items,
    matched_item_pairs, normalize_text, prf, real_time_factor,
)
from evaluation.llm.run import expected_categories, run, summarize, validate_case
from evaluation.report import report


def test_normalization_and_wer_are_transparent():
    assert normalize_text("  ÁRBOL,  rápido. ") == "árbol rápido"
    assert corpus_wer(["Hola, mundo!"] , ["hola mundo"]) == 0


def test_evidence_matching_is_literal_light_normalization():
    assert evidence_matches(" Decidimos   salir. ", "decidimos salir.")
    assert evidence_matches("salir", "Decidimos salir.")
    assert not evidence_matches("salir", "entrar")


def test_counts_and_prf():
    assert match_items([{"evidence": "uno"}], [{"evidence": "uno"}, {"evidence": "dos"}]) == {"tp": 1, "fp": 1, "fn": 0}
    assert prf(2, 1, 1) == {"tp": 2, "fp": 1, "fn": 1, "precision": 2 / 3, "recall": 2 / 3, "f1": 2 / 3}


def test_evaluation_reports_the_current_analysis_categories():
    assert expected_categories({"highlights": [{"evidence": "h"}], "tasks": [], "events": [{"evidence": "e"}]}) == {
        "highlights": [{"evidence": "h"}], "tasks": [], "events": [{"evidence": "e"}],
    }
    assert expected_categories({"decisions": [{"evidence": "d"}], "tasks": [], "reminders": [{"evidence": "r"}]}) == {
        "highlights": [{"evidence": "d"}], "tasks": [{"evidence": "r"}], "events": [],
    }
    assert expected_categories({
        "highlights": [{"evidence": "h"}], "decisions": [{"evidence": "d"}],
        "tasks": [{"evidence": "t"}], "reminders": [{"evidence": "r"}],
        "events": [{"evidence": "e"}],
    }) == {
        "highlights": [{"evidence": "h"}, {"evidence": "d"}],
        "tasks": [{"evidence": "t"}, {"evidence": "r"}],
        "events": [{"evidence": "e"}],
    }


def test_fixture_is_readable_and_report_supports_small_data(tmp_path, monkeypatch):
    fixture = Path("evaluation/fixtures/llm_cases_current.jsonl")
    cases = [json.loads(line) for line in fixture.read_text(encoding="utf-8").splitlines()]
    assert len(cases) == 18 and all("expected" in case for case in cases)
    output = run(fixture, tmp_path, None, 1, False)
    assert output.exists() and (tmp_path / "summary.json").exists()


def test_rtf_formula():
    assert real_time_factor(2.0, 10.0) == 0.2


def test_field_accuracy_uses_items_matched_by_evidence():
    expected = [{"evidence": "tarea", "due_at": "2026-09-15"}]
    predicted = [{"evidence": "tarea", "due_at": "2026-09-15"}]
    assert field_accuracy(matched_item_pairs(expected, predicted), "due_at") == {
        "correct": 1, "total": 1, "accuracy": 1.0,
    }


@pytest.mark.parametrize("name,count", [("llm_cases_current.jsonl", 18), ("llm_holdout_current.jsonl", 6)])
def test_current_fixtures_use_the_current_contract_and_fixed_time_context(name, count):
    fixture = Path("evaluation/fixtures") / name
    cases = [json.loads(line) for line in fixture.read_text(encoding="utf-8").splitlines()]
    assert len(cases) == count
    for case in cases:
        validate_case(case)
        assert set(case["expected"]) == set(ANALYSIS_CATEGORIES)
        assert case["reference_datetime"] and case["timezone"] == "Europe/Madrid"


def test_summary_and_report_share_categories_and_temporal_fields(tmp_path):
    cases = [{"id": "one", "text": "x", "expected": {
        "highlights": [], "tasks": [{"evidence": "t", "due_at": "2026-09-15"}],
        "events": [{"evidence": "e", "start_at": "2026-09-16", "all_day": True}],
    }}]
    records = [{"id": "one", "status": "ok", "latency_seconds": 0.5, "prediction": {
        "highlights": [], "tasks": [{"evidence": "t", "due_at": "2026-09-15"}],
        "events": [{"evidence": "e", "start_at": "2026-09-16", "all_day": True}],
    }}]
    summary = summarize(cases, records, "test-model")
    assert summary["categories"]["micro"]["f1"] == 1.0
    assert summary["fields"]["tasks.due_at"]["accuracy"] == 1.0
    llm_dir = tmp_path / "llm"
    llm_dir.mkdir()
    (llm_dir / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
    report(tmp_path, tmp_path / "figures", create_figures=False)
    table = (tmp_path / "summary-tables.md").read_text(encoding="utf-8")
    assert all(category in table for category in ANALYSIS_CATEGORIES)
    assert "decisions" not in table and "reminders" not in table


def test_invalid_llm_response_counts_expected_items_as_false_negatives():
    cases = [{"id": "one", "text": "x", "expected": {
        "highlights": [], "tasks": [],
        "events": [{"evidence": "reunión", "start_at": "2026-09-16", "all_day": True}],
    }}]
    records = [{"id": "one", "status": "error", "error": "AnalysisInvalidResponse",
                "latency_seconds": 0.5}]
    summary = summarize(cases, records, "test-model")
    assert summary["categories"]["events"] == {
        "tp": 0, "fp": 0, "fn": 1, "precision": 0.0, "recall": 0.0, "f1": 0.0,
    }
