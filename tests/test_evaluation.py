import json
from pathlib import Path

from evaluation.common import corpus_wer, evidence_matches, match_items, normalize_text, prf, real_time_factor
from evaluation.llm.run import run


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


def test_fixture_is_readable_and_report_supports_small_data(tmp_path, monkeypatch):
    fixture = Path("evaluation/fixtures/llm_cases.jsonl")
    cases = [json.loads(line) for line in fixture.read_text(encoding="utf-8").splitlines()]
    assert len(cases) == 36 and all("expected" in case for case in cases)
    output = run(fixture, tmp_path, None, 1, False)
    assert output.exists() and (tmp_path / "summary.json").exists()


def test_rtf_formula():
    assert real_time_factor(2.0, 10.0) == 0.2
