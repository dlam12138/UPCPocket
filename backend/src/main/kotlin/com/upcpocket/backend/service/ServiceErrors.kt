package com.upcpocket.backend.service

enum class ApiErrorCode(val httpStatus: Int) {
    VALIDATION_FAILED(400),
    CUSTODY_ACCEPTANCE_REQUIRED(400),
    SIGN_IN_FAILED(401),
    UNAUTHENTICATED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    VERIFICATION_BUSY(409),
    INTERACTIVE_CHALLENGE(409),
    CREDENTIAL_UPDATE_REQUIRED(409),
    KEY_VERSION_CONFLICT(409),
    RATE_LIMITED(429),
    REVISION_CHANGED(412),
    UPSTREAM_UNAVAILABLE(503),
    DEPENDENCY_UNAVAILABLE(503),
    INTERNAL_ERROR(500),
}

class ApiException(
    val code: ApiErrorCode,
    val retryAfterSeconds: Long? = null,
) : RuntimeException()
