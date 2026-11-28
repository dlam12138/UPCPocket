package com.upcpocket.backend.session

import com.upcpocket.backend.contract.AccountRecord
import com.upcpocket.backend.contract.BusinessSessionKey
import com.upcpocket.backend.contract.DeviceDescriptor
import com.upcpocket.backend.contract.DeviceKind
import com.upcpocket.backend.contract.DevicePlatform
import com.upcpocket.backend.contract.SessionRevocationPort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID

/** Server-local, deliberately non-persistent storage for Pocket and upstream business sessions. */
@Component
class InMemorySessionRegistry(
    private val clock: Clock,
) : SessionRevocationPort {
    private data class TokenRecord(
        val digest: String,
        val deviceId: UUID,
        val accountId: UUID,
        val installationId: UUID,
        val platform: DevicePlatform,
        val kind: DeviceKind,
        val credentialGeneration: Long,
        val gateRevision: Long,
        val studentId: CharArray?,
        val expiresAt: Instant,
    )

    private data class StoredBusinessSession(
        val bytes: ByteArray,
        val expiresAt: Instant,
        val epoch: Long,
    )

    private val lock = Any()
    private val secureRandom = SecureRandom()
    private val pocketTokenTtl = Duration.ofHours(12)
    private val businessSessionTtl = Duration.ofMinutes(10)
    private val maxPocketTokens = 100_000
    private val maxBusinessSessions = 50_000
    private val maxBusinessSessionBytes = 64 * 1024 * 1024
    private val maxTrackedAccounts = 100_000
    private val tokens = LinkedHashMap<String, TokenRecord>()
    private val installationTokens = HashMap<Pair<UUID, UUID>, String>()
    private val businessSessions = LinkedHashMap<BusinessSessionKey, StoredBusinessSession>()
    private var businessSessionBytes = 0
    private val businessEpochs = HashMap<UUID, Long>()
    private val accountTokenEpochs = HashMap<UUID, Long>()
    private val deviceEpochs = HashMap<Pair<UUID, UUID>, Long>()
    private val maxTrackedDevices = 200_000
    private val activeIssueFences = HashMap<UUID, Pair<UUID, UUID>>()
    private val activeBusinessWriteFences = HashMap<UUID, UUID>()
    private val inFlightAccountReferences = HashMap<UUID, Int>()
    private var epochFloor = 0L

    fun captureIssueFence(accountId: UUID, installationId: UUID): SessionIssueFence = synchronized(lock) {
        ensureEpochSlotLocked(accountId)
        val deviceKey = accountId to installationId
        if (!deviceEpochs.containsKey(deviceKey) && deviceEpochs.size >= maxTrackedDevices) {
            cleanupExpiredLocked(clock.instant())
            reapUnusedEpochSlotsLocked()
            if (deviceEpochs.size >= maxTrackedDevices) throw SessionCapacityException()
        }
        val deviceEpoch = deviceEpochs.putIfAbsent(deviceKey, epochFloor) ?: epochFloor
        val fence = SessionIssueFence(
            UUID.randomUUID(), accountId, installationId, accountTokenEpochs.getValue(accountId), deviceEpoch,
        )
        activeIssueFences[fence.fenceId] = deviceKey
        addAccountReferenceLocked(accountId)
        fence
    }

    fun issue(account: AccountRecord, descriptor: DeviceDescriptor): IssuedPocketSession =
        issue(account, descriptor, captureIssueFence(account.accountId, descriptor.installationId))

    fun issue(
        account: AccountRecord,
        descriptor: DeviceDescriptor,
        studentId: CharArray?,
    ): IssuedPocketSession = issue(
        account,
        descriptor,
        captureIssueFence(account.accountId, descriptor.installationId),
        studentId,
    )

    fun discardUnusedIssueFence(fence: SessionIssueFence) = synchronized(lock) {
        releaseIssueFenceLocked(fence)
        reapUnusedEpochSlotsLocked()
    }

    fun issue(
        account: AccountRecord,
        descriptor: DeviceDescriptor,
        fence: SessionIssueFence,
        studentId: CharArray? = null,
    ): IssuedPocketSession = synchronized(lock) {
        try {
            val now = clock.instant()
            cleanupExpiredLocked(now)
            val deviceKey = account.accountId to descriptor.installationId
            if (activeIssueFences[fence.fenceId] != deviceKey ||
                fence.accountId != account.accountId || fence.installationId != descriptor.installationId ||
                (accountTokenEpochs[account.accountId] ?: epochFloor) != fence.accountEpoch ||
                (deviceEpochs[deviceKey] ?: epochFloor) != fence.deviceEpoch
            ) throw SessionRevokedDuringIssuanceException()
            val installationKey = account.accountId to descriptor.installationId
            val oldDigest = installationTokens[installationKey]
            val old = oldDigest?.let(tokens::get)
            if (oldDigest != null) removeTokenLocked(oldDigest)
            ensureEpochSlotLocked(account.accountId)
            if (tokens.size >= maxPocketTokens) throw SessionCapacityException()

            val random = ByteArray(TOKEN_BYTES)
            secureRandom.nextBytes(random)
            val token = try {
                Base64.getUrlEncoder().withoutPadding().encodeToString(random)
            } finally {
                random.fill(0)
            }
            val digest = digestToken(token)
            val expiresAt = now.plus(pocketTokenTtl)
            val record = TokenRecord(
                digest = digest,
                deviceId = old?.deviceId ?: UUID.randomUUID(),
                accountId = account.accountId,
                installationId = descriptor.installationId,
                platform = descriptor.platform,
                kind = descriptor.kind,
                credentialGeneration = account.credentialGeneration,
                gateRevision = account.gateRevision,
                studentId = studentId?.copyOf(),
                expiresAt = expiresAt,
            )
            tokens[digest] = record
            installationTokens[installationKey] = digest
            IssuedPocketSession(token, record.deviceId, expiresAt)
        } finally {
            releaseIssueFenceLocked(fence)
            reapUnusedEpochSlotsLocked()
        }
    }

    fun authenticate(token: String): PocketSessionIdentity? = synchronized(lock) {
        val now = clock.instant()
        val digest = digestToken(token)
        val record = tokens[digest] ?: return@synchronized null
        if (!record.expiresAt.isAfter(now)) {
            removeTokenLocked(digest)
            reapUnusedEpochSlotsLocked()
            return@synchronized null
        }
        record.toIdentity()
    }

    fun revokeCurrent(identity: PocketSessionIdentity): Boolean = synchronized(lock) {
        val current = tokens[identity.tokenDigest]
        if (current == null || current.accountId != identity.accountId || current.deviceId != identity.deviceId) {
            return@synchronized false
        }
        removeTokenLocked(identity.tokenDigest)
        advanceDeviceEpochLocked(current.accountId, current.installationId)
        revokeBusinessSessionsLocked(identity.accountId)
        reapUnusedEpochSlotsLocked()
        true
    }

    /** Kicking a device removes just its Pocket token, then invalidates shared upstream cookies. */
    fun revokeDevice(accountId: UUID, deviceId: UUID): Boolean = synchronized(lock) {
        val record = tokens.values.firstOrNull { it.accountId == accountId && it.deviceId == deviceId }
            ?: return@synchronized false
        removeTokenLocked(record.digest)
        advanceDeviceEpochLocked(record.accountId, record.installationId)
        revokeBusinessSessionsLocked(accountId)
        reapUnusedEpochSlotsLocked()
        true
    }

    fun devices(accountId: UUID): List<DeviceSessionView> = synchronized(lock) {
        cleanupExpiredLocked(clock.instant())
        tokens.values.asSequence()
            .filter { it.accountId == accountId }
            .map { DeviceSessionView(it.deviceId, it.installationId, it.platform, it.kind, it.expiresAt) }
            .sortedBy { it.deviceId.toString() }
            .toList()
    }

    fun isDeviceActive(
        accountId: UUID,
        deviceId: UUID,
        credentialGeneration: Long? = null,
        gateRevision: Long? = null,
    ): Boolean = synchronized(lock) {
        cleanupExpiredLocked(clock.instant())
        tokens.values.any {
            it.accountId == accountId && it.deviceId == deviceId &&
                (credentialGeneration == null || it.credentialGeneration == credentialGeneration) &&
                (gateRevision == null || it.gateRevision == gateRevision)
        }
    }

    /** Returns a short-lived copy of the numeric identity bound to this exact Pocket token. */
    fun copyStudentId(identity: PocketSessionIdentity): CharArray? = synchronized(lock) {
        cleanupExpiredLocked(clock.instant())
        val record = tokens[identity.tokenDigest] ?: return@synchronized null
        if (!record.expiresAt.isAfter(clock.instant()) || record.deviceId != identity.deviceId ||
            record.accountId != identity.accountId || record.credentialGeneration != identity.credentialGeneration ||
            record.gateRevision != identity.gateRevision
        ) return@synchronized null
        record.studentId?.copyOf()
    }

    fun captureBusinessFence(identity: PocketSessionIdentity): SessionBusinessFence? = synchronized(lock) {
        val now = clock.instant()
        cleanupExpiredLocked(now)
        val record = tokens[identity.tokenDigest] ?: return@synchronized null
        if (!record.expiresAt.isAfter(now) || record.accountId != identity.accountId ||
            record.deviceId != identity.deviceId || record.credentialGeneration != identity.credentialGeneration ||
            record.gateRevision != identity.gateRevision
        ) return@synchronized null
        ensureEpochSlotLocked(record.accountId)
        SessionBusinessFence(
            accountId = record.accountId,
            deviceId = record.deviceId,
            credentialGeneration = record.credentialGeneration,
            gateRevision = record.gateRevision,
            businessEpoch = businessEpochs.getValue(record.accountId),
            tokenDigest = record.digest,
        )
    }

    fun isBusinessFenceCurrent(fence: SessionBusinessFence): Boolean = synchronized(lock) {
        val now = clock.instant()
        cleanupExpiredLocked(now)
        val record = tokens[fence.tokenDigest] ?: return@synchronized false
        record.expiresAt.isAfter(now) &&
            record.accountId == fence.accountId &&
            record.deviceId == fence.deviceId &&
            record.credentialGeneration == fence.credentialGeneration &&
            record.gateRevision == fence.gateRevision &&
            (businessEpochs[record.accountId] ?: epochFloor) == fence.businessEpoch
    }

    fun getBusinessSession(key: BusinessSessionKey): BusinessSessionValue? = synchronized(lock) {
        val now = clock.instant()
        val entry = businessSessions[key] ?: return@synchronized null
        if (!entry.expiresAt.isAfter(now) || entry.epoch != (businessEpochs[key.accountId] ?: epochFloor)) {
            removeBusinessSessionLocked(key)
            reapUnusedEpochSlotsLocked()
            return@synchronized null
        }
        businessSessions.remove(key)
        businessSessions[key] = entry
        BusinessSessionValue(entry.bytes, entry.expiresAt)
    }

    /**
     * The epoch check and insertion share the same lock as revocation. A slow response captured
     * before a revoke can therefore never repopulate the cache afterwards.
     */
    fun putBusinessSession(
        key: BusinessSessionKey,
        bytes: ByteArray,
        upstreamExpiresAt: Instant?,
        expectedBusinessEpoch: Long,
    ): Boolean = synchronized(lock) {
        putBusinessSessionLocked(key, bytes, upstreamExpiresAt, expectedBusinessEpoch)
    }

    /** Holds an account epoch reference across an upstream login call and its cache write. */
    fun beginBusinessSessionWrite(accountId: UUID): BusinessSessionWriteFence = synchronized(lock) {
        ensureEpochSlotLocked(accountId)
        val fence = BusinessSessionWriteFence(UUID.randomUUID(), accountId, businessEpochs.getValue(accountId))
        activeBusinessWriteFences[fence.fenceId] = accountId
        addAccountReferenceLocked(accountId)
        fence
    }

    fun putBusinessSession(
        key: BusinessSessionKey,
        bytes: ByteArray,
        upstreamExpiresAt: Instant?,
        fence: BusinessSessionWriteFence,
    ): Boolean = synchronized(lock) {
        if (activeBusinessWriteFences[fence.fenceId] != key.accountId || fence.accountId != key.accountId) {
            return@synchronized false
        }
        putBusinessSessionLocked(key, bytes, upstreamExpiresAt, fence.businessEpoch)
    }

    fun releaseBusinessSessionWrite(fence: BusinessSessionWriteFence) = synchronized(lock) {
        if (activeBusinessWriteFences.remove(fence.fenceId, fence.accountId)) {
            releaseAccountReferenceLocked(fence.accountId)
            reapUnusedEpochSlotsLocked()
        }
    }

    private fun putBusinessSessionLocked(
        key: BusinessSessionKey,
        bytes: ByteArray,
        upstreamExpiresAt: Instant?,
        expectedBusinessEpoch: Long,
    ): Boolean {
        if (key.serviceKey.length > MAX_SERVICE_KEY_CHARS) throw SessionCapacityException()
        val now = clock.instant()
        cleanupExpiredLocked(now)
        ensureEpochSlotLocked(key.accountId)
        if (businessEpochs.getValue(key.accountId) != expectedBusinessEpoch) {
            reapUnusedEpochSlotsLocked()
            return false
        }
        val expiry = minOf(now.plus(businessSessionTtl), upstreamExpiresAt ?: Instant.MAX)
        if (!expiry.isAfter(now)) {
            reapUnusedEpochSlotsLocked()
            return false
        }
        if (bytes.size > BusinessSessionValue.MAX_SESSION_BYTES) throw SessionCapacityException()
        val existingBytes = businessSessions[key]?.bytes?.size ?: 0
        val projectedSize = businessSessionBytes - existingBytes + bytes.size
        if ((key !in businessSessions && businessSessions.size >= maxBusinessSessions) ||
            projectedSize > maxBusinessSessionBytes
        ) throw SessionCapacityException()
        removeBusinessSessionLocked(key)
        businessSessions[key] = StoredBusinessSession(bytes.copyOf(), expiry, expectedBusinessEpoch)
        businessSessionBytes += bytes.size
        return true
    }

    override fun revokeAccountAll(accountId: UUID) = synchronized(lock) {
        tokens.values.filter { it.accountId == accountId }.map { it.digest }.forEach(::removeTokenLocked)
        ensureEpochSlotLocked(accountId)
        accountTokenEpochs[accountId] = Math.addExact(accountTokenEpochs.getValue(accountId), 1L)
        revokeBusinessSessionsLocked(accountId)
        reapUnusedEpochSlotsLocked()
    }

    override fun revokeBusinessSessions(accountId: UUID) = synchronized(lock) {
        revokeBusinessSessionsLocked(accountId)
        reapUnusedEpochSlotsLocked()
    }

    override fun currentBusinessEpoch(accountId: UUID): Long = synchronized(lock) {
        ensureEpochSlotLocked(accountId)
        businessEpochs.getValue(accountId)
    }

    @Scheduled(fixedDelay = 60_000)
    fun cleanupExpired() = synchronized(lock) {
        cleanupExpiredLocked(clock.instant())
    }

    private fun revokeBusinessSessionsLocked(accountId: UUID) {
        ensureEpochSlotLocked(accountId)
        val next = Math.addExact(businessEpochs.getValue(accountId), 1L)
        businessEpochs[accountId] = next
        businessSessions.keys.filter { it.accountId == accountId }.toList().forEach(::removeBusinessSessionLocked)
    }

    private fun ensureEpochSlotLocked(accountId: UUID) {
        if (!businessEpochs.containsKey(accountId) && businessEpochs.size >= maxTrackedAccounts) {
            cleanupExpiredLocked(clock.instant())
            reapUnusedEpochSlotsLocked()
            if (businessEpochs.size >= maxTrackedAccounts) throw SessionCapacityException()
        }
        businessEpochs.putIfAbsent(accountId, epochFloor)
        accountTokenEpochs.putIfAbsent(accountId, epochFloor)
    }

    private fun advanceDeviceEpochLocked(accountId: UUID, installationId: UUID) {
        val key = accountId to installationId
        if (key !in deviceEpochs && deviceEpochs.size >= maxTrackedDevices) throw SessionCapacityException()
        val current = deviceEpochs[key] ?: epochFloor
        deviceEpochs[key] = Math.addExact(current, 1L)
    }

    private fun cleanupExpiredLocked(now: Instant) {
        tokens.values.filter { !it.expiresAt.isAfter(now) }.map { it.digest }.forEach(::removeTokenLocked)
        businessSessions.entries.filter { !it.value.expiresAt.isAfter(now) }.map { it.key }.forEach(::removeBusinessSessionLocked)
        reapUnusedEpochSlotsLocked()
    }

    private fun releaseIssueFenceLocked(fence: SessionIssueFence) {
        if (activeIssueFences.remove(fence.fenceId) == (fence.accountId to fence.installationId)) {
            releaseAccountReferenceLocked(fence.accountId)
        }
    }

    private fun addAccountReferenceLocked(accountId: UUID) {
        inFlightAccountReferences[accountId] = Math.addExact(inFlightAccountReferences[accountId] ?: 0, 1)
    }

    private fun releaseAccountReferenceLocked(accountId: UUID) {
        val next = (inFlightAccountReferences[accountId] ?: return) - 1
        if (next <= 0) inFlightAccountReferences.remove(accountId) else inFlightAccountReferences[accountId] = next
    }

    private fun reapUnusedEpochSlotsLocked() {
        val activeIssueDevices = activeIssueFences.values.toHashSet()
        val liveDevices = tokens.values.mapTo(HashSet()) { it.accountId to it.installationId }
        val deviceIterator = deviceEpochs.entries.iterator()
        while (deviceIterator.hasNext()) {
            val (key, epoch) = deviceIterator.next()
            if (key !in liveDevices && key !in activeIssueDevices) {
                deviceIterator.remove()
                epochFloor = maxOf(epochFloor, Math.addExact(epoch, 1L))
            }
        }

        val liveAccounts = HashSet<UUID>().apply {
            tokens.values.mapTo(this) { it.accountId }
            businessSessions.keys.mapTo(this) { it.accountId }
            addAll(inFlightAccountReferences.keys)
            deviceEpochs.keys.mapTo(this) { it.first }
        }
        val accountIterator = businessEpochs.entries.iterator()
        while (accountIterator.hasNext()) {
            val (accountId, businessEpoch) = accountIterator.next()
            if (accountId !in liveAccounts) {
                val tokenEpoch = accountTokenEpochs[accountId] ?: epochFloor
                epochFloor = maxOf(epochFloor, Math.addExact(maxOf(businessEpoch, tokenEpoch), 1L))
                accountIterator.remove()
                accountTokenEpochs.remove(accountId)
            }
        }
    }

    internal fun trackedEpochSlotCount(): Int = synchronized(lock) {
        businessEpochs.size + deviceEpochs.size
    }

    private fun removeTokenLocked(digest: String) {
        val record = tokens.remove(digest) ?: return
        record.studentId?.fill('\u0000')
        installationTokens.remove(record.accountId to record.installationId, digest)
    }

    private fun removeBusinessSessionLocked(key: BusinessSessionKey) {
        businessSessions.remove(key)?.let { stored ->
            businessSessionBytes -= stored.bytes.size
            stored.bytes.fill(0)
        }
    }

    private fun TokenRecord.toIdentity() = PocketSessionIdentity(
        deviceId = deviceId,
        accountId = accountId,
        installationId = installationId,
        platform = platform,
        kind = kind,
        credentialGeneration = credentialGeneration,
        gateRevision = gateRevision,
        expiresAt = expiresAt,
        tokenDigest = digest,
    )

    private fun digestToken(token: String): String {
        val input = token.toByteArray(Charsets.UTF_8)
        var digest: ByteArray? = null
        return try {
            val hash = MessageDigest.getInstance("SHA-256").digest(input)
            digest = hash
            Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        } finally {
            input.fill(0)
            digest?.fill(0)
        }
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private const val MAX_SERVICE_KEY_CHARS = 64
    }
}

class SessionCapacityException : RuntimeException()

class SessionRevokedDuringIssuanceException : RuntimeException()
