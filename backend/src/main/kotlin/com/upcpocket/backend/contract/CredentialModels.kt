package com.upcpocket.backend.contract

/**
 * Owns the supplied character array. It is intentionally not thread-safe; the owner must keep it
 * within one operation and close it as soon as the characters are no longer needed.
 */
class SensitiveCharBuffer(private val value: CharArray) : AutoCloseable {
    private var closed = false

    fun <T> use(block: (CharArray) -> T): T {
        check(!closed) { "Sensitive buffer is closed" }
        return block(value)
    }

    override fun close() {
        if (!closed) {
            value.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "SensitiveCharBuffer([REDACTED])"
}

/**
 * Owns the supplied arrays for one credential use. Closing immediately zeroes both arrays,
 * including when they are the same array instance; callers must not retain either reference.
 */
class CredentialLease(
    val loginName: CharArray,
    val password: CharArray,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (!closed) {
            loginName.fill('\u0000')
            password.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "CredentialLease([REDACTED])"
}

enum class CredentialUsePurpose {
    VERIFY_MANUAL_SIGN_IN_COMPARE,
    AUTO_BUSINESS_LOGIN,
}

sealed interface CasVerificationResult {
    /** Owns and clears the normalized login name when closed. */
    class Verified(val normalizedLoginName: CharArray) : CasVerificationResult, AutoCloseable {
        private var closed = false

        override fun close() {
            if (!closed) {
                normalizedLoginName.fill('\u0000')
                closed = true
            }
        }

        override fun toString(): String = "CasVerificationResult.Verified([REDACTED])"
    }

    object InvalidCredentials : CasVerificationResult

    object InteractiveChallenge : CasVerificationResult

    object UpstreamUnavailable : CasVerificationResult
}
