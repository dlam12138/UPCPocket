package com.upcpocket.backend.contract

import java.util.UUID

/** Encrypted credential envelope. Byte arrays are defensively copied on input and output. */
class EncryptedEnvelope(
    val format: Short,
    payloadNonce: ByteArray,
    payloadCiphertext: ByteArray,
    val keyVersion: String,
    wrappedDek: ByteArray,
) {
    private val nonceValue = payloadNonce.copyOf()
    private val ciphertextValue = payloadCiphertext.copyOf()
    private val wrappedDekValue = wrappedDek.copyOf()

    val payloadNonce: ByteArray
        get() = nonceValue.copyOf()

    val payloadCiphertext: ByteArray
        get() = ciphertextValue.copyOf()

    val wrappedDek: ByteArray
        get() = wrappedDekValue.copyOf()

    init {
        require(format > 0) { "Envelope format must be positive" }
        require(nonceValue.size == GCM_NONCE_LENGTH) { "Envelope nonce must be $GCM_NONCE_LENGTH bytes" }
        require(ciphertextValue.size >= GCM_TAG_LENGTH) { "Envelope ciphertext is shorter than its authentication tag" }
        require(keyVersion.isNotBlank() && keyVersion.length <= KEY_VERSION_MAX_LENGTH) {
            "Key version must contain 1..$KEY_VERSION_MAX_LENGTH characters"
        }
        require(wrappedDekValue.isNotEmpty()) { "Wrapped DEK cannot be empty" }
    }

    override fun equals(other: Any?): Boolean =
        other is EncryptedEnvelope &&
            format == other.format &&
            keyVersion == other.keyVersion &&
            nonceValue.contentEquals(other.nonceValue) &&
            ciphertextValue.contentEquals(other.ciphertextValue) &&
            wrappedDekValue.contentEquals(other.wrappedDekValue)

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + nonceValue.contentHashCode()
        result = 31 * result + ciphertextValue.contentHashCode()
        result = 31 * result + keyVersion.hashCode()
        result = 31 * result + wrappedDekValue.contentHashCode()
        return result
    }

    override fun toString(): String = "EncryptedEnvelope([REDACTED])"

    companion object {
        const val CURRENT_FORMAT: Short = 1
        const val GCM_NONCE_LENGTH: Int = 12
        const val GCM_TAG_LENGTH: Int = 16
        const val KEY_VERSION_MAX_LENGTH: Int = 64
    }
}

data class AccountRecord(
    val accountId: UUID,
    val loginTag: LoginTag,
    val disabled: Boolean,
    val reauthRequired: Boolean,
    val credentialGeneration: Long,
    val gateRevision: Long,
    val pendingAttemptId: UUID?,
    val envelope: EncryptedEnvelope,
) {
    init {
        require(credentialGeneration > 0) { "Credential generation must be positive" }
        require(gateRevision > 0) { "Gate revision must be positive" }
    }

    fun effectiveState(): AccountState = when {
        disabled -> AccountState.DISABLED
        reauthRequired -> AccountState.REAUTH_REQUIRED
        else -> AccountState.ACTIVE
    }

    override fun toString(): String =
        "AccountRecord(state=${effectiveState()}, credentialGeneration=$credentialGeneration, gateRevision=$gateRevision)"
}

data class VaultPolicyRecord(val writeKeyVersion: String) {
    init {
        require(writeKeyVersion.isNotBlank() && writeKeyVersion.length <= EncryptedEnvelope.KEY_VERSION_MAX_LENGTH) {
            "Write key version must contain 1..${EncryptedEnvelope.KEY_VERSION_MAX_LENGTH} characters"
        }
    }

    override fun toString(): String = "VaultPolicyRecord(writeKeyVersion=[REDACTED])"
}

data class KeyRotationBatchResult(
    val writeVersion: String,
    val rewrapped: Int,
    val remaining: Long,
    val complete: Boolean,
) {
    init {
        require(writeVersion.isNotBlank()) { "Write version cannot be blank" }
        require(rewrapped >= 0) { "Rewrapped count cannot be negative" }
        require(remaining >= 0) { "Remaining count cannot be negative" }
    }
}

data class BusinessSessionKey(
    val accountId: UUID,
    val credentialGeneration: Long,
    val serviceKey: String,
) {
    init {
        require(credentialGeneration > 0) { "Credential generation must be positive" }
        require(serviceKey.isNotBlank()) { "Service key cannot be blank" }
    }

    override fun toString(): String =
        "BusinessSessionKey(credentialGeneration=$credentialGeneration, serviceKey=[REDACTED])"
}
