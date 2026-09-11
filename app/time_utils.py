"""Operaciones de fecha sin datetimes naive."""
from datetime import date, datetime, time, timezone
from zoneinfo import ZoneInfo


def ensure_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("El datetime debe incluir zona horaria")
    return value


def to_utc(value: datetime) -> datetime:
    return ensure_aware(value).astimezone(timezone.utc)


def day_interval(day: date | str, timezone_name: str) -> tuple[datetime, datetime]:
    if isinstance(day, str):
        day = date.fromisoformat(day)
    zone = ZoneInfo(timezone_name)
    next_day = date.fromordinal(day.toordinal() + 1)
    start_local = datetime.combine(day, time.min, tzinfo=zone)
    end_local = datetime.combine(next_day, time.min, tzinfo=zone)
    return to_utc(start_local), to_utc(end_local)
