package com.upcpocket.backend.http

import com.upcpocket.backend.contract.DeviceKind
import com.upcpocket.backend.contract.DevicePlatform
import com.upcpocket.backend.contract.AccountState
import java.time.Instant
import java.util.UUID

class SignInRequest(
    val loginName: CharArray,
    val password: CharArray,
    val acceptCustody: Boolean,
    val installationId: UUID,
    val platform: DevicePlatform,
    val kind: DeviceKind,
) {
    override fun toString(): String = "SignInRequest([REDACTED])"
}

class CredentialUpdateRequest(
    val loginName: CharArray,
    val password: CharArray,
) {
    override fun toString(): String = "CredentialUpdateRequest([REDACTED])"
}

class AccountLookupRequest(val loginName: CharArray) {
    override fun toString(): String = "AccountLookupRequest([REDACTED])"
}

data class SessionResponse(val token: String, val deviceId: UUID, val expiresAt: Instant) {
    override fun toString(): String = "SessionResponse(deviceId=$deviceId, token=[REDACTED])"
}

data class StudentAccountResponse(
    val accountId: UUID,
    val state: AccountState,
    val credentialGeneration: Long,
    val gateRevision: Long,
)

data class DeviceResponse(
    val deviceId: UUID,
    val installationId: UUID,
    val platform: DevicePlatform,
    val kind: DeviceKind,
    val expiresAt: Instant,
)

data class AdminAccountResponse(
    val accountId: UUID,
    val state: AccountState,
    val credentialGeneration: Long,
    val gateRevision: Long,
)

data class KeyRotationResponse(
    val writeVersion: String,
    val rewrapped: Int,
    val remaining: Long,
    val complete: Boolean,
)

data class HealthResponse(
    val database: Boolean,
    val migrations: Boolean,
    val masterKeyProvider: Boolean,
    val casVerifier: Boolean,
)

data class AuditEventResponse(
    val eventId: UUID,
    val occurredAt: Instant,
    val requestId: UUID,
    val actionCode: String,
    val resultCode: String,
)

data class AuditPageResponse(val items: List<AuditEventResponse>, val nextCursor: String?)

data class AdminLookupResponse(val account: AdminAccountResponse?)

data class ErrorResponse(val code: String, val requestId: UUID)

data class DisabledRequest(val disabled: Boolean)
