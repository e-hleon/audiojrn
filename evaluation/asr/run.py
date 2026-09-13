"""Ejecuta el benchmark ASR con progreso JSONL y reanudación."""
from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from evaluation.common import append_jsonl, corpus_cer, corpus_wer, normalize_text, read_jsonl, real_time_factor, summarize_numbers


def gpu_snapshot() -> dict | None:
    try:
        result = subprocess.run(["nvidia-smi", "--query-gpu=name,driver_version,memory.used,memory.total,utilization.gpu",
                                "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=2, check=True)
        name, driver, used, total, utilization = result.stdout.strip().split(", ")
        return {"name": name, "driver_version": driver, "memory_used_mb": int(used), "memory_total_mb": int(total),
                "utilization_percent": int(utilization)}
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


def run_model(model_name: str, manifest: Path, output_dir: Path, device: str, compute_type: str,
              resume: bool, download_root: Path | None = None) -> Path:
    from app.transcription import Transcriber
    samples = read_jsonl(manifest)
    output_dir.mkdir(parents=True, exist_ok=True)
    safe_model_name = model_name.replace("/", "_")
    output = output_dir / f"{safe_model_name}.jsonl"
    if output.exists() and not resume:
        raise FileExistsError(f"Ya existe {output}; usa --resume o un directorio nuevo")
    existing = {row["sample_id"] for row in read_jsonl(output)} if resume else set()
    gpu_before_load = gpu_snapshot()
    started = time.perf_counter()
    model_cache = download_root or output_dir.parent / "models"
    transcriber = Transcriber(model_name=model_name, device=device, compute_type=compute_type,
                              download_root=str(model_cache))
    load_seconds = time.perf_counter() - started
    details = transcriber.details()
    gpu_after_load = gpu_snapshot()
    if samples:
        transcriber.transcribe(samples[0]["audio"])  # warmup, excluido de las métricas por muestra
    for sample in samples:
        if sample["sample_id"] in existing:
            continue
        start = time.perf_counter()
        try:
            result = transcriber.transcribe(sample["audio"])
            elapsed = time.perf_counter() - start
            duration = float(__import__("soundfile").info(sample["audio"]).duration)
            append_jsonl(output, {"timestamp": datetime.now(timezone.utc).isoformat(), "model": model_name,
                "sample_id": sample["sample_id"], "reference": sample["reference"],
                "reference_normalized": normalize_text(sample["reference"]), "hypothesis": result["text"],
                "hypothesis_normalized": normalize_text(result["text"]), "language": result["language"],
                "audio_duration_seconds": duration, "inference_seconds": elapsed,
                "rtf": real_time_factor(elapsed, duration), "wer": corpus_wer([sample["reference"]], [result["text"]]),
                "cer": corpus_cer([sample["reference"]], [result["text"]]), "error": None})
        except Exception as exc:  # conserva el fallo y permite continuar con el corpus
            append_jsonl(output, {"timestamp": datetime.now(timezone.utc).isoformat(), "model": model_name,
                "sample_id": sample["sample_id"], "reference": sample["reference"], "error": type(exc).__name__,
                "error_message": str(exc)[:300]})
    records = read_jsonl(output)
    good = [row for row in records if not row.get("error")]
    summary = {"model": model_name, "device": details["device"], "compute_type": details["compute_type"],
        "samples": len(good), "attempted_samples": len(records), "failures": len(records) - len(good),
        "load_seconds": load_seconds, "hardware": {"platform": platform.platform(),
        "gpu_before_load": gpu_before_load, "gpu_after_load": gpu_after_load, "gpu_after_run": gpu_snapshot()},
        "dataset_manifest": str(manifest), "model_cache": str(model_cache),
        "git_commit": os.getenv("GIT_COMMIT") or os.popen("git rev-parse HEAD").read().strip(),
        "corpus_wer": corpus_wer([r["reference"] for r in good], [r["hypothesis"] for r in good]) if good else None,
        "corpus_cer": corpus_cer([r["reference"] for r in good], [r["hypothesis"] for r in good]) if good else None,
        "mean_wer": summarize_numbers([r["wer"] for r in good]), "latency": summarize_numbers([r["inference_seconds"] for r in good]),
        "rtf": summarize_numbers([r["rtf"] for r in good])}
    (output_dir / f"{safe_model_name}.summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", nargs="+", default=["large-v3"])
    parser.add_argument("--manifest", type=Path, default=Path(".evaluation-cache/asr/manifest.jsonl"))
    parser.add_argument("--output-dir", type=Path, default=Path("evaluation/results/asr"))
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--compute-type", default="int8_float16")
    parser.add_argument("--download-root", type=Path, default=None,
                        help="Caché de modelos de faster-whisper; por defecto, evaluation/results/models")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    if not args.manifest.exists():
        raise SystemExit("No existe el manifiesto; ejecuta evaluation.asr.prepare primero")
    if args.limit:
        rows = read_jsonl(args.manifest)[:args.limit]
        temp = args.output_dir / "selected-manifest.jsonl"
        temp.parent.mkdir(parents=True, exist_ok=True)
        temp.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
        args.manifest = temp
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for model in args.models:
        run_model(model, args.manifest, args.output_dir, args.device, args.compute_type, args.resume,
                  args.download_root)
