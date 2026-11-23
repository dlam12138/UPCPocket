"""Per-branch library endpoints. Paths keep the contract of the local server."""

from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from ..errors import ApiError
from ..library.service import LibraryServiceError
from .deps import (
    audit_context, load_session, read_json_body, require_csrf, require_revision,
    require_session, resources,
)


router = APIRouter(prefix="/api/library")
BRANCH_NAMES = {"east", "qiushi"}
LIBRARY_LIMITS = {
    "session": (120, 60),
    "catalog": (120, 60),
    "reservations": (30, 60),
    "book": (10, 60),
    "cancel": (10, 60),
    "logout": (10, 60),
}
ANCHORS = {"tangdaowan": "#library", "east": "#library-east", "qiushi": "#library-qiushi"}


def _require_station(request: Request) -> None:
    if not resources(request).settings.station_enabled:
        raise ApiError(403, "station_disabled")


def _branch(value: str | None) -> str:
    if value is None:
        return "tangdaowan"
    if value in BRANCH_NAMES:
        return value
    raise ApiError(404, "unknown_route")


async def _user_allow(request: Request, session, bucket: str) -> None:
    res = resources(request)
    limit, window = LIBRARY_LIMITS[bucket]
    if not await res.limiter.allow(f"library_{bucket}", session.user_id, limit, window):
        raise ApiError(429, "rate_limited")


async def _session_state(request: Request, branch: str) -> JSONResponse:
    res = resources(request)
    if not res.settings.station_enabled or not res.settings.booking_enabled:
        return JSONResponse({"state": "unavailable"})
    session = await load_session(request)
    if session is None:
        return JSONResponse({"state": "sign_in"})
    await _user_allow(request, session, "session")
    try:
        return JSONResponse(await res.library_service.state(session, branch))
    except LibraryServiceError as error:
        raise ApiError(error.status, error.code) from None


def _library_params(request: Request) -> dict[str, list[str]]:
    total = 0
    params: dict[str, list[str]] = {}
    for key, value in request.query_params.multi_items():
        if len(value) > 128:
            raise ApiError(400, "invalid_request")
        params.setdefault(key, []).append(value)
        total += 1
    if total > 6:
        raise ApiError(400, "invalid_request")
    return params


async def _catalog(request: Request, branch: str) -> JSONResponse:
    _require_station(request)
    res = resources(request)
    session = await require_session(request)
    require_revision(request, session)
    await _user_allow(request, session, "catalog")
    try:
        return JSONResponse(
            await res.library_service.catalog(session, branch, _library_params(request)),
        )
    except LibraryServiceError as error:
        raise ApiError(error.status, error.code) from None


async def _reservations(request: Request, branch: str) -> JSONResponse:
    _require_station(request)
    res = resources(request)
    session = await require_session(request)
    require_revision(request, session)
    await _user_allow(request, session, "reservations")
    params = _library_params(request)
    if set(params) - {"attempt", "cancelAttempt"} or any(len(values) != 1 for values in params.values()):
        raise ApiError(400, "invalid_request")
    try:
        return JSONResponse(await res.library_service.reservations(
            session, branch, params.get("attempt", [None])[0], params.get("cancelAttempt", [None])[0],
        ))
    except LibraryServiceError as error:
        raise ApiError(error.status, error.code) from None


async def _book(request: Request, branch: str) -> JSONResponse:
    _require_station(request)
    res = resources(request)
    session = await require_session(request)
    require_revision(request, session)
    require_csrf(request, session)
    await _user_allow(request, session, "book")
    payload = await read_json_body(request, limit=4096)
    try:
        return JSONResponse(
            await res.library_service.book(session, branch, payload, audit_context(request)),
        )
    except LibraryServiceError as error:
        raise ApiError(error.status, error.code) from None


async def _cancel(request: Request, branch: str) -> JSONResponse:
    _require_station(request)
    res = resources(request)
    session = await require_session(request)
    require_revision(request, session)
    require_csrf(request, session)
    await _user_allow(request, session, "cancel")
    payload = await read_json_body(request, limit=4096)
    try:
        return JSONResponse(
            await res.library_service.cancel(session, branch, payload, audit_context(request)),
        )
    except LibraryServiceError as error:
        raise ApiError(error.status, error.code) from None


async def _logout(request: Request, branch: str) -> JSONResponse:
    _require_station(request)
    res = resources(request)
    session = await require_session(request)
    require_revision(request, session)
    require_csrf(request, session)
    await _user_allow(request, session, "logout")
    try:
        await res.library_service.logout(session, branch, audit_context(request))
    except LibraryServiceError as error:
        raise ApiError(error.status, error.code) from None
    return JSONResponse({"state": "sign_in"})


# -- Tangdaowan (no branch segment) ------------------------------------


@router.get("/session")
async def session_default(request: Request):
    return await _session_state(request, "tangdaowan")


@router.get("/catalog")
async def catalog_default(request: Request):
    return await _catalog(request, "tangdaowan")


@router.get("/reservations")
async def reservations_default(request: Request):
    return await _reservations(request, "tangdaowan")


@router.post("/book")
async def book_default(request: Request):
    return await _book(request, "tangdaowan")


@router.post("/cancel")
async def cancel_default(request: Request):
    return await _cancel(request, "tangdaowan")


@router.post("/logout")
async def logout_default(request: Request):
    return await _logout(request, "tangdaowan")


@router.get("/login")
async def login_default():
    return RedirectResponse(f"/?library=1{ANCHORS['tangdaowan']}", status_code=303)


# -- Chaoxing branches -------------------------------------------------


@router.get("/{branch}/session")
async def session_branch(request: Request, branch: str):
    return await _session_state(request, _branch(branch))


@router.get("/{branch}/catalog")
async def catalog_branch(request: Request, branch: str):
    return await _catalog(request, _branch(branch))


@router.get("/{branch}/reservations")
async def reservations_branch(request: Request, branch: str):
    return await _reservations(request, _branch(branch))


@router.post("/{branch}/book")
async def book_branch(request: Request, branch: str):
    return await _book(request, _branch(branch))


@router.post("/{branch}/cancel")
async def cancel_branch(request: Request, branch: str):
    return await _cancel(request, _branch(branch))


@router.post("/{branch}/logout")
async def logout_branch(request: Request, branch: str):
    return await _logout(request, _branch(branch))


@router.get("/{branch}/login")
async def login_branch(branch: str):
    return RedirectResponse(
        f"/?library=1{ANCHORS[_branch(branch)]}", status_code=303,
    )
