"""Public campus data cache with stale fallback.

A failed upstream refresh must never look like a successful empty result. With
a previous success we serve it with `stale` marked; without one the caller gets
an explicit upstream error.
"""

from __future__ import annotations

import hashlib
import json
import time
from typing import Awaitable, Callable, Protocol

from redis import asyncio as redis_asyncio

from .config import Settings


class CacheBackend(Protocol):
    async def get(self, key: str) -> bytes | None: ...

    async def set(self, key: str, value: bytes, ttl: int) -> None: ...

    async def close(self) -> None: ...


class RedisCache:
    def __init__(self, url: str):
        self.client = redis_asyncio.Redis.from_url(
            url, decode_responses=False, socket_timeout=3, socket_connect_timeout=3,
        )

    async def get(self, key: str) -> bytes | None:
        return await self.client.get(f"upcp:cache:{key}")

    async def set(self, key: str, value: bytes, ttl: int) -> None:
        await self.client.set(f"upcp:cache:{key}", value, ex=max(1, ttl))

    async def close(self) -> None:
        await self.client.aclose()


class MemoryCache:
    def __init__(self):
        self._values: dict[str, tuple[bytes, float]] = {}

    async def get(self, key: str) -> bytes | None:
        entry = self._values.get(key)
        if entry is None:
            return None
        value, expires = entry
        if expires <= time.monotonic():
            self._values.pop(key, None)
            return None
        return value

    async def set(self, key: str, value: bytes, ttl: int) -> None:
        self._values[key] = (value, time.monotonic() + max(1, ttl))

    async def close(self) -> None:
        return None


class PublicCache:
    def __init__(self, backend: CacheBackend, settings: Settings):
        self.backend = backend
        self.settings = settings

    async def fetch(
        self,
        key: str,
        loader: Callable[[], Awaitable[object]],
        *,
        ttl: int,
    ) -> tuple[object, str]:
        """Return (payload, state) where state is fresh/hit/stale."""
        fresh = await self.backend.get(key)
        if fresh is not None:
            try:
                return json.loads(fresh), "hit"
            except (ValueError, RecursionError):
                pass
        try:
            value = await loader()
        except Exception:
            stale = await self.backend.get(self._stale_key(key))
            if stale is None:
                raise
            try:
                return json.loads(stale), "stale"
            except (ValueError, RecursionError):
                raise
        try:
            encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        except (TypeError, ValueError, RecursionError):
            raise
        await self.backend.set(key, encoded, max(1, ttl))
        await self.backend.set(self._stale_key(key), encoded, self.settings.public_cache_stale_ttl)
        return value, "miss"

    @staticmethod
    def _stale_key(key: str) -> str:
        return f"{key}:stale"

    async def close(self) -> None:
        await self.backend.close()


def cache_key(name: str, params: dict[str, list[str]] | None = None) -> str:
    if not params:
        return name
    canonical = json.dumps(params, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return f"{name}:{hashlib.sha256(canonical.encode('utf-8')).hexdigest()[:32]}"


def build_cache(settings: Settings) -> PublicCache:
    backend: CacheBackend
    if settings.redis_url:
        backend = RedisCache(settings.redis_url)
    else:
        backend = MemoryCache()
    return PublicCache(backend, settings)
