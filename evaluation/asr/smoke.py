"""Smoke test acotado de un modelo ASR con muestreo periódico de GPU."""
from __future__ import annotations

import argparse
import json
import subprocess
import threading
import time
from datetime import datetime, timezone
from pathlib import Path


def gpu_sample() -> dict | None:
    try:
        result = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,memory.used,memory.free,memory.total,utilization.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, check=True, timeout=2,
        )
        name, used, free, total, utilization = result.stdout.strip().split(", ")
        return {"name": name, "memory_used_mb": int(used), "memory_free_mb": int(free),
                "memory_total_mb": int(total), "utilization_percent": int(utilization)}
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


class GPUMonitor:
    def __init__(self, interval: float = 0.25):
        self.interval = interval
        self.samples: list[dict] = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._sample_until_stopped, daemon=True)

    def _sample_until_stopped(self) -> None:
        while not self._stop.is_set():
            sample = gpu_sample()
            if sample:
                self.samples.append({"elapsed_seconds": time.perf_counter(), **sample})
            self._stop.wait(self.interval)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=3)


def run(model: str, audio: Path, repetitions: int, compute_type: str, download_root: Path) -> dict:
    from app.transcription import Transcriber

    before = gpu_sample()
    monitor = GPUMonitor()
    monitor.start()
    result = {
        "started_at": datetime.now(timezone.utc).isoformat(), "model": model,
        "compute_type_requested": compute_type, "audio": str(audio),
        "repetitions_requested": repetitions, "gpu_before": before,
        "load": {}, "transcriptions": [],
    }
    try:
        started = time.perf_counter()
        transcriber = Transcriber(model_name=model, device="cuda", compute_type=compute_type,
                                  download_root=str(download_root))
        result["load"] = {"ok": True, "seconds": time.perf_counter() - started,
                          "details": transcriber.details(), "gpu_after": gpu_sample()}
        for index in range(repetitions):
            started = time.perf_counter()
            transcription = transcriber.transcribe(audio)
            result["transcriptions"].append({
                "index": index + 1, "ok": True, "seconds": time.perf_counter() - started,
                "text": transcription["text"], "language": transcription["language"],
                "gpu_after": gpu_sample(),
            })
    except Exception as exc:
        result["error"] = {"type": type(exc).__name__, "message": str(exc)[:500]}
        if not result["load"]:
            result["load"] = {"ok": False}
    finally:
        monitor.stop()
        result["gpu_after"] = gpu_sample()
        used = [sample["memory_used_mb"] for sample in monitor.samples]
        result["gpu_sampling"] = {
            "interval_seconds": monitor.interval, "samples": len(used),
            "minimum_used_mb": min(used) if used else None,
            "maximum_used_mb": max(used) if used else None,
            "approximate_increase_mb": max(used) - before["memory_used_mb"]
            if used and before else None,
        }
        result["completed_transcriptions"] = sum(
            item.get("ok", False) for item in result["transcriptions"]
        )
        result["ok"] = result["load"].get("ok", False) and (
            result["completed_transcriptions"] == repetitions
        )
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--compute-type", default="int8_float16")
    parser.add_argument("--download-root", type=Path, default=Path("/models"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    outcome = run(args.model, args.audio, args.repetitions, args.compute_type, args.download_root)
    rendered = json.dumps(outcome, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    raise SystemExit(0 if outcome["ok"] else 2)
