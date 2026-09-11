"""Motor y base declarativa de SQLAlchemy."""
from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from app.settings import Settings, get_settings


class Base(DeclarativeBase):
    pass


def make_engine(settings: Settings | None = None):
    settings = settings or get_settings()
    return create_engine(settings.database_url, pool_pre_ping=True)


def make_session_factory(settings: Settings | None = None):
    return sessionmaker(bind=make_engine(settings), class_=Session, expire_on_commit=False)
