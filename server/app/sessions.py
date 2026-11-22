"""Browser sessions and encrypted per-user library sessions.

Browser sessions hold only an opaque ID, the user identity and CSRF material.
Library tokens/cookies are stored encrypted with a per-user AAD so a value
cannot be moved between users or branches.
"""

from __future__ import annotations

import asyncio
import json
import secrets
import time
from dataclasses import dataclass
from typing import AsyncIterator, Mapping, Protocol

from redis import asyncio as redis_asyncio

from .config import Settings
from .crypto import new_secret


LIBRARY_BRANCHES = ("tangdaowan", "east", "qiushi")
KEY_PREFIX = "upcp:"
LOGIN_NONCE_TTL = 600


class SessionStoreError(RuntimeError):
    pass


class LockTimeout(RuntimeError):
    pass


@dataclass(frozen=True)
class BrowserSession:
    id: str
    user_id: str
    csrf: str
    revision: str
    created_at: float
    last_seen: float

    def to_json(self) -> str:
        return json.dumps({
            "user_id": self.user_id,
            "csrf": self.csrf,
            "revision": self.revision,
            "created_at": self.created_at,
            "last_seen": self.last_seen,
        }, separators=(",", ":"))

    @classmethod
    def from_json(cls, session_id: str, raw: str) -> "BrowserSession | None":
        try:
            data = json.loads(raw)
            return cls(
                id=session_id,
                user_id=str(data["user_id"]),
                csrf=str(data["csrf"]),
                revision=str(data["revision"]),
                created_at=float(data["created_at"]),
                last_seen=float(data["last_seen"]),
            )
        except (KeyError, TypeError, ValueError, RecursionError):
            return None


def new_browser_session(user_id: str) -> BrowserSession:
    now = time.time()
    return BrowserSession(
        id=new_secret(32),
        user_id=user_id,
        csrf=new_secret(32),
        revision=new_secret(18),
        created_at=now,
        last_seen=now,
    )


class SessionBackend(Protocol):
    async def ping(self) -> bool: ...

    async def get_browser(self, session_id: str) -> BrowserSession | None: ...

    async def save_browser(self, session: BrowserSession) -> None: ...

    async def drop_browser(self, session_id: str) -> None: ...

    async def get_library(self, user_id: str, branch: str) -> bytes | None: ...

    async def set_library(self, user_id: str, branch: str, payload: bytes) -> None: ...

    async def drop_library(self, user_id: str, branch: str) -> None: ...

    async def has_library(self, user_id: str, branch: str) -> bool: ...

    async def drop_libraries(self, user_id: str) -> None: ...

    async def reserve_nonce(self, nonce: str) -> bool: ...

    def lock(self, user_id: str, *, ttl: float = 30.0, wait: float = 20.0) -> "AsyncIterator[None]": ...

    async def close(self) -> None: ...


