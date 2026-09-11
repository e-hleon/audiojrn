"""Evalúa el analizador real de OpenAI sobre fixtures sintéticos."""
from __future__ import annotations

import argparse
import json
import os
import platform
import time
from datetime import datetime, timezone
from pathlib import Path

from app.analysis import OpenAIAnalyzer
from evaluation.common import append_jsonl, match_items, prf, read_jsonl


def run(fixtures: Path, output_dir: Path, model: str | None, max_cases: int, resume: bool) -> Path:
    cases = read_jsonl(fixtures)[:max_cases]
    if model:
        os.environ["OPENAI_MODEL"] = model
    analyzer = OpenAIAnalyzer()
    output = output_dir / "predictions.jsonl"
    existing = {row["id"] for row in read_jsonl(output)} if resume else set()
    for case in cases:
        if case["id"] in existing:
            continue
        start = time.perf_counter()
        record = {"timestamp": datetime.now(timezone.utc).isoformat(), "id": case["id"], "model_requested": analyzer.model}
        try:
            result = analyzer.analyze(case["text"])
            record.update({"status": "ok", "latency_seconds": time.perf_counter() - start,
                           "prediction": result.model_dump(mode="json"), "evidence_valid": True,
                           **analyzer.last_call_metadata})
        except Exception as exc:
            record.update({"status": "error", "latency_seconds": time.perf_counter() - start,
                           "error": type(exc).__name__, "error_message": str(exc)[:300]})
        append_jsonl(output, record)
    records = read_jsonl(output)
    by_id = {case["id"]: case for case in cases}
    categories = {category: {"tp": 0, "fp": 0, "fn": 0} for category in ("decisions", "tasks", "reminders")}
    valid = [row for row in records if row.get("status") == "ok"]
    for row in valid:
        expected = by_id.get(row["id"], {}).get("expected", {})
        prediction = row["prediction"]
        for category in categories:
            matched = match_items(expected.get(category, []), prediction.get(category, []))
            for key in categories[category]:
                categories[category][key] += matched[key]
    metrics = {category: {**values, **prf(values["tp"], values["fp"], values["fn"])}
               for category, values in categories.items()}
    all_counts = {key: sum(values[key] for values in categories.values()) for key in ("tp", "fp", "fn")}
    latencies = sorted(r["latency_seconds"] for r in valid)
    rejection_reasons = {}
    for row in records:
        if row.get("status") != "ok":
            reason = row.get("error_message", "unknown").rsplit("(", 1)[-1].rstrip(")")
            rejection_reasons[reason] = rejection_reasons.get(reason, 0) + 1
    effective_models = sorted({r.get("model_effective") for r in valid if r.get("model_effective")})
    summary = {"fixture": str(fixtures), "cases": len(cases), "valid_schema_percent": 100 * len(valid) / len(cases) if cases else 0,
               "failed_calls": len(records) - len(valid), "model_requested": analyzer.model,
               "rejection_reasons": rejection_reasons,
               "model_effective": effective_models,
               "hardware": platform.platform(), "git_commit": os.popen("git rev-parse HEAD").read().strip(),
               "categories": {**metrics, "micro": {**all_counts, **prf(**all_counts)}},
               "latency_seconds": {"mean": sum(latencies) / len(latencies) if latencies else None,
                                   "median": latencies[len(latencies)//2] if latencies else None,
                                   "p95": latencies[int((len(latencies)-1)*.95)] if latencies else None},
               "input_tokens": sum(r.get("input_tokens") or 0 for r in valid),
               "output_tokens": sum(r.get("output_tokens") or 0 for r in valid)}
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", type=Path, default=Path("evaluation/fixtures/llm_cases.jsonl"))
    parser.add_argument("--output-dir", type=Path, default=Path("evaluation/results/llm"))
    parser.add_argument("--model", default=None)
    parser.add_argument("--max-cases", type=int, default=40)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    run(args.fixtures, args.output_dir, args.model, args.max_cases, args.resume)
