"""Inferencia local: una instancia CUDA, sin alternativa silenciosa en CPU."""
import logging
import os

from faster_whisper import WhisperModel
from faster_whisper.audio import decode_audio

# Manual capture has a configurable limit on Android, with a shared hard cap of
# 30 minutes. Continuous still produces short chunks on Android.
MAX_SECONDS = 30 * 60
SAMPLE_RATE = 16000


class InvalidAudio(ValueError):
    pass


class AudioTooLong(ValueError):
    pass


class Transcriber:
    def __init__(self, model_name=None, device=None, compute_type=None, download_root="/models"):
        self.model_name = model_name or os.getenv("WHISPER_MODEL", "large-v3")
        self.model = WhisperModel(
            self.model_name,
            device=device or "cuda",
            compute_type=compute_type or os.getenv("WHISPER_COMPUTE_TYPE", "int8_float16"),
            download_root=download_root,
        )
        logging.getLogger("uvicorn.error").info("ASR ready: %s", self.details())

    def details(self):
        return {"model": self.model_name, "device": self.model.model.device,
                "compute_type": self.model.model.compute_type}

    def transcribe(self, audio_file, language=None):
        try:
            audio = decode_audio(audio_file, sampling_rate=SAMPLE_RATE)
        except (ValueError, EOFError, OSError) as exc:
            raise InvalidAudio("No se puede decodificar el audio") from exc
        if not audio.size:
            raise InvalidAudio("El audio está vacío")
        if audio.size > MAX_SECONDS * SAMPLE_RATE:
            raise AudioTooLong("El audio supera los 30 minutos")
        options = {"beam_size": 5}
        if language is not None:
            options["language"] = language
        segments, info = self.model.transcribe(audio, **options)
        # La inferencia es diferida: consumir el generador antes de responder.
        text = "".join(segment.text for segment in segments).strip()
        return {"text": text, "language": info.language, **self.details()}
