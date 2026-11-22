"""Request security: origin checks, CSRF, rate limiting, hashes and headers."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import ipaddress
import re
import time
from typing import Protocol

from fastapi import Request
from redis import asyncio as redis_asyncio

from .config import Settings


_LABEL = re.compile(r"[A-Za-z0-9_\-]{1,64}")


def client_ip(request: Request, settings: Settings) -> str:
    if settings.trust_proxy:
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            # Caddy appends the real client address to any client-supplied
            # header, so the last entry is the one this deployment can trust.
            candidate = forwarded.rsplit(",", 1)[-1].strip()
            if candidate and len(candidate) <= 64:
                return candidate
    client = request.client
    return client.host if client is not None else ""


def network_key(address: str) -> str:
    """Aggregate IPv6 to /64 for rate limiting; keep IPv4 and unknown values."""
    try:
        parsed = ipaddress.ip_address(address)
    except ValueError:
        return address[:64]
    if parsed.version == 6:
        return str(ipaddress.IPv6Network((parsed, 64), strict=False).network_address)
    return str(parsed)


def ip_hash(secret: bytes, address: str) -> bytes:
    return hmac.new(secret, f"ip:{address}".encode("utf-8"), hashlib.sha256).digest()


def ua_hash(secret: bytes, user_agent: str) -> bytes:
    return hmac.new(secret, f"ua:{user_agent[:512]}".encode("utf-8"), hashlib.sha256).digest()


def origin_allowed(request: Request, public_origin: str) -> bool:
    origin = request.headers.get("origin")
    if origin is not None and origin.rstrip("/") != public_origin:
        return False
    site = request.headers.get("sec-fetch-site")
    if site is not None and site not in {"same-origin", "none"}:
        return False
    return True


def matches(value: str | None, expected: str) -> bool:
    return isinstance(value, str) and bool(value) and hmac.compare_digest(value, expected)


API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"
# Mirrors deploy/Caddyfile. The PWA has no inline scripts/styles, so 'self' is enough.
STATIC_CSP = (
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
    "connect-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
    "form-action 'self'"
)


def security_headers(settings: Settings) -> dict[str, str]:
    headers = {
        "X-Content-Type-Options": "nosniff",
        "Referrer-Policy": "same-origin",
        "X-Frame-Options": "DENY",
        "Content-Security-Policy": API_CSP,
        "Cross-Origin-Resource-Policy": "same-origin",
        "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    }
    if settings.https:
        headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    return headers


def static_security_headers(settings: Settings) -> dict[str, str]:
    """Headers for the PWA when the app serves static files itself (SERVE_STATIC).

    Production serves `web/` from Caddy with the same policy; the API policy is
    deliberately not applied here or the PWA assets would be blocked.
    """
    headers = security_headers(settings)
    headers["Content-Security-Policy"] = STATIC_CSP
    return headers


def normalized_route(request: Request) -> str:
    route = request.scope.get("route")
    path = getattr(route, "path", "") if route is not None else ""
    return path if isinstance(path, str) and _LABEL.fullmatch(path.replace("/", "_").replace("{", "_").replace("}", "_")) else "unmatched"


class RateLimitBackend(Protocol):
    async def incr(self, key: str, window: int) -> int: ...

    async def close(self) -> None: ...


class RedisRateLimiter:
    def __init__(self, url: str):
        self.client = redis_asyncio.Redis.from_url(
            url, decode_responses=False, socket_timeout=3, socket_connect_timeout=3,
        )
        self._script = (
            "local c = redis.call('incr', KEYS[1]); "
            "if c == 1 then redis.call('expire', KEYS[1], ARGV[1]) end; "
            "return c"
        )

    async def incr(self, key: str, window: int) -> int:
        return int(await self.client.eval(self._script, 1, f"upcp:rl:{key}", window))

    async def close(self) -> None:
        await self.client.aclose()


class MemoryRateLimiter:
    def __init__(self):
        self._lock = asyncio.Lock()
        self._counters: dict[str, tuple[int, float]] = {}

    async def incr(self, key: str, window: int) -> int:
        now = time.monotonic()
        async with self._lock:
            count, reset = self._counters.get(key, (0, now + window))
            if reset <= now:
                count, reset = 0, now + window
            count += 1
            self._counters[key] = (count, reset)
            if len(self._counters) > 20000:
                self._counters = {
                    name: value for name, value in self._counters.items() if value[1] > now
                }
            return count

    async def close(self) -> None:
        return None


class RateLimiter:
    def __init__(self, backend: RateLimitBackend, enabled: bool):
        self.backend = backend
        self.enabled = enabled

    async def allow(self, bucket: str, key: str, limit: int, window: int) -> bool:
        if not self.enabled:
            return True
        count = await self.backend.incr(f"{bucket}:{key}", window)
        return count <= limit

    async def close(self) -> None:
        await self.backend.close()


def build_rate_limiter(settings: Settings) -> RateLimiter:
    backend: RateLimitBackend
    if settings.redis_url:
        backend = RedisRateLimiter(settings.redis_url)
    else:
        backend = MemoryRateLimiter()
    return RateLimiter(backend, settings.rate_limit_enabled)
