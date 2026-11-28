package com.upcpocket.backend.service

import java.util.concurrent.CompletableFuture

enum class ApiErrorCode(val httpStatus: Int) {
    VALIDATION_FAILED(400),
    UPSTREAM_PROTOCOL_ERROR(502),
    CUSTODY_ACCEPTANCE_REQUIRED(400),
    IDENTITY_UNSUPPORTED(422),
    SIGN_IN_FAILED(401),
    UNAUTHENTICATED(401),
    TOKEN_INVALID(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    VERIFICATION_BUSY(409),
    INTERACTIVE_CHALLENGE(409),
    CREDENTIAL_UPDATE_REQUIRED(409),
    KEY_VERSION_CONFLICT(409),
    RATE_LIMITED(429),
    REVISION_CHANGED(412),
    UPSTREAM_TIMEOUT(504),
    MODULE_DISABLED(503),
    CIRCUIT_OPEN(503),
    UPSTREAM_UNAVAILABLE(503),
    DEPENDENCY_UNAVAILABLE(503),
    INTERNAL_ERROR(500),
}

class ApiException(
    val code: ApiErrorCode,
    val retryAfterSeconds: Long? = null,
    internal val completionSignal: CompletableFuture<*>? = null,
) : RuntimeException()
