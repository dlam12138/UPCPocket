package com.upcpocket.backend.session

import com.upcpocket.backend.contract.DeviceKind
import com.upcpocket.backend.contract.DevicePlatform
import java.time.Instant
import java.util.UUID

class IssuedPocketSession(
    val token: String,
    val deviceId: UUID,
    val expiresAt: Instant,
) {
    override fun toString(): String = "IssuedPocketSession(deviceId=$deviceId, token=[REDACTED])"
}

data class SessionIssueFence internal constructor(
    internal val fenceId: UUID,
    val accountId: UUID,
    val installationId: UUID,
    internal val accountEpoch: Long,
    internal val deviceEpoch: Long,
)

data class BusinessSessionWriteFence internal constructor(
    internal val fenceId: UUID,
    val accountId: UUID,
    val businessEpoch: Long,
)

data class SessionBusinessFence internal constructor(
    val accountId: UUID,
    val deviceId: UUID,
    val credentialGeneration: Long,
    val gateRevision: Long,
    val businessEpoch: Long,
    internal val tokenDigest: String,
) {
    override fun toString(): String =
        "SessionBusinessFence(accountId=$accountId, deviceId=$deviceId, credentialGeneration=$credentialGeneration, gateRevision=$gateRevision, businessEpoch=$businessEpoch)"
}

data class PocketSessionIdentity(
    val deviceId: UUID,
    val accountId: UUID,
    val installationId: UUID,
    val platform: DevicePlatform,
    val kind: DeviceKind,
    val credentialGeneration: Long,
    val gateRevision: Long,
    val expiresAt: Instant,
    /** Digest key used only for revoking the current bearer; never the raw bearer token. */
    internal val tokenDigest: String,
) {
    override fun toString(): String =
        "PocketSessionIdentity(deviceId=$deviceId, accountId=$accountId, credentialGeneration=$credentialGeneration, gateRevision=$gateRevision)"
}

data class DeviceSessionView(
    val deviceId: UUID,
    val installationId: UUID,
    val platform: DevicePlatform,
    val kind: DeviceKind,
    val expiresAt: Instant,
)

class BusinessSessionValue(bytes: ByteArray, val expiresAt: Instant) : AutoCloseable {
    init {
        require(bytes.size <= MAX_SESSION_BYTES) { "Business session value exceeds its size limit" }
    }

    private val value = bytes.copyOf()
    private var closed = false

    @Synchronized
    fun copyBytes(): ByteArray {
        check(!closed) { "Business session value is closed" }
        return value.copyOf()
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            value.fill(0)
            closed = true
        }
    }

    override fun toString(): String = "BusinessSessionValue([REDACTED])"

    companion object {
        const val MAX_SESSION_BYTES = 256 * 1024
    }
}

class BusinessLoginResult(
    bytes: ByteArray,
    val upstreamExpiresAt: Instant?,
) : AutoCloseable {
    init {
        require(bytes.size <= BusinessSessionValue.MAX_SESSION_BYTES) {
            "Business login result exceeds its size limit"
        }
    }

    private val value = bytes.copyOf()
    private var closed = false

    @Synchronized
    fun copyBytes(): ByteArray {
        check(!closed) { "Business login result is closed" }
        return value.copyOf()
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            value.fill(0)
            closed = true
        }
    }

    override fun toString(): String = "BusinessLoginResult([REDACTED])"
}
