"""FastAPI application for the multi-user UPC Pocket web service."""

from __future__ import annotations

import logging
import re
import time
import uuid
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.datastructures import MutableHeaders
from starlette.exceptions import HTTPException as StarletteHTTPException

from .config import Settings
from .errors import ERROR_CODES, ApiError
from .library.service import LibraryServiceError
from .crypto import KeyRing
from .runtime import Resources, build_resources, run_blocking
from .security import origin_allowed, security_headers, static_security_headers
from .sessions import SessionBackend
from .library.journal import Database
from .cache import PublicCache
from .security import RateLimiter
from .upstream.bridge import Upstream
from .upstream.pool import UpstreamPool
from .routes import auth as auth_routes
from .routes import library as library_routes
from .routes import public as public_routes


logger = logging.getLogger("upcp.access")
MAX_LOGIN_BODY = 8192
MAX_API_BODY = 16384


class BodyTooLarge(Exception):
    pass


class GuardMiddleware:
    """Origin guard, API body limit, security headers and fixed-field logs."""

    def __init__(self, app, settings: Settings):
        self.app = app
        self.settings = settings
        self.api_headers = security_headers(settings)
        self.static_headers = static_security_headers(settings)

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        method = scope.get("method", "GET")
        path = scope.get("path", "")
        request_id = uuid.uuid4().hex[:16]
        scope.setdefault("state", {})["request_id"] = request_id
        started = time.monotonic()
        status = {"code": 0}
        started_response = {"value": False}

        async def send_wrapper(message):
            if message["type"] == "http.response.start":
                started_response["value"] = True
                status["code"] = message["status"]
                headers = MutableHeaders(scope=message)
                policy = self.api_headers if (
                    path.startswith("/api/") or path == "/healthz"
                ) else self.static_headers
                for key, value in policy.items():
                    headers[key] = value
                headers["X-Request-Id"] = request_id
                if path.startswith("/api/"):
                    headers["Cache-Control"] = "no-store"
            await send(message)

        async def reject(code: int) -> None:
            response = JSONResponse({"error": ERROR_CODES.get(code, "invalid_request")}, status_code=code)
            await response(scope, receive, send_wrapper)

        try:
            if method not in {"GET", "HEAD", "OPTIONS"}:
                if not origin_allowed(Request(scope, receive), self.settings.public_origin):
                    await reject(403)
                    return
                if path.startswith("/api/") and any(
                    name == b"transfer-encoding" for name, _ in scope["headers"]
                ):
                    await reject(400)
                    return
            limited = method == "POST" and path.startswith("/api/")
            body_limit = MAX_LOGIN_BODY if path == "/api/auth/login" else MAX_API_BODY
            received = 0

            async def limited_receive():
                nonlocal received
                message = await receive()
                if message["type"] == "http.request":
                    received += len(message.get("body", b""))
                    if limited and received > body_limit:
                        raise BodyTooLarge()
                return message

            await self.app(scope, limited_receive, send_wrapper)
        except BodyTooLarge:
            if not started_response["value"]:
                await reject(413)
        except Exception as error:  # noqa: BLE001 - fixed response, type-only log
            logger.error(
                "unhandled request error request_id=%s type=%s", request_id, type(error).__name__,
            )
            if not started_response["value"]:
                await reject(500)
        finally:
            elapsed_ms = int((time.monotonic() - started) * 1000)
            logger.info(
                "request request_id=%s method=%s path=%s status=%s duration_ms=%d",
                request_id, method, _fixed_path(path), status["code"], elapsed_ms,
            )


def _fixed_path(path: str) -> str:
    """Collapse arbitrary path segments so query strings and values never log."""
    path = re.sub(r"[^\x21-\x7e]", "?", path.split("?", 1)[0])[:256]
    parts = path.split("/")
    if len(parts) > 3 and parts[1] == "api" and parts[2] == "library":
        fixed = ["", "api", "library"]
        if len(parts) >= 5 and parts[3] not in {"session", "catalog", "reservations", "book", "cancel", "logout", "login"}:
            fixed.append("{branch}")
            fixed.extend(parts[4:])
        else:
            fixed.extend(parts[3:])
        return "/".join(fixed)
    return path