class RedisBackend:
    def __init__(self, url: str, settings: Settings):
        self.client = redis_asyncio.Redis.from_url(
            url, decode_responses=False, socket_timeout=3, socket_connect_timeout=3,
        )
        self.settings = settings

    @staticmethod
    def _browser_key(session_id: str) -> str:
        return f"{KEY_PREFIX}sess:{session_id}"

    @staticmethod
    def _library_key(user_id: str, branch: str) -> str:
        return f"{KEY_PREFIX}lib:{user_id}:{branch}"

    @staticmethod
    def _absolute_key(user_id: str, branch: str) -> str:
        return f"{KEY_PREFIX}libabs:{user_id}:{branch}"

    @staticmethod
    def _nonce_key(nonce: str) -> str:
        return f"{KEY_PREFIX}nonce:{nonce}"

    async def ping(self) -> bool:
        return bool(await self.client.ping())

    async def get_browser(self, session_id: str) -> BrowserSession | None:
        key = self._browser_key(session_id)
        raw = await self.client.get(key)
        if raw is None:
            return None
        session = BrowserSession.from_json(session_id, raw.decode("utf-8", "replace"))
        if session is None:
            await self.client.delete(key)
            return None
        now = time.time()
        remaining = self.settings.session_absolute_seconds - (now - session.created_at)
        if remaining <= 0:
            await self.drop_browser(session_id)
            return None
        session = BrowserSession(
            id=session.id, user_id=session.user_id, csrf=session.csrf,
            revision=session.revision, created_at=session.created_at, last_seen=now,
        )
        await self.client.set(
            key, session.to_json(), ex=max(1, min(self.settings.session_idle_seconds, int(remaining))),
        )
        return session

    async def save_browser(self, session: BrowserSession) -> None:
        index = f"{KEY_PREFIX}sess:user:{session.user_id}"
        async with self.client.pipeline(transaction=True) as pipe:
            pipe.set(
                self._browser_key(session.id), session.to_json(),
                ex=self.settings.session_absolute_seconds,
            )
            pipe.sadd(index, session.id)
            pipe.expire(index, self.settings.session_absolute_seconds)
            await pipe.execute()

    async def drop_browser(self, session_id: str) -> None:
        raw = await self.client.get(self._browser_key(session_id))
        session = BrowserSession.from_json(session_id, raw.decode("utf-8", "replace")) if raw else None
        async with self.client.pipeline(transaction=True) as pipe:
            pipe.delete(self._browser_key(session_id))
            if session is not None:
                pipe.srem(f"{KEY_PREFIX}sess:user:{session.user_id}", session_id)
            await pipe.execute()

    async def drop_user_data(self, user_id: str) -> None:
        """Remove every Redis key belonging to one user (account deletion)."""
        index = f"{KEY_PREFIX}sess:user:{user_id}"
        members = await self.client.smembers(index)
        keys = [self._browser_key(
            value.decode() if isinstance(value, bytes) else value,
        ) for value in members]
        keys.append(index)
        for branch in LIBRARY_BRANCHES:
            keys.extend((self._library_key(user_id, branch), self._absolute_key(user_id, branch)))
        await self.client.delete(*keys)

    async def get_library(self, user_id: str, branch: str) -> bytes | None:
        key = self._library_key(user_id, branch)
        payload = await self.client.get(key)
        if payload is None:
            return None
        absolute_raw = await self.client.get(self._absolute_key(user_id, branch))
        try:
            created = float(absolute_raw or 0)
        except (TypeError, ValueError):
            created = 0
        remaining = self.settings.library_absolute_seconds - (time.time() - created) if created else 0
        if remaining <= 0:
            await self.drop_library(user_id, branch)
            return None
        await self.client.expire(key, max(1, min(self.settings.library_idle_seconds, int(remaining))))
        return payload

    async def set_library(self, user_id: str, branch: str, payload: bytes) -> None:
        ttl = min(self.settings.library_idle_seconds, self.settings.library_absolute_seconds)
        async with self.client.pipeline(transaction=True) as pipe:
            pipe.set(self._library_key(user_id, branch), payload, ex=ttl)
            pipe.set(self._absolute_key(user_id, branch), str(time.time()),
                     ex=self.settings.library_absolute_seconds)
            await pipe.execute()

    async def drop_library(self, user_id: str, branch: str) -> None:
        await self.client.delete(self._library_key(user_id, branch), self._absolute_key(user_id, branch))

    async def has_library(self, user_id: str, branch: str) -> bool:
        return bool(await self.client.exists(self._library_key(user_id, branch)))

    async def drop_libraries(self, user_id: str) -> None:
        keys = []
        for branch in LIBRARY_BRANCHES:
            keys.extend((self._library_key(user_id, branch), self._absolute_key(user_id, branch)))
        await self.client.delete(*keys)

    async def reserve_nonce(self, nonce: str) -> bool:
        # SET NX is the single-use marker: the first submission wins, every
        # later replay of the same envelope fails. No unauthenticated endpoint
        # writes here, so Redis growth is bounded by rate-limited logins.
        return bool(await self.client.set(
            self._nonce_key(nonce), "1", ex=LOGIN_NONCE_TTL, nx=True,
        ))

    def lock(self, user_id: str, *, ttl: float = 30.0, wait: float = 20.0) -> AsyncIterator[None]:
        return _RedisLock(self.client, f"{KEY_PREFIX}lock:user:{user_id}", ttl=ttl, wait=wait)

    async def close(self) -> None:
        await self.client.aclose()


class _RedisLock:
    def __init__(self, client, key: str, *, ttl: float, wait: float):
        self.client = client
        self.key = key
        self.ttl_ms = max(1000, int(ttl * 1000))
        self.wait = wait
        self.token = secrets.token_urlsafe(24)

    async def __aenter__(self) -> None:
        deadline = time.monotonic() + self.wait
        while True:
            if await self.client.set(self.key, self.token, nx=True, px=self.ttl_ms):
                return None
            if time.monotonic() >= deadline:
                raise LockTimeout("library operation already in progress")
            await asyncio.sleep(0.05)

    async def __aexit__(self, exc_type, exc, tb) -> None:
        script = (
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
            "return redis.call('del', KEYS[1]) else return 0 end"
        )
        try:
            await self.client.eval(script, 1, self.key, self.token)
        except Exception:  # noqa: BLE001 - releasing must not mask the original error
            pass
        return None


