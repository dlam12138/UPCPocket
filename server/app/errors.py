"""Fixed public error codes. Upstream free text never reaches a response."""

from __future__ import annotations


class ApiError(Exception):
    def __init__(self, status: int, code: str):
        self.status = status
        self.code = code
        super().__init__(code)


ERROR_CODES = {
    400: "invalid_request",
    401: "session_expired",
    403: "invalid_request",
    404: "unknown_route",
    405: "invalid_request",
    409: "conflict",
    413: "invalid_request",
    415: "invalid_request",
    429: "rate_limited",
    500: "internal",
    502: "upstream_unavailable",
    503: "upstream_unavailable",
}
