import io
import wave
from types import SimpleNamespace

import pytest

from app.transcription import AudioTooLong, InvalidAudio, MAX_SECONDS, Transcriber


def wav(seconds):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16000)
        output.writeframes(b"\0\0" * (seconds * 16000))
    buffer.seek(0)
    return buffer


def transcriber_without_gpu():
    return Transcriber.__new__(Transcriber)


def test_real_decoder_rejects_invalid_audio():
    with pytest.raises(InvalidAudio):
        transcriber_without_gpu().transcribe(io.BytesIO(b"not audio"))


def test_duration_limit_before_inference():
    with pytest.raises(AudioTooLong):
        transcriber_without_gpu().transcribe(wav(MAX_SECONDS + 1))


def test_thirty_minutes_is_allowed(monkeypatch):
    import numpy
    transcriber = transcriber_without_gpu()
    transcriber.model_name = "test"
    transcriber.model = SimpleNamespace(
        model=SimpleNamespace(device="cuda", compute_type="int8_float16"),
        transcribe=lambda *args, **kwargs: (iter(()), SimpleNamespace(language="es")),
    )
    monkeypatch.setattr("app.transcription.decode_audio", lambda *args, **kwargs: numpy.zeros(MAX_SECONDS * 16000, dtype=numpy.float32))
    assert transcriber.transcribe(io.BytesIO(b"valid"))["text"] == ""


def test_segments_consumed_before_response():
    transcriber = transcriber_without_gpu()
    transcriber.model_name = "test"
    consumed = []

    def segments():
        consumed.append(True)
        yield SimpleNamespace(text=" Hola")
        yield SimpleNamespace(text=" mundo")

    transcriber.model = SimpleNamespace(
        model=SimpleNamespace(device="cuda", compute_type="int8_float16"),
        transcribe=lambda *args, **kwargs: (segments(), SimpleNamespace(language="es")),
    )
    assert transcriber.transcribe(wav(1))["text"] == "Hola mundo"
    assert consumed


def test_language_is_passed_only_when_explicitly_requested():
    transcriber = transcriber_without_gpu()
    transcriber.model_name = "test"
    calls = []

    def fake_transcribe(*args, **kwargs):
        calls.append(kwargs)
        return (iter([SimpleNamespace(text=" texto")]), SimpleNamespace(language=kwargs.get("language", "es")))

    transcriber.model = SimpleNamespace(
        model=SimpleNamespace(device="cuda", compute_type="int8_float16"),
        transcribe=fake_transcribe,
    )
    assert transcriber.transcribe(wav(1), language="es")["language"] == "es"
    assert transcriber.transcribe(wav(1))["language"] == "es"
    assert calls == [{"beam_size": 5, "language": "es"}, {"beam_size": 5}]
