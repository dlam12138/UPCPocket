"""Process resources shared by routes."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .cache import PublicCache, build_cache
from .config import Settings
from .crypto import DataCipher, KeyRing
from .library.journal import Database, build_database
from .library.service import LibraryLoginService, UserLibraryService
from .security import RateLimiter, build_rate_limiter
from .sessions import SessionBackend, build_backend
from .upstream.bridge import Upstream, load as load_upstream
from .upstream.pool import UpstreamPool, run_blocking


@dataclass
class Resources:
    settings: Settings
    keyring: KeyRing
    cipher: DataCipher
    store: SessionBackend
    database: Database
    cache: PublicCache
    limiter: RateLimiter
    pool: UpstreamPool
    login_pool: UpstreamPool
    upstream: Upstream
    login_service: LibraryLoginService
    library_service: UserLibraryService

    async def close(self) -> None:
        await self.store.close()
        await self.cache.close()
        await self.limiter.close()
        await run_blocking(self.database.close)


def build_resources(
    settings: Settings,
    *,
    upstream: Upstream | None = None,
    store: SessionBackend | None = None,
    database: Database | None = None,
    cache: PublicCache | None = None,
    limiter: RateLimiter | None = None,
    pool: UpstreamPool | None = None,
    login_pool: UpstreamPool | None = None,
    keyring: KeyRing | None = None,
) -> Resources:
    keyring = keyring or KeyRing.from_settings(settings)
    cipher = DataCipher.from_settings(settings)
    store = store or build_backend(settings)
    database = database or build_database(settings, cipher)
    cache = cache or build_cache(settings)
    limiter = limiter or build_rate_limiter(settings)
    pool = pool or UpstreamPool(
        concurrency=settings.upstream_concurrency, timeout=settings.upstream_timeout,
    )
    login_pool = login_pool or UpstreamPool(
        # CAS login gets its own small pool so a burst of booking cannot starve
        # authentication, and vice versa.
        concurrency=max(2, settings.upstream_concurrency // 4),
        timeout=settings.upstream_timeout,
    )
    upstream = upstream or load_upstream(settings.upstream_path)
    login_service = LibraryLoginService(
        settings, store, database, cipher, login_pool, upstream,
    )
    library_service = UserLibraryService(settings, store, database, cipher, pool, upstream)
    return Resources(
        settings=settings,
        keyring=keyring,
        cipher=cipher,
        store=store,
        database=database,
        cache=cache,
        limiter=limiter,
        pool=pool,
        login_pool=login_pool,
        upstream=upstream,
        login_service=login_service,
        library_service=library_service,
    )


def resources_for(app_state: Any) -> Resources:
    return app_state.resources
