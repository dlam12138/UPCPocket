"""Environment configuration for the multi-user web service.

Data/session/login secrets are read from files, never from environment values.
PostgreSQL and Redis DSNs necessarily carry their passwords in environment
variables; the dataclass fields are excluded from repr and must never be
logged. The DSNs are only visible to the app container, not to redis/db.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_WEB_ROOT = REPO_ROOT / "web"
DEFAULT_UPSTREAM_PATH = REPO_ROOT / "tools" / "web"
COOKIE_SECURE_NAME = "__Host-upcp_session"
COOKIE_DEV_NAME = "upcp_session"
LOGIN_BODY_LIMIT = 8192


class ConfigError(RuntimeError):
    """Raised when required production configuration is missing or invalid."""


def _bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name, "").strip().lower()
    if not raw:
        return default
    if raw in {"1", "true", "yes", "on"}:
        return True
    if raw in {"0", "false", "no", "off"}:
        return False
    raise ConfigError(f"invalid boolean for {name}")


def _int(env: Mapping[str, str], name: str, default: int, low: int, high: int) -> int:
    raw = env.get(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        raise ConfigError(f"invalid integer for {name}") from None
    if not low <= value <= high:
        raise ConfigError(f"out of range for {name}")
    return value


def _float(env: Mapping[str, str], name: str, default: float, low: float, high: float) -> float:
    raw = env.get(name, "").strip()
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        raise ConfigError(f"invalid number for {name}") from None
    if not low <= value <= high:
        raise ConfigError(f"out of range for {name}")
    return value


def _path(env: Mapping[str, str], name: str, default: Path | None) -> Path:
    raw = env.get(name, "").strip()
    if raw:
        return Path(raw).expanduser()
    if default is None:
        raise ConfigError(f"missing {name}")
    return default


def _read_secret(path: Path, name: str, *, minimum: int, exact: int | None = None) -> bytes:
    try:
        value = path.read_bytes()
    except OSError:
        raise ConfigError(f"cannot read {name}") from None
    if exact is not None and len(value) != exact:
        raise ConfigError(f"{name} must be exactly {exact} bytes")
    if len(value) < minimum:
        raise ConfigError(f"{name} is too short")
    return value


def _origin(env: Mapping[str, str]) -> str:
    raw = env.get("PUBLIC_ORIGIN", "").strip().rstrip("/")
    if not raw:
        raise ConfigError("missing PUBLIC_ORIGIN")
    if not re.fullmatch(r"https?://[A-Za-z0-9.\-]+(?::\d{1,5})?", raw):
        raise ConfigError("invalid PUBLIC_ORIGIN")
    return raw


@dataclass(frozen=True)
class Settings:
    env: str
    public_origin: str
    web_root: Path
    upstream_path: Path
    serve_static: bool
    redis_url: str = field(repr=False)
    database_url: str = field(repr=False)
    db_auto_migrate: bool
    login_key_id: str
    login_key_dir: Path
    login_private_key_file: Path | None
    data_key: bytes = field(repr=False)
    data_key_version: int
    old_data_keys: tuple[tuple[int, bytes], ...] = field(repr=False)
    session_secret: bytes = field(repr=False)
    booking_enabled: bool
    rate_limit_enabled: bool
    trust_proxy: bool
    log_level: str
    session_idle_seconds: int
    session_absolute_seconds: int
    library_idle_seconds: int
    library_absolute_seconds: int
    login_rate_limit: int
    login_rate_window: int
    upstream_timeout: float
    upstream_concurrency: int
    classrooms_cache_ttl: int
    public_cache_ttl: int
    public_cache_stale_ttl: int
    allow_ephemeral_keys: bool
    cookie_secure: bool
    cookie_name: str
    consent_version: str = "2026-11-web-v1"
    retention_attempts_days: int = 90
    retention_audit_days: int = 180

    @property
    def https(self) -> bool:
        return self.public_origin.startswith("https://")

    @property
    def production(self) -> bool:
        return self.env == "production"

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        env = dict(os.environ if env is None else env)
        environment = env.get("ENV", "production").strip().lower()
        if environment not in {"production", "development"}:
            raise ConfigError("ENV must be production or development")
        production = environment == "production"
        public_origin = _origin(env)
        https = public_origin.startswith("https://")
        serve_static = _bool(env, "SERVE_STATIC", not production)

        allow_ephemeral = _bool(env, "ALLOW_EPHEMERAL_KEYS", False)
        if allow_ephemeral and production:
            raise ConfigError("ALLOW_EPHEMERAL_KEYS is not allowed in production")

        login_key_id = env.get("LOGIN_KEY_ID", "").strip() or "dev"
        login_key_dir = _path(env, "LOGIN_KEY_DIR", REPO_ROOT / ".local" / "server-keys")
        private_file = env.get("LOGIN_RSA_PRIVATE_KEY", "").strip()
        login_private_key_file = Path(private_file).expanduser() if private_file else None

        data_key_file = env.get("DATA_KEY_FILE", "").strip()
        session_secret_file = env.get("SESSION_SECRET_FILE", "").strip()
        if data_key_file and session_secret_file:
            data_key = _read_secret(Path(data_key_file).expanduser(), "DATA_KEY_FILE", minimum=32, exact=32)
            session_secret = _read_secret(
                Path(session_secret_file).expanduser(), "SESSION_SECRET_FILE", minimum=32,
            )
        elif allow_ephemeral:
            data_key = os.urandom(32)
            session_secret = os.urandom(32)
        else:
            raise ConfigError("missing DATA_KEY_FILE/SESSION_SECRET_FILE")

        old_data_keys: list[tuple[int, bytes]] = []
        for item in env.get("DATA_KEY_OLD", "").split(","):
            item = item.strip()
            if not item:
                continue
            version_text, separator, key_text = item.partition(":")
            if not separator:
                raise ConfigError("invalid DATA_KEY_OLD")
            try:
                version = int(version_text)
                key = bytes.fromhex(key_text)
            except ValueError:
                raise ConfigError("invalid DATA_KEY_OLD") from None
            if not 1 <= version <= 255 or len(key) != 32 or version == _int(
                env, "DATA_KEY_VERSION", 1, 1, 255,
            ):
                raise ConfigError("invalid DATA_KEY_OLD")
            old_data_keys.append((version, key))

        redis_url = env.get("REDIS_URL", "").strip()
        database_url = env.get("DATABASE_URL", "").strip()
        if production and not allow_ephemeral:
            if not redis_url:
                raise ConfigError("missing REDIS_URL")
            if not database_url:
                raise ConfigError("missing DATABASE_URL")
        if production and not https:
            raise ConfigError("PUBLIC_ORIGIN must use https in production")

        cookie_secure = _bool(env, "SESSION_COOKIE_SECURE", https)
        if production and not cookie_secure:
            raise ConfigError("SESSION_COOKIE_SECURE must be enabled in production")
        cookie_name = env.get("SESSION_COOKIE_NAME", "").strip() or (
            COOKIE_SECURE_NAME if cookie_secure else COOKIE_DEV_NAME
        )
        if cookie_secure and not cookie_name.startswith("__Host-"):
            raise ConfigError("secure cookie must use the __Host- prefix")
        if not re.fullmatch(r"[A-Za-z0-9_.\-]{1,64}", cookie_name):
            raise ConfigError("invalid SESSION_COOKIE_NAME")

        return cls(
            env=environment,
            public_origin=public_origin,
            web_root=_path(env, "WEB_ROOT", DEFAULT_WEB_ROOT),
            upstream_path=_path(env, "UPSTREAM_PATH", DEFAULT_UPSTREAM_PATH),
            serve_static=serve_static,
            redis_url=redis_url,
            database_url=database_url,
            db_auto_migrate=_bool(env, "DB_AUTO_MIGRATE", True),
            login_key_id=login_key_id,
            login_key_dir=login_key_dir,
            login_private_key_file=login_private_key_file,
            data_key=data_key,
            data_key_version=_int(env, "DATA_KEY_VERSION", 1, 1, 255),
            old_data_keys=tuple(old_data_keys),
            session_secret=session_secret,
            booking_enabled=_bool(env, "BOOKING_ENABLED", True),
            rate_limit_enabled=_bool(env, "RATE_LIMIT_ENABLED", True),
            trust_proxy=_bool(env, "TRUST_PROXY", production),
            log_level=env.get("LOG_LEVEL", "INFO").strip().upper() or "INFO",
            session_idle_seconds=_int(env, "SESSION_IDLE_SECONDS", 1800, 60, 86400),
            session_absolute_seconds=_int(env, "SESSION_ABSOLUTE_SECONDS", 28800, 300, 604800),
            library_idle_seconds=_int(env, "LIBRARY_SESSION_IDLE_SECONDS", 1800, 60, 86400),
            library_absolute_seconds=_int(env, "LIBRARY_SESSION_ABSOLUTE_SECONDS", 28800, 300, 604800),
            login_rate_limit=_int(env, "LOGIN_RATE_LIMIT", 5, 1, 100),
            login_rate_window=_int(env, "LOGIN_RATE_WINDOW_SECONDS", 600, 30, 86400),
            upstream_timeout=_float(env, "UPSTREAM_TIMEOUT_SECONDS", 20.0, 1.0, 120.0),
            upstream_concurrency=_int(env, "UPSTREAM_CONCURRENCY", 20, 1, 200),
            classrooms_cache_ttl=_int(env, "CLASSROOMS_CACHE_TTL_SECONDS", 60, 0, 3600),
            public_cache_ttl=_int(env, "PUBLIC_CACHE_TTL_SECONDS", 3600, 0, 86400),
            public_cache_stale_ttl=_int(env, "PUBLIC_CACHE_STALE_TTL_SECONDS", 86400, 60, 604800),
            allow_ephemeral_keys=allow_ephemeral,
            cookie_secure=cookie_secure,
            cookie_name=cookie_name,
        )
