"""Descarga un subconjunto determinista de FLEURS español en la caché local."""
from __future__ import annotations

import argparse
import json
import random
from datetime import datetime, timezone
from pathlib import Path

import soundfile as sf
from datasets import Audio, load_dataset


def prepare(limit: int, seed: int, cache: Path, language: str = "es_419") -> Path:
    random.seed(seed)
    dataset = load_dataset("google/fleurs", language, split="validation", cache_dir=str(cache / "hf"))
    dataset = dataset.cast_column("audio", Audio(decode=False))
    candidates = [row for row in dataset if row["transcription"].strip() and row["audio"]["path"]]
    # La permutación fijada evita seleccionar solo los primeros hablantes/clips.
    candidates.sort(key=lambda row: str(row["id"]))
    random.Random(seed).shuffle(candidates)
    selected = candidates[:limit]
    audio_dir = cache / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)
    manifest = cache / "manifest.jsonl"
    with manifest.open("w", encoding="utf-8") as output:
        for row in selected:
            sample_id = str(row["id"])
            source = Path(row["audio"]["path"])
            target = audio_dir / f"{sample_id}.wav"
            if not target.exists():
                samples, rate = sf.read(source, dtype="float32")
                sf.write(target, samples, rate)
            output.write(json.dumps({"sample_id": sample_id, "audio": str(target),
                                     "reference": row["transcription"], "dataset": "google/fleurs",
                                     "config": language, "split": "validation"}, ensure_ascii=False) + "\n")
    (cache / "metadata.json").write_text(json.dumps({"created_at": datetime.now(timezone.utc).isoformat(),
        "dataset": "google/fleurs", "dataset_url": "https://huggingface.co/datasets/google/fleurs",
        "dataset_config": language, "split": "validation", "license": "CC BY 4.0",
        "selection": "referencia no vacía y audio válido; IDs ordenados y barajados con seed fija",
        "limit": limit, "seed": seed, "selected_ids": [str(row["id"]) for row in selected]},
        ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=50)
    parser.add_argument("--seed", type=int, default=20260905)
    parser.add_argument("--cache", type=Path, default=Path(".evaluation-cache/asr"))
    args = parser.parse_args()
    print(prepare(args.limit, args.seed, args.cache))
