"""Genera tablas Markdown y figuras desde los agregados JSON, sin editar cifras."""
from __future__ import annotations

import json
from pathlib import Path

from evaluation.common import ANALYSIS_CATEGORIES


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "evaluation/results"
FIGURES = ROOT / "evaluation/figures"


def append_llm_table(lines: list[str], title: str, path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    lines += ["", title, "", "| Categoría | TP | FP | FN | precision | recall | F1 |",
              "|---|---:|---:|---:|---:|---:|---:|"]
    for category in (*ANALYSIS_CATEGORIES, "micro"):
        item = data["categories"][category]
        lines.append(f"| {category} | {item['tp']} | {item['fp']} | {item['fn']} | {item['precision']:.3f} | {item['recall']:.3f} | {item['f1']:.3f} |")
    lines += ["", f"Respuestas válidas: {data['valid_responses']}/{data['calls']} ({data['valid_response_percent']:.1f} %).",
              f"Llamadas fallidas: {data['failed_calls']}. Latencia media/mediana/p95 (s): "
              f"{data['latency_seconds']['mean']}/{data['latency_seconds']['median']}/{data['latency_seconds']['p95']}.",
              f"Modelo efectivo: {', '.join(data.get('model_effective', [])) or 'no disponible'}. "
              f"Tokens entrada/salida: {data.get('input_tokens', 0)}/{data.get('output_tokens', 0)}.",
              "", "| Campo temporal | aciertos | casos emparejados | exactitud |", "|---|---:|---:|---:|"]
    for field, values in data.get("fields", {}).items():
        accuracy = "—" if values["accuracy"] is None else f"{values['accuracy']:.3f}"
        lines.append(f"| {field} | {values['correct']} | {values['total']} | {accuracy} |")


def report(results: Path = RESULTS, figures: Path = FIGURES, create_figures: bool = True) -> None:
    figures.mkdir(parents=True, exist_ok=True)
    asr = sorted(results.glob("asr/*.summary.json"))
    llm_path = results / "llm/summary.json"
    holdout_path = results / "llm-holdout/summary.json"
    lines = ["# Resultados agregados", "", "## ASR", "",
             "| Modelo | dispositivo | compute type | muestras | fallos | WER | CER | latencia mediana (s) | p95 (s) | RTF | carga (s) |",
             "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for path in asr:
        data = json.loads(path.read_text(encoding="utf-8"))
        lines.append(f"| {data['model']} | {data.get('device', '')} | {data.get('compute_type', '')} | {data['samples']} | {data['failures']} | {data['corpus_wer']} | {data['corpus_cer']} | {data['latency']['median']} | {data['latency']['p95']} | {data['rtf']['mean']} | {data['load_seconds']} |")
    if llm_path.exists():
        append_llm_table(lines, "## Extracción LLM: conjunto principal", llm_path)
    if holdout_path.exists():
        append_llm_table(lines, "## Extracción LLM: holdout", holdout_path)
    (results / "summary-tables.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if not create_figures or (not asr and not llm_path.exists() and not holdout_path.exists()):
        return
    import matplotlib.pyplot as plt
    if llm_path.exists():
        data = json.loads(llm_path.read_text(encoding="utf-8")); categories = list(ANALYSIS_CATEGORIES)
        figure, axis = plt.subplots(figsize=(6.5, 3.8)); width = .25; positions = list(range(len(categories)))
        for offset, metric in enumerate(("precision", "recall", "f1")):
            axis.bar([x + (offset - 1) * width for x in positions], [data["categories"][cat][metric] for cat in categories], width, label=metric)
        axis.set_xticks(positions, categories); axis.set_ylim(0, 1); axis.set_ylabel("Puntuación"); axis.set_title("Extracción estructurada"); axis.legend(); figure.tight_layout(); figure.savefig(figures / "llm-prf.png", dpi=180); figure.savefig(figures / "llm-prf.svg"); plt.close(figure)


if __name__ == "__main__":
    report()
