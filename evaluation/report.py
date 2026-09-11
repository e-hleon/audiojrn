"""Genera tablas Markdown y figuras desde los agregados JSON, sin editar cifras."""
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "evaluation/results"
FIGURES = ROOT / "evaluation/figures"


def report() -> None:
    FIGURES.mkdir(parents=True, exist_ok=True)
    asr = sorted(RESULTS.glob("asr/*.summary.json"))
    llm_path = RESULTS / "llm/summary.json"
    lines = ["# Resultados agregados", "", "## ASR", "", "| Modelo | muestras | WER | CER | latencia mediana (s) | p95 (s) | RTF | load time (s) |", "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for path in asr:
        data = json.loads(path.read_text(encoding="utf-8"))
        lines.append(f"| {data['model']} | {data['samples']} | {data['corpus_wer']} | {data['corpus_cer']} | {data['latency']['median']} | {data['latency']['p95']} | {data['rtf']['mean']} | {data['load_seconds']} |")
    lines += ["", "## Extracción LLM", "", "| Categoría | TP | FP | FN | precision | recall | F1 |", "|---|---:|---:|---:|---:|---:|---:|"]
    if llm_path.exists():
        data = json.loads(llm_path.read_text(encoding="utf-8"))
        for category in ("decisions", "tasks", "reminders"):
            item = data["categories"][category]
            lines.append(f"| {category} | {item['tp']} | {item['fp']} | {item['fn']} | {item['precision']:.3f} | {item['recall']:.3f} | {item['f1']:.3f} |")
    (RESULTS / "summary-tables.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if not asr and not llm_path.exists():
        return
    import matplotlib.pyplot as plt
    if asr:
        labels, wers, rtfs = [], [], []
        for path in asr:
            data = json.loads(path.read_text(encoding="utf-8")); labels.append(data["model"])
            wers.append(data["corpus_wer"]); rtfs.append(data["rtf"]["mean"])
        for name, values, ylabel in (("asr-wer", wers, "Corpus WER"), ("asr-rtf", rtfs, "RTF (s/s)")):
            figure, axis = plt.subplots(figsize=(5.2, 3.4)); axis.bar(labels, values); axis.set_ylabel(ylabel); axis.set_xlabel("Modelo"); axis.set_title(ylabel + " por modelo"); figure.tight_layout(); figure.savefig(FIGURES / (name + ".png"), dpi=180); figure.savefig(FIGURES / (name + ".svg")); plt.close(figure)
    if llm_path.exists():
        data = json.loads(llm_path.read_text(encoding="utf-8")); categories = ["decisions", "tasks", "reminders"]
        figure, axis = plt.subplots(figsize=(6.5, 3.8)); width = .25; positions = list(range(len(categories)))
        for offset, metric in enumerate(("precision", "recall", "f1")):
            axis.bar([x + (offset - 1) * width for x in positions], [data["categories"][cat][metric] for cat in categories], width, label=metric)
        axis.set_xticks(positions, categories); axis.set_ylim(0, 1); axis.set_ylabel("Puntuación"); axis.set_title("Extracción estructurada"); axis.legend(); figure.tight_layout(); figure.savefig(FIGURES / "llm-prf.png", dpi=180); figure.savefig(FIGURES / "llm-prf.svg"); plt.close(figure)


if __name__ == "__main__":
    report()
