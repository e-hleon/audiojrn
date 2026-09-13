"""Prepara el fallback reproducible SLR61 de mensajes meteorológicos españoles.

No se usa para sustituir FLEURS: permite conservar una evaluación acotada cuando
el acceso al conjunto de Hugging Face no está disponible. Selecciona 100 de los
180 mensajes publicados (90 es_AR y 90 es_ES) tras ordenar por identificador y
barajar con la semilla histórica del proyecto.
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import shutil
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path


BASE_URL = "https://www.openslr.org/resources/61/"
ARCHIVE = "es_weather_messages.zip"
INDEXES = ("es_ar_line_index_weather.tsv", "es_es_line_index_weather.tsv")


def download(url: str, destination: Path) -> None:
    if destination.exists():
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)


def read_index(path: Path, variant: str) -> list[dict]:
    rows: list[dict] = []
    with path.open(encoding="utf-8") as source:
        for row in csv.reader(source, delimiter="\t"):
            if len(row) < 2 or not row[0].strip() or not row[1].strip():
                continue
            rows.append({"sample_id": f"{variant}:{row[0].strip()}", "file_id": row[0].strip(),
                         "reference": row[1].strip(), "variant": variant})
    return rows


def find_audio(audio_root: Path, file_id: str) -> Path:
    matches = list(audio_root.rglob(f"{file_id}.wav"))
    if len(matches) != 1:
        raise FileNotFoundError(f"No se encontró un WAV único para {file_id}: {matches}")
    return matches[0]


def prepare(limit: int, seed: int, cache: Path) -> Path:
    source = cache / "source"
    for name in (*INDEXES, ARCHIVE):
        download(BASE_URL + name, source / name)
    audio_root = source / "audio"
    if not audio_root.exists():
        with zipfile.ZipFile(source / ARCHIVE) as archive:
            archive.extractall(audio_root)
    candidates = read_index(source / INDEXES[0], "es_AR") + read_index(source / INDEXES[1], "es_ES")
    candidates.sort(key=lambda row: row["sample_id"])
    random.Random(seed).shuffle(candidates)
    selected = candidates[:limit]
    if len(selected) != limit:
        raise ValueError(f"SLR61 solo proporcionó {len(selected)} candidatos; se solicitaron {limit}")
    manifest = cache / "manifest.jsonl"
    with manifest.open("w", encoding="utf-8") as output:
        for row in selected:
            output.write(json.dumps({**row, "audio": str(find_audio(audio_root, row["file_id"]))},
                                    ensure_ascii=False) + "\n")
    (cache / "metadata.json").write_text(json.dumps({
        "created_at": datetime.now(timezone.utc).isoformat(), "dataset": "OpenSLR SLR61 weather messages",
        "dataset_url": "https://www.openslr.org/61/", "archive": BASE_URL + ARCHIVE,
        "license": "CC BY-SA 4.0", "population": "90 mensajes es_AR y 90 mensajes es_ES",
        "selection": "IDs ordenados y barajados con semilla fija; se toman los primeros N",
        "seed": seed, "limit": limit, "selected_ids": [row["sample_id"] for row in selected],
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--seed", type=int, default=20260906)
    parser.add_argument("--cache", type=Path, default=Path(".evaluation-cache/asr-slr61"))
    args = parser.parse_args()
    print(prepare(args.limit, args.seed, args.cache))
