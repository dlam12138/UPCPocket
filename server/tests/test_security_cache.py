"""Security helpers and public cache semantics."""

from __future__ import annotations

import asyncio
import time

import pytest
from starlette.requests import Request

from app.cache import MemoryCache, PublicCache, cache_key
from app.crypto import CryptoError
from app.security import (
    MemoryRateLimiter, RateLimiter, matches, normalized_route, origin_allowed, security_headers,
)
from app.config import Settings


def request(headers: dict[str, str]) -> Request:
    raw = [(key.lower().encode(), value.encode()) for key, value in headers.items()]
    return Request({"type": "http", "method": "POST", "headers": raw, "client": ("1.2.3.4", 1)})


def test_forwarded_for_uses_last_proxy_address(settings):
    from dataclasses import replace

    from app.security import client_ip

    proxied = replace(settings, trust_proxy=True)
    # A single Caddy writes the real client; any client-supplied prefix is ignored.
    spoofed = request({"x-forwarded-for": "6.6.6.6, 203.0.113.9"})
    assert client_ip(spoofed, proxied) == "203.0.113.9"
    single = request({"x-forwarded-for": "203.0.113.9"})
    assert client_ip(single, proxied) == "203.0.113.9"
    direct = replace(settings, trust_proxy=False)
    assert client_ip(spoofed, direct) == "1.2.3.4"
    from app.security import network_key

    assert network_key("2001:db8:1:2:3:4:5:6") == "2001:db8:1:2::"
    assert network_key("2001:db8:1:2:ffff::1") == "2001:db8:1:2::"
    assert network_key("203.0.113.9") == "203.0.113.9"
    assert network_key("not-an-ip") == "not-an-ip"


def test_origin_allowed():
    assert origin_allowed(request({}), "https://pocket.example")
    assert origin_allowed(request({"origin": "https://pocket.example"}), "https://pocket.example")
    assert origin_allowed(request({"sec-fetch-site": "same-origin"}), "https://pocket.example")
    assert not origin_allowed(request({"origin": "https://evil.example"}), "https://pocket.example")
    assert not origin_allowed(request({"sec-fetch-site": "cross-site"}), "https://pocket.example")


def test_matches_is_constant_time_and_exact():
    assert matches("abc", "abc")
    assert not matches("abc", "abd")
    assert not matches("", "abc")
    assert not matches(None, "abc")


def test_rate_limiter_memory():
    limiter = RateLimiter(MemoryRateLimiter(), enabled=True)
    assert asyncio.run(limiter.allow("login_ip", "x", 2, 60))
    assert asyncio.run(limiter.allow("login_ip", "x", 2, 60))
    assert not asyncio.run(limiter.allow("login_ip", "x", 2, 60))
    disabled = RateLimiter(MemoryRateLimiter(), enabled=False)
    for _ in range(10):
        assert asyncio.run(disabled.allow("login_ip", "x", 1, 60))


def test_cache_key_is_stable():
    params = {"building": "b1", "date": "2026-11-23", "period": ["1", "2"]}
    assert cache_key("classrooms", params) == cache_key("classrooms", dict(reversed(list(params.items()))))
    assert cache_key("classrooms", params) != cache_key("classrooms", {**params, "period": ["1"]})
    assert cache_key("calendar") == "calendar"


def test_cache_stale_fallback(settings):
    async def scenario():
        cache = PublicCache(MemoryCache(), settings)
        calls = {"count": 0}

        async def loader():
            calls["count"] += 1
            return {"items": [1, 2]}

        value, state = await cache.fetch("k", loader, ttl=60)
        assert value == {"items": [1, 2]} and state == "miss"
        value, state = await cache.fetch("k", loader, ttl=60)
        assert state == "hit" and calls["count"] == 1

        async def failing():
            calls["count"] += 1
            raise RuntimeError("upstream down")

        cache.backend._values.pop("k", None)
        value, state = await cache.fetch("k", failing, ttl=60)
        assert state == "stale" and value == {"items": [1, 2]}
        assert calls["count"] == 2

        cache.backend._values.pop("k", None)
        cache.backend._values.pop("k:stale", None)

        with pytest.raises(RuntimeError):
            await cache.fetch("k", failing, ttl=60)

    asyncio.run(scenario())


def test_pool_timeout_keeps_permit_until_thread_finishes():
    from app.upstream.pool import UpstreamPool, UpstreamUnavailable

    pool = UpstreamPool(concurrency=1, timeout=0.05)

    def slow():
        time.sleep(0.3)
        return "slow"

    async def scenario():
        with pytest.raises(UpstreamUnavailable):
            await pool.run("group", slow)
        started = time.monotonic()
        # The abandoned thread still holds the only permit, so a new call waits
        # for it instead of stacking unbounded blocking threads.
        assert await pool.run("group", lambda: "fast") == "fast"
        assert time.monotonic() - started >= 0.15

    asyncio.run(scenario())


def test_security_headers(settings):
    headers = security_headers(settings)
    assert headers["X-Content-Type-Options"] == "nosniff"
    assert "frame-ancestors 'none'" in headers["Content-Security-Policy"]
    assert "Strict-Transport-Security" not in headers  # http test origin
    https = Settings.from_env({
        "ENV": "development",
        "PUBLIC_ORIGIN": "https://pocket.example",
        "SERVE_STATIC": "false",
        "ALLOW_EPHEMERAL_KEYS": "true",
    })
    assert https.cookie_name == "__Host-upcp_session"
    assert security_headers(https)["Strict-Transport-Security"].startswith("max-age=31536000")
