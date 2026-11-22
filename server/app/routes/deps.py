"""Shared route helpers."""

from __future__ import annotations

import json
from typing import Any

from fastapi import Request

from ..errors import ApiError
from ..library.service import AuditContext
from ..runtime import Resources
from ..security import client_ip, ip_hash, matches, ua_hash
from ..sessions import BrowserSession


def resources(request: Request) -> Resources:
    return request.app.state.resources


async def load_session(request: Request) -> BrowserSession | None:
    res = resources(request)
    token = request.cookies.get(res.settings.cookie_name, "")
    if not token or len(token) > 128:
        return None
    return await res.store.get_browser(token)


async def require_session(request: Request) -> BrowserSession:
    session = await load_session(request)
    if session is None:
        raise ApiError(401, "session_expired")
    return session


def require_revision(request: Request, session: BrowserSession) -> None:
    if not matches(request.headers.get("x-library-session"), session.revision):
        raise ApiError(409, "library_session_changed")


def require_csrf(request: Request, session: BrowserSession) -> None:
    if not matches(request.headers.get("x-csrf-token"), session.csrf):
        raise ApiError(403, "invalid_request")


def audit_context(request: Request) -> AuditContext:
    settings = resources(request).settings
    address = client_ip(request, settings)
    return AuditContext(
        ip_hash=ip_hash(settings.session_secret, address),
        ua_hash=ua_hash(settings.session_secret, request.headers.get("user-agent", "")),
    )


async def read_json_body(request: Request, *, limit: int = 8192) -> dict[str, Any]:
    media = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if media != "application/json":
        raise ApiError(415, "invalid_request")
    raw = await request.body()
    if len(raw) > limit or not raw:
        raise ApiError(413, "invalid_request")
    try:
        value = json.loads(raw)
    except (ValueError, RecursionError):
        raise ApiError(400, "invalid_request") from None
    if not isinstance(value, dict):
        raise ApiError(400, "invalid_request")
    return value
