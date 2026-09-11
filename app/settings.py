"""Configuración de infraestructura y zona horaria."""
from dataclasses import dataclass
import os
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


@dataclass(frozen=True)
class Settings:
    database_url: str
    app_timezone: str

    @classmethod
    def from_env(cls) -> "Settings":
        database_url = os.getenv(
            "DATABASE_URL",
            "postgresql+psycopg://audio_tfm:audio_tfm_dev@localhost:5432/audio_tfm",
        )
        timezone = os.getenv("APP_TIMEZONE", "UTC")
        try:
            ZoneInfo(timezone)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError(f"APP_TIMEZONE no es una zona IANA válida: {timezone}") from exc
        if not database_url.strip():
            raise ValueError("DATABASE_URL no puede estar vacío")
        return cls(database_url=database_url, app_timezone=timezone)


def get_settings() -> Settings:
    return Settings.from_env()
