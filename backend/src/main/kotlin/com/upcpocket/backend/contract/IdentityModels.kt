package com.upcpocket.backend.contract

import java.security.MessageDigest
import java.util.UUID

enum class AccountState {
    ACTIVE,
    REAUTH_REQUIRED,
    DISABLED,
}

enum class DevicePlatform {
    ANDROID,
    IOS,
    OTHER,
}

enum class DeviceKind {
    PHONE,
    TABLET,
    OTHER,
}

class DeviceDescriptor(
    val installationId: UUID,
    val platform: DevicePlatform,
    val kind: DeviceKind,
) {
    override fun toString(): String = "DeviceDescriptor(platform=$platform, kind=$kind)"
}

enum class AuditActionCode {
    LOOKUP,
    DISABLE,
    RESTORE,
    REVOKE_ALL,
    ROTATE_KEY,
    READ_AUDIT,
    UNBIND,
    TRIP_REAUTH,
    UPDATE_CREDENTIAL,
}

enum class AuditResultCode {
    OK,
    DENIED,
    CONFLICT,
    FAILED,
    PARTIAL,
}

/** A fixed-width blind index. The original bytes are copied and never included in text output. */
class LoginTag(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == BYTE_LENGTH) { "LoginTag must be exactly $BYTE_LENGTH bytes" }
    }

    fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LoginTag && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "LoginTag([REDACTED])"

    companion object {
        const val BYTE_LENGTH: Int = 32
    }
}
