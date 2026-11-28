package com.upcpocket.backend.storage

import com.upcpocket.backend.contract.EncryptedEnvelope
import com.upcpocket.backend.contract.LoginTag
import com.upcpocket.backend.contract.VaultPolicyRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class JdbcAccountStoreTest {
    private lateinit var database: JdbcTestDatabase

    @BeforeEach
    fun setUp() {
        database = JdbcTestDatabase()
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    @Test
    fun `first binding rejects duplicate account and login tag`() {
        val first = JdbcTestDatabase.sampleAccount(id = UUID(0, 1), seed = 11)
        assertTrue(database.accounts.insertFirstBinding(first))
        assertFalse(database.accounts.insertFirstBinding(first))

        val duplicateTag = JdbcTestDatabase.sampleAccount(id = UUID(0, 2), seed = 11)
        assertFalse(database.accounts.insertFirstBinding(duplicateTag))
        assertEquals(first.accountId, database.accounts.findByLoginTag(first.loginTag)?.accountId)
    }

    @Test
    fun `credential update increments generations and rejects stale version`() {
        val account = JdbcTestDatabase.sampleAccount(id = UUID(0, 10), seed = 10)
        assertTrue(database.accounts.insertFirstBinding(account))
        val attempt = UUID.randomUUID()
        assertTrue(database.accounts.registerPendingAttempt(account.accountId, 1, 1, attempt))

        val newTag = LoginTag(ByteArray(32) { (90 + it).toByte() })
        val newEnvelope = EncryptedEnvelope(
            format = 1,
            payloadNonce = ByteArray(12) { 7 },
            payloadCiphertext = ByteArray(16) { 8 },
            keyVersion = "v2",
            wrappedDek = ByteArray(48) { 9 },
        )
        val updated = database.accounts.updateCredential(
            accountId = account.accountId,
            expectedCredentialGeneration = 1,
            expectedGateRevision = 1,
            expectedPendingAttemptId = attempt,
            loginTag = newTag,
            envelope = newEnvelope,
        )

        assertNotNull(updated)
        assertEquals(2L, updated!!.credentialGeneration)
        assertEquals(2L, updated.gateRevision)
        assertNull(updated.pendingAttemptId)
        assertFalse(updated.reauthRequired)
        assertNull(
            database.accounts.updateCredential(
                account.accountId,
                expectedCredentialGeneration = 1,
                expectedGateRevision = 1,
                expectedPendingAttemptId = attempt,
                loginTag = account.loginTag,
                envelope = account.envelope,
            ),
        )
        assertFalse(database.accounts.registerPendingAttempt(account.accountId, 1, 1, UUID.randomUUID()))
    }

    @Test
    fun `credential update compares nullable pending id and preserves disabled state`() {
        val account = JdbcTestDatabase.sampleAccount(
            id = UUID(0, 11),
            seed = 11,
            credentialGeneration = 3,
            gateRevision = 5,
            disabled = true,
            reauthRequired = true,
        )
        assertTrue(database.accounts.insertFirstBinding(account))

        val updated = database.accounts.updateCredential(
            accountId = account.accountId,
            expectedCredentialGeneration = 3,
            expectedGateRevision = 5,
            expectedPendingAttemptId = null,
            loginTag = account.loginTag,
            envelope = account.envelope,
        )

        assertNotNull(updated)
        assertEquals(4L, updated!!.credentialGeneration)
        assertEquals(6L, updated.gateRevision)
        assertTrue(updated.disabled)
        assertFalse(updated.reauthRequired)
        assertNull(updated.pendingAttemptId)
    }

    @Test
    fun `unique login tag conflict leaves the surrounding transaction usable`() {
        val first = JdbcTestDatabase.sampleAccount(id = UUID(0, 12), seed = 12)
        val second = JdbcTestDatabase.sampleAccount(id = UUID(0, 13), seed = 13)
        assertTrue(database.accounts.insertFirstBinding(first))
        assertTrue(database.accounts.insertFirstBinding(second))

        database.transactionTemplate.executeWithoutResult {
            assertNull(
                database.accounts.updateCredential(
                    accountId = first.accountId,
                    expectedCredentialGeneration = first.credentialGeneration,
                    expectedGateRevision = first.gateRevision,
                    expectedPendingAttemptId = null,
                    loginTag = second.loginTag,
                    envelope = first.envelope,
                ),
            )
            assertTrue(database.accounts.insertVaultPolicyIfAbsent(VaultPolicyRecord("v1")))
        }

        assertEquals(first.accountId, database.accounts.findByLoginTag(first.loginTag)?.accountId)
        assertEquals(second.accountId, database.accounts.findByLoginTag(second.loginTag)?.accountId)
        assertEquals("v1", database.accounts.findVaultPolicy()?.writeKeyVersion)
    }

    @Test
    fun `orphaned pending attempts require reauthentication and advance the gate`() {
        val pendingAttempt = UUID.randomUUID()
        val orphaned = JdbcTestDatabase.sampleAccount(
            id = UUID(0, 14),
            seed = 14,
            pendingAttemptId = pendingAttempt,
        )
        val unaffected = JdbcTestDatabase.sampleAccount(id = UUID(0, 15), seed = 15)
        assertTrue(database.accounts.insertFirstBinding(orphaned))
        assertTrue(database.accounts.insertFirstBinding(unaffected))

        assertEquals(1, database.accounts.convertOrphanedPendingAttemptsToReauthRequired())

        val converted = database.accounts.findByAccountId(orphaned.accountId)
        assertNotNull(converted)
        assertTrue(converted!!.reauthRequired)
        assertNull(converted.pendingAttemptId)
        assertEquals(orphaned.gateRevision + 1, converted.gateRevision)

        val unchanged = database.accounts.findByAccountId(unaffected.accountId)
        assertNotNull(unchanged)
        assertFalse(unchanged!!.reauthRequired)
        assertNull(unchanged.pendingAttemptId)
        assertEquals(unaffected.gateRevision, unchanged.gateRevision)
        assertEquals(0, database.accounts.convertOrphanedPendingAttemptsToReauthRequired())
    }

    @Test
    fun `vault policy row is locked only inside an active jdbc transaction`() {
        assertThrows(IllegalStateException::class.java) {
            database.accounts.lockVaultPolicyForWrite()
        }
        assertNull(database.transactionTemplate.execute { database.accounts.lockVaultPolicyForWrite() })

        assertTrue(database.accounts.insertVaultPolicyIfAbsent(VaultPolicyRecord("v1")))
        val locked = database.transactionTemplate.execute { database.accounts.lockVaultPolicyForWrite() }
        assertNotNull(locked)
        assertEquals("v1", locked!!.writeKeyVersion)
    }

    @Test
    fun `reauth trip is one time and restoration preserves reauth requirement`() {
        val account = JdbcTestDatabase.sampleAccount(id = UUID(0, 20), seed = 20)
        assertTrue(database.accounts.insertFirstBinding(account))
        val attempt = UUID.randomUUID()
        assertTrue(database.accounts.registerPendingAttempt(account.accountId, 1, 1, attempt))
        assertTrue(database.accounts.tripReauthRequired(account.accountId, 1, attempt))
        assertFalse(database.accounts.tripReauthRequired(account.accountId, 1, attempt))

        val reauth = database.accounts.findByAccountId(account.accountId)
        assertNotNull(reauth)
        assertTrue(reauth!!.reauthRequired)
        assertNull(reauth.pendingAttemptId)
        assertEquals(2L, reauth.gateRevision)

        val disabled = database.accounts.disable(account.accountId, reauth.gateRevision)
        assertNotNull(disabled)
        assertTrue(disabled!!.disabled)
        val restored = database.accounts.restore(account.accountId, disabled.gateRevision)
        assertNotNull(restored)
        assertFalse(restored!!.disabled)
        assertTrue(restored.reauthRequired)
        assertNull(database.accounts.restore(account.accountId, reauth.gateRevision))
    }

    @Test
    fun `unbind hard deletes encrypted row`() {
        val account = JdbcTestDatabase.sampleAccount(id = UUID(0, 30), seed = 30)
        assertTrue(database.accounts.insertFirstBinding(account))
        assertTrue(database.accounts.unbind(account.accountId, account.gateRevision))
        assertNull(database.accounts.findByAccountId(account.accountId))
        assertEquals(
            0L,
            database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM pocket_account WHERE account_id = :id",
                mapOf("id" to account.accountId),
                Long::class.javaObjectType,
            ),
        )
    }

    @Test
    fun `rewrap page is ordered and conditional update checks generation`() {
        val rows = (1L..3L).map { index ->
            JdbcTestDatabase.sampleAccount(id = UUID(0, index), seed = index.toInt())
                .also { assertTrue(database.accounts.insertFirstBinding(it)) }
        }
        assertEquals(3L, database.accounts.countAccountsNotUsingKeyVersion("v2"))

        val firstPage = database.accounts.findAccountsNeedingRewrap("v2", null, 2)
        assertEquals(rows.take(2).map { it.accountId }, firstPage.map { it.accountId })
        val secondPage = database.accounts.findAccountsNeedingRewrap("v2", firstPage.last().accountId, 2)
        assertEquals(listOf(rows.last().accountId), secondPage.map { it.accountId })

        val first = firstPage.first()
        val oldWrappedDek = first.envelope.wrappedDek
        val replacement = ByteArray(48) { 99 }
        assertTrue(
            database.accounts.updateWrappedDekIfUnchanged(
                first.accountId,
                first.credentialGeneration,
                first.envelope.keyVersion,
                oldWrappedDek,
                "v2",
                replacement,
            ),
        )
        assertFalse(
            database.accounts.updateWrappedDekIfUnchanged(
                first.accountId,
                first.credentialGeneration,
                first.envelope.keyVersion,
                oldWrappedDek,
                "v2",
                replacement,
            ),
        )
        assertEquals(2L, database.accounts.countAccountsNotUsingKeyVersion("v2"))
        oldWrappedDek.fill(0)
        replacement.fill(0)
    }

    @Test
    fun `rewrap update rejects a stale credential generation`() {
        val account = JdbcTestDatabase.sampleAccount(id = UUID(0, 40), seed = 40)
        assertTrue(database.accounts.insertFirstBinding(account))
        assertNotNull(
            database.accounts.updateCredential(
                accountId = account.accountId,
                expectedCredentialGeneration = account.credentialGeneration,
                expectedGateRevision = account.gateRevision,
                expectedPendingAttemptId = null,
                loginTag = account.loginTag,
                envelope = account.envelope,
            ),
        )

        assertFalse(
            database.accounts.updateWrappedDekIfUnchanged(
                accountId = account.accountId,
                expectedCredentialGeneration = account.credentialGeneration,
                expectedKeyVersion = account.envelope.keyVersion,
                expectedWrappedDek = account.envelope.wrappedDek,
                newKeyVersion = "v2",
                newWrappedDek = ByteArray(48) { 101 },
            ),
        )
    }
}
