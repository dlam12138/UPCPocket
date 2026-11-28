package com.upcpocket.backend.session

import com.upcpocket.backend.contract.AccountRecord
import com.upcpocket.backend.contract.BusinessSessionKey
import com.upcpocket.backend.contract.DeviceDescriptor
import com.upcpocket.backend.contract.DeviceKind
import com.upcpocket.backend.contract.DevicePlatform
import com.upcpocket.backend.contract.EncryptedEnvelope
import com.upcpocket.backend.contract.LoginTag
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class InMemorySessionRegistryTest {
    private val account = account()

    @Test
    fun `reissue replaces same installation token and preserves device identity`() {
        val registry = InMemorySessionRegistry(clock = MutableClock(NOW))
        val descriptor = descriptor(UUID.randomUUID())

        val first = registry.issue(account, descriptor)
        val second = registry.issue(account, descriptor)

        assertNotEquals(first.token, second.token)
        assertEquals(first.deviceId, second.deviceId)
        assertNull(registry.authenticate(first.token))
        assertEquals(second.deviceId, registry.authenticate(second.token)?.deviceId)
        assertEquals(1, registry.devices(account.accountId).size)
    }

    @Test
    fun `revoking one device preserves other Pocket tokens and blocks a delayed business response`() {
        val registry = InMemorySessionRegistry(clock = MutableClock(NOW))
        val first = registry.issue(account, descriptor(UUID.randomUUID()))
        val second = registry.issue(account, descriptor(UUID.randomUUID()))
        val key = BusinessSessionKey(account.accountId, account.credentialGeneration, "card")
        val capturedEpoch = registry.currentBusinessEpoch(account.accountId)
        assertTrue(registry.putBusinessSession(key, byteArrayOf(1, 2, 3), null, capturedEpoch))

        assertTrue(registry.revokeDevice(account.accountId, first.deviceId))

        assertNull(registry.authenticate(first.token))
        assertNotNull(registry.authenticate(second.token))
        assertNull(registry.getBusinessSession(key))
        assertFalse(registry.putBusinessSession(key, byteArrayOf(4, 5, 6), null, capturedEpoch))
    }

    @Test
    fun `business session expiry uses the earlier upstream expiry and account revocation clears tokens`() {
        val clock = MutableClock(NOW)
        val registry = InMemorySessionRegistry(clock = clock)
        val issued = registry.issue(account, descriptor(UUID.randomUUID()))
        val key = BusinessSessionKey(account.accountId, account.credentialGeneration, "library")
        val upstreamExpiry = NOW.plusSeconds(15)
        assertTrue(registry.putBusinessSession(key, byteArrayOf(7, 8), upstreamExpiry, 0L))
        val cached = registry.getBusinessSession(key)
        assertNotNull(cached)
        assertEquals(upstreamExpiry, cached!!.expiresAt)
        assertArrayEquals(byteArrayOf(7, 8), cached.copyBytes())
        cached.close()

        clock.advance(Duration.ofSeconds(16))
        assertNull(registry.getBusinessSession(key))
        registry.revokeAccountAll(account.accountId)
        assertNull(registry.authenticate(issued.token))
    }

    @Test
    fun `reconstructed registry does not accept a prior process token`() {
        val firstProcess = InMemorySessionRegistry(clock = MutableClock(NOW))
        val issued = firstProcess.issue(account, descriptor(UUID.randomUUID()))
        val restarted = InMemorySessionRegistry(clock = MutableClock(NOW))

        assertNull(restarted.authenticate(issued.token))
    }

    @Test
    fun `account and device revocation fences reject a token issued from an earlier login attempt`() {
        val registry = InMemorySessionRegistry(clock = MutableClock(NOW))
        val descriptor = descriptor(UUID.randomUUID())
        val accountFence = registry.captureIssueFence(account.accountId, descriptor.installationId)
        registry.revokeAccountAll(account.accountId)
        assertThrows(SessionRevokedDuringIssuanceException::class.java) {
            registry.issue(account, descriptor, accountFence)
        }

        val current = registry.issue(account, descriptor)
        val deviceFence = registry.captureIssueFence(account.accountId, descriptor.installationId)
        assertTrue(registry.revokeDevice(account.accountId, current.deviceId))
        assertThrows(SessionRevokedDuringIssuanceException::class.java) {
            registry.issue(account, descriptor, deviceFence)
        }
    }

    @Test
    fun `expired identities release epoch slots and a historical business epoch stays rejected`() {
        val clock = MutableClock(NOW)
        val registry = InMemorySessionRegistry(clock = clock)
        val retiredAccount = account()
        val retiredKey = BusinessSessionKey(retiredAccount.accountId, retiredAccount.credentialGeneration, "card")
        val lateWrite = registry.beginBusinessSessionWrite(retiredAccount.accountId)
        registry.revokeBusinessSessions(retiredAccount.accountId)
        registry.releaseBusinessSessionWrite(lateWrite)

        repeat(256) {
            registry.issue(account(), descriptor(UUID.randomUUID()))
        }
        clock.advance(Duration.ofHours(13))
        registry.cleanupExpired()

        assertEquals(0, registry.trackedEpochSlotCount())
        assertFalse(registry.putBusinessSession(retiredKey, byteArrayOf(9), null, lateWrite.businessEpoch))
        assertEquals(0, registry.trackedEpochSlotCount())
    }

    private fun account(id: UUID = UUID.randomUUID()): AccountRecord = AccountRecord(
        accountId = id,
        loginTag = LoginTag(ByteArray(32) { it.toByte() }),
        disabled = false,
        reauthRequired = false,
        credentialGeneration = 1,
        gateRevision = 1,
        pendingAttemptId = null,
        envelope = EncryptedEnvelope(1, ByteArray(12), ByteArray(16), "test-key", byteArrayOf(1)),
    )

    private fun descriptor(installationId: UUID) =
        DeviceDescriptor(installationId, DevicePlatform.ANDROID, DeviceKind.PHONE)

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-11-28T00:00:00Z")
    }
}
