"""Funciones pequeñas y deterministas compartidas por los experimentos."""
from __future__ import annotations

import json
import math
import re
import unicodedata
from pathlib import Path
from statistics import mean, median

try:
    from jiwer import cer as _jiwer_cer, wer as _jiwer_wer
except ImportError:  # permite ejecutar las pruebas de algoritmos sin dependencias opcionales
    _jiwer_cer = _jiwer_wer = None


def _distance(left: list[str], right: list[str]) -> int:
    previous = list(range(len(right) + 1))
    for i, first in enumerate(left, 1):
        current = [i]
        for j, second in enumerate(right, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (first != second)))
        previous = current
    return previous[-1]


def normalize_text(text: str) -> str:
    """Lowercase, normaliza Unicode, quita puntuación y colapsa espacios."""
    text = unicodedata.normalize("NFC", text).lower()
    text = "".join(char for char in text if not unicodedata.category(char).startswith("P"))
    return " ".join(text.split())


def corpus_wer(references: list[str], hypotheses: list[str]) -> float:
    refs, hyps = [normalize_text(x) for x in references], [normalize_text(x) for x in hypotheses]
    if _jiwer_wer:
        return _jiwer_wer(refs, hyps)
    return _distance(" ".join(refs).split(), " ".join(hyps).split()) / max(len(" ".join(refs).split()), 1)


def corpus_cer(references: list[str], hypotheses: list[str]) -> float:
    refs, hyps = [normalize_text(x) for x in references], [normalize_text(x) for x in hypotheses]
    if _jiwer_cer:
        return _jiwer_cer(refs, hyps)
    return _distance(list("".join(refs)), list("".join(hyps))) / max(len("".join(refs)), 1)


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * p / 100
    lower, upper = math.floor(index), math.ceil(index)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (index - lower)


def summarize_numbers(values: list[float]) -> dict[str, float | None]:
    return {"mean": mean(values) if values else None, "median": median(values) if values else None,
            "p95": percentile(values, 95)}


def real_time_factor(inference_seconds: float, audio_duration_seconds: float) -> float:
    if audio_duration_seconds <= 0:
        raise ValueError("La duración debe ser positiva")
    return inference_seconds / audio_duration_seconds


def append_jsonl(path: Path, record: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def prf(tp: int, fp: int, fn: int) -> dict[str, float | int]:
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {"tp": tp, "fp": fp, "fn": fn, "precision": precision,
            "recall": recall, "f1": 2 * precision * recall / (precision + recall)
            if precision + recall else 0.0}


def evidence_key(value: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", value).strip().lower())


def evidence_matches(predicted: str, expected: str) -> bool:
    left, right = evidence_key(predicted), evidence_key(expected)
    return bool(left and right and (left == right or left in right or right in left))


def match_items(expected: list[dict], predicted: list[dict]) -> dict[str, int]:
    """Matching 1:1 greedy por evidencia, sin juez semántico."""
    used: set[int] = set()
    tp = 0
    for wanted in expected:
        for index, actual in enumerate(predicted):
            if index not in used and evidence_matches(actual.get("evidence", ""), wanted.get("evidence", "")):
                used.add(index)
                tp += 1
                break
    return {"tp": tp, "fp": len(predicted) - tp, "fn": len(expected) - tp}
