"""Caracteriza el analizador real de OpenAI mediante casos sintéticos anotados."""
from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from app.analysis import OpenAIAnalyzer
from evaluation.common import (
    ANALYSIS_CATEGORIES,
    append_jsonl,
    field_accuracy,
    match_items,
    matched_item_pairs,
    prf,
    read_jsonl,
    summarize_numbers,
)


FIELD_CHECKS = {"tasks": ("due_at",), "events": ("start_at", "all_day")}


def expected_categories(expected: dict) -> dict[str, list[dict]]:
    """Normaliza fixtures actuales y conserva la lectura de los históricos."""
    known = set(ANALYSIS_CATEGORIES) | {"decisions", "reminders"}
    unknown = set(expected) - known
    if unknown:
        raise ValueError(f"Categorías de análisis no admitidas: {sorted(unknown)}")
    return {
        "highlights": [*expected.get("highlights", []), *expected.get("decisions", [])],
        "tasks": [*expected.get("tasks", []), *expected.get("reminders", [])],
        "events": expected.get("events", []),
    }


def validate_case(case: dict) -> None:
    if not {"id", "text", "expected"} <= set(case):
        raise ValueError("Cada caso requiere id, text y expected")
    if set(case["expected"]) != set(ANALYSIS_CATEGORIES):
        raise ValueError(f"{case['id']}: el fixture debe usar {ANALYSIS_CATEGORIES}")
    has_reference = bool(case.get("reference_datetime"))
    has_timezone = bool(case.get("timezone"))
    if has_reference != has_timezone:
        raise ValueError(f"{case['id']}: reference_datetime y timezone deben aparecer juntos")


def _git_commit() -> str | None:
    if commit := os.getenv("GIT_COMMIT"):
        return commit
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True,
            check=True, timeout=2,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None


def summarize(cases: list[dict], records: list[dict], model: str) -> dict:
    """Agrega predicciones ya obtenidas sin realizar llamadas externas."""
    by_id = {case["id"]: case for case in cases}
    latest = {row["id"]: row for row in records if row.get("id") in by_id}
    active_records = list(latest.values())
    valid = [row for row in active_records if row.get("status") == "ok"]
    counts = {category: {"tp": 0, "fp": 0, "fn": 0} for category in ANALYSIS_CATEGORIES}
    pairs_by_field = {
        (category, field): []
        for category, fields in FIELD_CHECKS.items()
        for field in fields
    }
    # Una llamada inválida no aporta predicciones, pero sus elementos esperados sí
    # deben contribuir a FN. De otro modo la tasa de respuestas válidas alteraría
    # artificialmente el recall de la extracción.
    for case in cases:
        row = latest.get(case["id"], {})
        expected = expected_categories(case["expected"])
        prediction = row.get("prediction", {}) if row.get("status") == "ok" else {}
        for category in ANALYSIS_CATEGORIES:
            wanted, actual = expected[category], prediction.get(category, [])
            matched = match_items(wanted, actual)
            for name in counts[category]:
                counts[category][name] += matched[name]
            pairs = matched_item_pairs(wanted, actual)
            for field in FIELD_CHECKS.get(category, ()):
                pairs_by_field[(category, field)].extend(pairs)
    totals = {
        name: sum(values[name] for values in counts.values())
        for name in ("tp", "fp", "fn")
    }
    errors: dict[str, int] = {}
    for row in active_records:
        if row.get("status") != "ok":
            reason = row.get("error", "unknown")
            errors[reason] = errors.get(reason, 0) + 1
    latencies = [float(row["latency_seconds"]) for row in valid]
    effective_models = sorted({row.get("model_effective") for row in valid if row.get("model_effective")})
    return {
        "fixture_cases": len(cases),
        "calls": len(active_records),
        "valid_responses": len(valid),
        "valid_response_percent": 100 * len(valid) / len(active_records) if active_records else 0.0,
        "failed_calls": len(active_records) - len(valid),
        "model": model,
        "model_effective": effective_models,
        "hardware": platform.platform(),
        "git_commit": _git_commit(),
        "categories": {**{category: prf(**value) for category, value in counts.items()},
                       "micro": prf(**totals)},
        "fields": {f"{category}.{field}": field_accuracy(pairs, field)
                   for (category, field), pairs in pairs_by_field.items()},
        "latency_seconds": summarize_numbers(latencies),
        "input_tokens": sum(int(row.get("input_tokens") or 0) for row in valid),
        "output_tokens": sum(int(row.get("output_tokens") or 0) for row in valid),
        "errors": errors,
    }


def run(fixtures: Path, output_dir: Path, model: str | None, max_cases: int, resume: bool) -> Path:
    cases = read_jsonl(fixtures)[:max_cases]
    for case in cases:
        validate_case(case)
    if model:
        os.environ["OPENAI_MODEL"] = model
    analyzer = OpenAIAnalyzer()
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / "predictions.jsonl"
    if output.exists() and not resume:
        raise FileExistsError(f"Ya existe {output}; usa --resume o un directorio nuevo")
    existing = {row["id"] for row in read_jsonl(output)} if resume else set()
    for case in cases:
        if case["id"] in existing:
            continue
        start = time.perf_counter()
        record = {"timestamp": datetime.now(timezone.utc).isoformat(), "id": case["id"],
                  "model": analyzer.model}
        try:
            result = analyzer.analyze(
                case["text"], case.get("reference_datetime"), case.get("timezone")
            )
            record.update({"status": "ok", "latency_seconds": time.perf_counter() - start,
                           "prediction": result.model_dump(mode="json"),
                           **analyzer.last_call_metadata})
        except Exception as exc:
            record.update({"status": "error", "latency_seconds": time.perf_counter() - start,
                           "error": type(exc).__name__, "error_message": str(exc)[:300]})
        append_jsonl(output, record)
    summary = summarize(cases, read_jsonl(output), analyzer.model)
    (output_dir / "summary.json").write_text(
        json.dumps({"fixture": str(fixtures), **summary}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", type=Path, default=Path("evaluation/fixtures/llm_cases_current.jsonl"))
    parser.add_argument("--output-dir", type=Path, default=Path("evaluation/results/llm"))
    parser.add_argument("--model", default=None)
    parser.add_argument("--max-cases", type=int, default=40)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    run(args.fixtures, args.output_dir, args.model, args.max_cases, args.resume)
