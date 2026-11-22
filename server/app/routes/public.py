"""Public campus data routes backed by the Redis cache."""

from __future__ import annotations

import re

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from ..cache import cache_key
from ..errors import ApiError
from ..runtime import Resources
from ..upstream.pool import UpstreamUnavailable
from .deps import resources


router = APIRouter(prefix="/api")

CLASSROOM_FIELDS = {"campus", "date", "building", "period"}
GUIDE_ID = re.compile(r"[1-9][0-9]{0,18}", re.ASCII)


def _pairs(request: Request, *, allowed: set[str], max_values: int = 18) -> dict[str, list[str]]:
    pairs: dict[str, list[str]] = {}
    total = 0
    for key, value in request.query_params.multi_items():
        if key not in allowed:
            raise ApiError(400, "invalid_request")
        if not 1 <= len(value) <= 128:
            raise ApiError(400, "invalid_request")
        pairs.setdefault(key, []).append(value)
        total += 1
    if total > max_values:
        raise ApiError(400, "invalid_request")
    return pairs


async def _upstream_call(res: Resources, group: str, function, *args, error_types):
    try:
        return await res.pool.run(group, function, *args, expected=error_types)
    except error_types as error:
        raise ApiError(
            int(getattr(error, "status", 502)), str(getattr(error, "code", "upstream_unavailable")),
        ) from None
    except UpstreamUnavailable:
        raise ApiError(503, "upstream_unavailable") from None


async def _cached(
    res: Resources, key: str, loader, ttl: int,
) -> JSONResponse:
    payload, state = await res.cache.fetch(key, loader, ttl=ttl)
    headers = {"Cache-Control": "no-store", "X-Cache": state}
    if isinstance(payload, dict):
        if state == "stale":
            payload = {**payload, "stale": True}
    return JSONResponse(payload, headers=headers)


@router.get("/classrooms")
async def classrooms(request: Request):
    res = resources(request)
    params = _pairs(request, allowed=CLASSROOM_FIELDS)
    if set(params) != CLASSROOM_FIELDS:
        raise ApiError(400, "invalid_request")

    async def loader():
        return await _upstream_call(
            res, "public:classrooms", res.upstream.query_classrooms, params,
            error_types=(res.upstream.ClassroomError,),
        )

    return await _cached(
        res, cache_key("classrooms", params), loader, res.settings.classrooms_cache_ttl,
    )


@router.get("/calendar")
async def calendar(request: Request):
    res = resources(request)
    if request.query_params:
        raise ApiError(400, "invalid_request")

    async def loader():
        return await _upstream_call(
            res, "public:calendar", res.upstream.query_calendar,
            error_types=(res.upstream.CalendarError,),
        )

    return await _cached(res, "calendar", loader, res.settings.public_cache_ttl)


@router.get("/service-catalog")
async def service_catalog(request: Request):
    res = resources(request)
    if request.query_params:
        raise ApiError(400, "invalid_request")

    async def loader():
        return await _upstream_call(
            res, "public:catalog", res.upstream.query_service_catalog,
            error_types=(res.upstream.ServiceCatalogError,),
        )

    return await _cached(res, "service-catalog", loader, res.settings.public_cache_ttl)


@router.get("/service-catalog/guide")
async def service_guide(request: Request):
    res = resources(request)
    pairs = _pairs(request, allowed={"id"}, max_values=1)
    if set(pairs) != {"id"} or len(pairs["id"]) != 1 or not GUIDE_ID.fullmatch(pairs["id"][0]):
        raise ApiError(400, "invalid_request")
    identifier = pairs["id"][0]

    async def loader():
        return await _upstream_call(
            res, "public:guide", res.upstream.query_service_guide, identifier,
            error_types=(res.upstream.ServiceCatalogError,),
        )

    return await _cached(
        res, cache_key("service-catalog-guide", {"id": [identifier]}), loader,
        res.settings.public_cache_ttl,
    )
