"""Prueba HTTP real, sin dependencias: python3 scripts/smoke_test.py AUDIO.wav."""
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request
import uuid

BASE_URL = os.getenv("API_URL", "http://127.0.0.1:8000").rstrip("/")


def upload(content):
    boundary = uuid.uuid4().hex
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.wav"\r\n'
        'Content-Type: application/octet-stream\r\n\r\n'
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        BASE_URL + "/transcriptions", data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Uso: python3 scripts/smoke_test.py AUDIO.wav")
    with urllib.request.urlopen(BASE_URL + "/health", timeout=10) as response:
        health = json.load(response)
    assert health["status"] == "ready", health
    assert health["device"] == "cuda", health
    status, result = upload(Path(sys.argv[1]).read_bytes())
    assert status == 200, (status, result)
    assert result["text"].strip(), result
    assert result["device"] == "cuda", result
    assert result["compute_type"] == "int8_float16", result
    print(json.dumps(result, ensure_ascii=False, indent=2))
    status, error = upload(b"This is not an audio file")
    assert status == 400, (status, error)
    status, error = upload(b"")
    assert status == 400, (status, error)
    print("OK: audio real por HTTP, CUDA, texto y errores de entrada")


if __name__ == "__main__":
    main()