def create_app(
    settings: Settings | None = None,
    *,
    upstream: Upstream | None = None,
    store: SessionBackend | None = None,
    database: Database | None = None,
    cache: PublicCache | None = None,
    limiter: RateLimiter | None = None,
    pool: UpstreamPool | None = None,
    login_pool: UpstreamPool | None = None,
    keyring: KeyRing | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    if not logging.getLogger().handlers:
        logging.basicConfig(
            level=settings.log_level,
            format="%(levelname)s %(name)s %(message)s",
        )
    logging.getLogger("upcp").setLevel(settings.log_level)
    resources = build_resources(
        settings, upstream=upstream, store=store, database=database,
        cache=cache, limiter=limiter, pool=pool, login_pool=login_pool, keyring=keyring,
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if settings.db_auto_migrate:
            await run_blocking(resources.database.ensure_schema)
        try:
            await run_blocking(
                resources.database.cleanup,
                settings.retention_attempts_days, settings.retention_audit_days,
            )
        except Exception as error:  # noqa: BLE001 - retention must not block startup
            logger.warning("retention cleanup failed: %s", type(error).__name__)
        yield
        await resources.close()

    logger.info(
        "service configured env=%s static=%s redis=%s database=%s booking=%s school=%s",
        settings.env, settings.serve_static, bool(settings.redis_url),
        bool(settings.database_url), settings.booking_enabled, settings.school_access_mode,
    )
    app = FastAPI(
        title="UPC Pocket Web",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    app.state.resources = resources
    app.add_middleware(GuardMiddleware, settings=settings)

    @app.exception_handler(ApiError)
    async def api_error_handler(request: Request, error: ApiError):
        return JSONResponse({"error": error.code}, status_code=error.status)

    @app.exception_handler(LibraryServiceError)
    async def library_error_handler(request: Request, error: LibraryServiceError):
        return JSONResponse({"error": error.code}, status_code=error.status)

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, error: RequestValidationError):
        return JSONResponse({"error": "invalid_request"}, status_code=400)

    @app.exception_handler(StarletteHTTPException)
    async def http_error_handler(request: Request, error: StarletteHTTPException):
        code = ERROR_CODES.get(error.status_code, "unknown_route")
        return JSONResponse({"error": code}, status_code=error.status_code)

    @app.exception_handler(Exception)
    async def unexpected_handler(request: Request, error: Exception):
        request_id = getattr(request.state, "request_id", "")
        logger.error(
            "unhandled application error request_id=%s type=%s", request_id, type(error).__name__,
        )
        return JSONResponse({"error": "internal"}, status_code=500)

    @app.get("/healthz")
    async def healthz():
        checks: dict[str, bool] = {"keys": True}
        try:
            checks["sessions"] = bool(await resources.store.ping())
        except Exception:  # noqa: BLE001
            checks["sessions"] = False
        try:
            checks["database"] = bool(await run_blocking(resources.database.ping))
        except Exception:  # noqa: BLE001
            checks["database"] = False
        healthy = all(checks.values())
        return JSONResponse(
            {"status": "ok" if healthy else "degraded", "checks": checks},
            status_code=200 if healthy else 503,
        )

    app.include_router(public_routes.router)
    app.include_router(auth_routes.router)
    app.include_router(library_routes.router)

    @app.api_route("/api/{rest:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE"])
    async def unknown_api(rest: str):
        return JSONResponse({"error": "unknown_route"}, status_code=404)

    if settings.serve_static:
        if not settings.web_root.is_dir():
            raise RuntimeError(f"web root is missing: {settings.web_root}")
        app.mount("/", StaticFiles(directory=str(settings.web_root), html=True), name="static")

    return app