class MemoryBackend:
    """Development/test backend. No persistence and no cross-process sharing."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self._lock = asyncio.Lock()
        self._browsers: dict[str, tuple[BrowserSession, float, float]] = {}
        self._library: dict[tuple[str, str], tuple[bytes, float, float]] = {}
        self._user_locks: dict[str, asyncio.Lock] = {}
        self._nonces: dict[str, float] = {}

    async def ping(self) -> bool:
        return True

    async def get_browser(self, session_id: str) -> BrowserSession | None:
        async with self._lock:
            entry = self._browsers.get(session_id)
            if entry is None:
                return None
            session, expires, absolute = entry
            now = time.time()
            if min(expires, absolute) <= now:
                self._browsers.pop(session_id, None)
                return None
            session = BrowserSession(
                id=session.id, user_id=session.user_id, csrf=session.csrf,
                revision=session.revision, created_at=session.created_at, last_seen=now,
            )
            self._browsers[session_id] = (
                session,
                min(now + self.settings.session_idle_seconds, absolute),
                absolute,
            )
            return session

    async def save_browser(self, session: BrowserSession) -> None:
        async with self._lock:
            now = time.time()
            self._browsers[session.id] = (
                session,
                min(now + self.settings.session_idle_seconds,
                    now + self.settings.session_absolute_seconds),
                now + self.settings.session_absolute_seconds,
            )

    async def drop_browser(self, session_id: str) -> None:
        async with self._lock:
            self._browsers.pop(session_id, None)

    async def get_library(self, user_id: str, branch: str) -> bytes | None:
        async with self._lock:
            entry = self._library.get((user_id, branch))
            if entry is None:
                return None
            payload, expires, absolute = entry
            now = time.time()
            if now >= min(expires, absolute):
                self._library.pop((user_id, branch), None)
                return None
            self._library[(user_id, branch)] = (
                payload, min(now + self.settings.library_idle_seconds, absolute), absolute,
            )
            return payload

    async def set_library(self, user_id: str, branch: str, payload: bytes) -> None:
        now = time.time()
        async with self._lock:
            self._library[(user_id, branch)] = (
                payload,
                now + min(self.settings.library_idle_seconds, self.settings.library_absolute_seconds),
                now + self.settings.library_absolute_seconds,
            )

    async def drop_library(self, user_id: str, branch: str) -> None:
        async with self._lock:
            self._library.pop((user_id, branch), None)

    async def has_library(self, user_id: str, branch: str) -> bool:
        return await self.get_library(user_id, branch) is not None

    async def drop_libraries(self, user_id: str) -> None:
        async with self._lock:
            for branch in LIBRARY_BRANCHES:
                self._library.pop((user_id, branch), None)

    async def reserve_nonce(self, nonce: str) -> bool:
        async with self._lock:
            now = time.monotonic()
            expiry = self._nonces.get(nonce)
            if expiry is not None and expiry > now:
                return False
            self._nonces = {
                value: deadline for value, deadline in self._nonces.items() if deadline > now
            }
            self._nonces[nonce] = now + LOGIN_NONCE_TTL
            return True

    def lock(self, user_id: str, *, ttl: float = 30.0, wait: float = 20.0) -> AsyncIterator[None]:
        return _MemoryLock(self, user_id, wait=wait)

    async def drop_user_data(self, user_id: str) -> None:
        await self.drop_libraries(user_id)
        async with self._lock:
            self._browsers = {
                sid: value for sid, value in self._browsers.items() if value[0].user_id != user_id
            }

    async def close(self) -> None:
        return None


class _MemoryLock:
    def __init__(self, backend: MemoryBackend, user_id: str, *, wait: float):
        self.backend = backend
        self.user_id = user_id
        self.wait = wait

    async def __aenter__(self) -> None:
        async with self.backend._lock:
            lock = self.backend._user_locks.setdefault(self.user_id, asyncio.Lock())
        try:
            await asyncio.wait_for(lock.acquire(), timeout=self.wait)
        except asyncio.TimeoutError:
            raise LockTimeout("library operation already in progress") from None
        self.lock = lock

    async def __aexit__(self, exc_type, exc, tb) -> None:
        self.lock.release()
        return None


def build_backend(settings: Settings) -> SessionBackend:
    if settings.redis_url:
        return RedisBackend(settings.redis_url, settings)
    if settings.production:
        raise SessionStoreError("REDIS_URL is required in production")
    return MemoryBackend(settings)
