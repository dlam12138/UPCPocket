package com.upcpocket.app.account

import com.upcpocket.app.account.api.AccountDataScope
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.AccountInvalidationFailure
import com.upcpocket.app.account.api.AccountInvalidationRequest
import com.upcpocket.app.account.api.AccountInvalidationResult
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountInvalidator
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.platform.crypto.ByteCrypto
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SchoolAccountRepositoryTest {

    private val oldPassword = "synthetic-old-password"
    private val newPassword = "synthetic-new-password"
    private val account = "20260001"
    private val otherAccount = "20260002"

    @Test
    fun firstSaveEncryptsAndRestoresWithoutPlaintext() = runTest {
        val store = MemoryStore()
        val crypto = FakeCrypto()
        val repository = repository(store, crypto)
        repository.restore()

        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)
        val secret = oldPassword.toCharArray()
        assertEquals(
            SchoolAccountWriteResult.Success,
            repository.save(account, secret),
        )
        assertTrue("保存后调用方传入的密码数组应被清零", secret.all { it == '\u0000' })
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, repository.state.value.status)
        assertEquals(AccountGeneration(1), repository.state.value.generation)
        assertEquals("MASK-01", repository.state.value.maskedAccount)
        assertTrue(repository.state.value.studentIdAvailable)
        assertEquals("MASK-01", repository.state.value.maskedStudentId)
        val identity = repository.studentIdentity.value!!
        assertEquals(account, identity.studentId)
        assertEquals(AccountGeneration(1), identity.credentialGeneration)
        assertFalse(identity.accountId.value.contains(account))

        val raw = store.read(AccountFormat.ACCOUNT_FILE, 64 * 1024)!!
        assertFalse(raw.toString(Charsets.UTF_8).contains(account))
        assertFalse(raw.toString(Charsets.UTF_8).contains(oldPassword))
        assertNoCredentialPlaintext(store, account, oldPassword)

        val reopened = repository(store, crypto)
        reopened.restore()
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, reopened.state.value.status)
        assertEquals(AccountGeneration(1), reopened.state.value.generation)
        assertEquals("MASK-01", reopened.state.value.maskedAccount)
        assertEquals(identity.accountId, reopened.studentIdentity.value?.accountId)
        assertEquals(identity.accountId, reopened.accountReference.value?.accountId)
        assertEquals(AccountGeneration(1), reopened.accountReference.value?.credentialGeneration)
        assertFalse(reopened.state.value.cleanupRequired)
    }

    @Test
    fun passwordUpdateIncrementsGenerationAndOnlyClearsSessions() = runTest {
        val store = MemoryStore()
        val invalidator = RecordingInvalidator()
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        invalidator.requests.clear()

        assertEquals(
            SchoolAccountWriteResult.Success,
            repository.save(account, newPassword.toCharArray()),
        )
        assertEquals(AccountGeneration(2), repository.state.value.generation)
        assertEquals(1, invalidator.requests.size)
        assertEquals(AccountDataScope.SESSIONS_ONLY, invalidator.requests.single().scope)
        assertEquals(AccountGeneration(1), invalidator.requests.single().previousGeneration)
        assertEquals(AccountGeneration(2), invalidator.requests.single().nextGeneration)
    }

    @Test
    fun accountSwitchClearsPersonalDataAndRejectsOldToken() = runTest {
        val store = MemoryStore()
        val invalidator = RecordingInvalidator()
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        val oldToken = repository.state.value.captureRequestToken()!!
        val oldAccountId = repository.studentIdentity.value!!.accountId

        assertEquals(
            SchoolAccountWriteResult.Success,
            repository.save(otherAccount, newPassword.toCharArray()),
        )
        assertEquals(AccountDataScope.PERSONAL_DATA, invalidator.requests.single().scope)
        assertFalse(repository.state.value.isCurrent(oldToken))
        assertEquals(AccountGeneration(2), repository.state.value.generation)
        assertEquals("MASK-02", repository.state.value.maskedAccount)
        assertNotEquals(oldAccountId, repository.studentIdentity.value!!.accountId)
    }

    @Test
    fun nonNumericAliasHasNoControlledStudentIdentity() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()
        val alias = "student-alias@example.invalid"
        assertEquals(
            SchoolAccountWriteResult.Success,
            repository.save(alias, oldPassword.toCharArray()),
        )
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, repository.state.value.status)
        assertFalse(repository.state.value.studentIdAvailable)
        assertNull(repository.state.value.maskedStudentId)
        assertNull(repository.studentIdentity.value)
    }

    @Test
    fun accountReferenceExistsForAliasWithoutStudentIdentity() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()
        assertNull(repository.accountReference.value)
        val alias = "student-alias@example.invalid"
        repository.save(alias, oldPassword.toCharArray())
        val reference = repository.accountReference.value!!
        assertEquals(AccountGeneration(1), reference.credentialGeneration)
        assertEquals(repository.studentIdentity.value?.accountId, null)
        assertFalse(reference.accountId.value.contains(alias))
        assertFalse(reference.toString().contains(alias))
    }

    @Test
    @OptIn(RestrictedSchoolCredentialUse::class)
    fun controlledCredentialUseIsBoundToCurrentGenerationAndClearsPassword() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()
        repository.save(account, oldPassword.toCharArray())

        var seenPassword: CharArray? = null
        val result = repository.withLoginCredential { credential ->
            assertEquals(account, credential.loginName)
            assertEquals(repository.accountReference.value, com.upcpocket.app.account.api.SchoolAccountReference(
                accountId = credential.accountId,
                credentialGeneration = credential.credentialGeneration,
            ))
            credential.usePassword { chars ->
                seenPassword = chars
                assertEquals(oldPassword, chars.concatToString())
            }
            "ok"
        }
        assertEquals(SchoolCredentialUseResult.Success("ok"), result)
        assertTrue(seenPassword!!.all { it == '\u0000' })
    }

    @Test
    @OptIn(RestrictedSchoolCredentialUse::class)
    fun credentialUseRejectsChangeDuringAuthentication() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()
        repository.save(account, oldPassword.toCharArray())

        val result = repository.withLoginCredential { credential ->
            credential.usePassword { assertEquals(oldPassword, it.concatToString()) }
            repository.save(account, newPassword.toCharArray())
            "late"
        }
        assertEquals(
            SchoolCredentialUseResult.Failure(SchoolCredentialFailure.CHANGED_DURING_USE),
            result,
        )
    }

    @Test
    @OptIn(RestrictedSchoolCredentialUse::class)
    fun credentialUseIsUnavailableWithoutAccount() = runTest {
        val repository = repository(MemoryStore())
        repository.restore()
        val result = repository.withLoginCredential { "unexpected" }
        assertEquals(
            SchoolCredentialUseResult.Failure(SchoolCredentialFailure.NOT_CONFIGURED),
            result,
        )
    }

    @Test
    fun shortenedOrPaddedLoginNameIsNotTreatedAsStudentId() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()
        // 不截取、不 trim 出数字；只有规范化后的整串符合 8–16 位才可用。
        val tooShort = "1234567"
        repository.save(tooShort, oldPassword.toCharArray())
        assertNull(repository.studentIdentity.value)
        val padded = " 20260001 "
        repository.save(padded, oldPassword.toCharArray())
        assertEquals("20260001", repository.studentIdentity.value?.studentId)
    }

    @Test
    fun encryptionFailureKeepsOldVersion() = runTest {
        val store = MemoryStore()
        val crypto = FakeCrypto()
        val repository = repository(store, crypto)
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        val before = store.snapshot()

        crypto.failEncrypt = true
        val result = repository.save(account, newPassword.toCharArray())
        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.ENCRYPTION_FAILED),
            result,
        )
        assertArrayEquals(before[AccountFormat.ACCOUNT_FILE], store.files[AccountFormat.ACCOUNT_FILE])
        assertEquals(AccountGeneration(1), repository.state.value.generation)

        val reopened = repository(store, FakeCrypto())
        reopened.restore()
        assertEquals(AccountGeneration(1), reopened.state.value.generation)
        assertEquals("MASK-01", reopened.state.value.maskedAccount)
    }

    @Test
    fun pendingWriteFailureKeepsOldVersion() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        val before = store.snapshot()

        store.failWrite = AccountFormat.PENDING_FILE
        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED),
            repository.save(account, newPassword.toCharArray()),
        )
        assertArrayEquals(before[AccountFormat.ACCOUNT_FILE], store.files[AccountFormat.ACCOUNT_FILE])
        assertEquals(AccountGeneration(1), repository.state.value.generation)
    }

    @Test
    fun journalWriteFailureKeepsOldVersionAndRemovesPending() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        val before = store.snapshot()

        store.failWrite = AccountFormat.JOURNAL_FILE
        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED),
            repository.save(account, newPassword.toCharArray()),
        )
        assertFalse(store.exists(AccountFormat.PENDING_FILE))
        assertArrayEquals(before[AccountFormat.ACCOUNT_FILE], store.files[AccountFormat.ACCOUNT_FILE])
        assertEquals(AccountGeneration(1), repository.state.value.generation)
    }

    @Test
    fun transitionStateWriteFailureKeepsOldVersion() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        val before = store.snapshot()

        store.failWrite = AccountFormat.STATE_FILE
        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED),
            repository.save(account, newPassword.toCharArray()),
        )
        assertFalse(store.exists(AccountFormat.JOURNAL_FILE))
        assertFalse(store.exists(AccountFormat.PENDING_FILE))
        assertArrayEquals(before[AccountFormat.STATE_FILE], store.files[AccountFormat.STATE_FILE])

        val reopened = repository(store, FakeCrypto())
        reopened.restore()
        assertEquals(AccountGeneration(1), reopened.state.value.generation)
        assertEquals("MASK-01", reopened.state.value.maskedAccount)
    }

    @Test
    fun crashAfterJournalResumesSwitchOnRestart() = runTest {
        val store = MemoryStore()
        val invalidator = RecordingInvalidator()
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())

        store.crashAfterWrite = AccountFormat.JOURNAL_FILE
        try {
            repository.save(otherAccount, newPassword.toCharArray())
            fail("应当模拟进程中断")
        } catch (_: SimulatedCrash) {
            // 模拟进程在 journal 写入后退出。
        }
        store.crashAfterWrite = null
        invalidator.requests.clear()

        val reopened = repository(store, invalidators = listOf(invalidator))
        reopened.restore()
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, reopened.state.value.status)
        assertEquals(AccountGeneration(2), reopened.state.value.generation)
        assertEquals("MASK-02", reopened.state.value.maskedAccount)
        assertEquals(AccountDataScope.PERSONAL_DATA, invalidator.requests.single().scope)
        assertFalse(store.exists(AccountFormat.JOURNAL_FILE))
        assertFalse(store.exists(AccountFormat.PENDING_FILE))
    }

    @Test
    fun cleanupFailurePersistsRemovingAndResumes() = runTest {
        val store = MemoryStore()
        val invalidator = RecordingInvalidator(fail = true)
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())

        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.CLEANUP_FAILED),
            repository.delete(),
        )
        assertEquals(SchoolAccountStatus.REMOVING, repository.state.value.status)
        assertTrue(repository.state.value.cleanupRequired)
        assertNull(repository.studentIdentity.value)
        assertTrue(store.exists(AccountFormat.JOURNAL_FILE))
        assertTrue(store.exists(AccountFormat.ACCOUNT_FILE))

        invalidator.fail = false
        val reopened = repository(store, invalidators = listOf(invalidator))
        reopened.restore()
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, reopened.state.value.status)
        assertFalse(reopened.state.value.cleanupRequired)
        assertNull(reopened.studentIdentity.value)
        assertFalse(store.exists(AccountFormat.ACCOUNT_FILE))
        assertFalse(store.exists(AccountFormat.JOURNAL_FILE))
    }

    @Test
    fun pendingDeleteFailureKeepsCommittedJournalAndResumesWithoutReinvalidating() = runTest {
        val store = MemoryStore()
        val invalidator = RecordingInvalidator()
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        invalidator.requests.clear()
        val oldCiphertext = store.files.getValue(AccountFormat.ACCOUNT_FILE).copyOf()
        store.failDelete = AccountFormat.PENDING_FILE

        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED),
            repository.save(account, newPassword.toCharArray()),
        )
        assertEquals(AccountGeneration(2), repository.state.value.generation)
        assertTrue(repository.state.value.cleanupRequired)
        assertEquals(SchoolAccountErrorCode.STORAGE_FAILED, repository.state.value.errorCode)
        assertTrue(store.exists(AccountFormat.ACCOUNT_FILE))
        assertFalse(oldCiphertext.contentEquals(store.files.getValue(AccountFormat.ACCOUNT_FILE)))
        assertTrue(store.exists(AccountFormat.PENDING_FILE))
        assertTrue(store.exists(AccountFormat.JOURNAL_FILE))
        assertEquals(1, invalidator.requests.size)

        // 新正式密文已写入，不能把旧版本误当回滚；重启只收尾 pending/journal。
        store.failDelete = null
        val reopened = repository(store, invalidators = listOf(invalidator))
        reopened.restore()
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, reopened.state.value.status)
        assertEquals(AccountGeneration(2), reopened.state.value.generation)
        assertEquals(1, invalidator.requests.size)
        assertFalse(store.exists(AccountFormat.PENDING_FILE))
        assertFalse(store.exists(AccountFormat.JOURNAL_FILE))
    }

    @Test
    fun journalDeleteFailureAfterSameAccountUpdateDoesNotReplaySessionsInvalidation() = runTest {
        val store = MemoryStore()
        val invalidator = RecordingInvalidator()
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        invalidator.requests.clear()

        store.failDelete = AccountFormat.JOURNAL_FILE
        assertEquals(
            SchoolAccountWriteResult.Success,
            repository.save(account, newPassword.toCharArray()),
        )
        assertEquals(AccountGeneration(2), repository.state.value.generation)
        assertEquals(1, invalidator.requests.size)

        store.files["unrelated-personal-after-update"] = byteArrayOf(1, 4, 7)
        store.failDelete = null
        val reopened = repository(store, invalidators = listOf(invalidator))
        reopened.restore()
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, reopened.state.value.status)
        assertEquals(1, invalidator.requests.size)
        assertArrayEquals(byteArrayOf(1, 4, 7), store.files["unrelated-personal-after-update"])
        assertFalse(store.exists(AccountFormat.JOURNAL_FILE))
    }

    @Test
    fun journalDeleteFailureAfterSwitchDoesNotReplayPersonalDataInvalidation() = runTest {
        val store = MemoryStore()
        val invalidator = RecordingInvalidator()
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        invalidator.requests.clear()

        store.failDelete = AccountFormat.JOURNAL_FILE
        assertEquals(
            SchoolAccountWriteResult.Success,
            repository.save(otherAccount, newPassword.toCharArray()),
        )
        assertTrue(store.exists(AccountFormat.JOURNAL_FILE))
        assertEquals(1, invalidator.requests.size)

        // 换号完成后用户重新建立的个人数据不能被下一次恢复当成旧数据清掉。
        store.files["unrelated-personal-after-switch"] = byteArrayOf(7, 8, 9)
        store.failDelete = null
        val reopened = repository(store, invalidators = listOf(invalidator))
        reopened.restore()
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, reopened.state.value.status)
        assertEquals(AccountGeneration(2), reopened.state.value.generation)
        assertEquals(1, invalidator.requests.size)
        assertArrayEquals(byteArrayOf(7, 8, 9), store.files["unrelated-personal-after-switch"])
        assertFalse(store.exists(AccountFormat.JOURNAL_FILE))
    }

    @Test
    fun journalDeleteFailureAfterDeleteDoesNotReplayPersonalDataInvalidation() = runTest {
        val store = MemoryStore()
        val invalidator = RecordingInvalidator()
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        invalidator.requests.clear()

        store.failDelete = AccountFormat.JOURNAL_FILE
        assertEquals(SchoolAccountWriteResult.Success, repository.delete())
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)
        assertTrue(store.exists(AccountFormat.JOURNAL_FILE))
        assertEquals(1, invalidator.requests.size)

        store.files["new-personal-after-delete"] = byteArrayOf(4, 5, 6)
        store.failDelete = null
        val reopened = repository(store, invalidators = listOf(invalidator))
        reopened.restore()
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, reopened.state.value.status)
        assertEquals(1, invalidator.requests.size)
        assertArrayEquals(byteArrayOf(4, 5, 6), store.files["new-personal-after-delete"])
        assertFalse(store.exists(AccountFormat.ACCOUNT_FILE))
        assertFalse(store.exists(AccountFormat.JOURNAL_FILE))
    }

    @Test
    fun deleteRemovesCredentialAndOnlyAccountFiles() = runTest {
        val store = MemoryStore()
        store.files["unrelated-focus"] = byteArrayOf(1, 2, 3)
        val invalidator = RecordingInvalidator()
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())

        assertEquals(SchoolAccountWriteResult.Success, repository.delete())
        assertEquals(SchoolAccountStatus.NOT_CONFIGURED, repository.state.value.status)
        assertEquals(AccountDataScope.PERSONAL_DATA, invalidator.requests.single().scope)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.files["unrelated-focus"])
        assertFalse(store.exists(AccountFormat.ACCOUNT_FILE))
        assertFalse(store.exists(AccountFormat.PENDING_FILE))
        assertTrue(store.exists(AccountFormat.STATE_FILE))
    }

    @Test
    fun keyFailureMarksNeedsReconfigurationAndClearsPersonalData() = runTest {
        val store = MemoryStore()
        val crypto = FakeCrypto()
        val invalidator = RecordingInvalidator()
        val repository = repository(store, crypto, listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())

        crypto.failDecrypt = true
        val reopened = repository(store, crypto, listOf(invalidator))
        reopened.restore()
        assertEquals(SchoolAccountStatus.NEEDS_RECONFIGURATION, reopened.state.value.status)
        assertEquals(SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE, reopened.state.value.errorCode)
        assertFalse(reopened.state.value.cleanupRequired)
        assertNull(reopened.state.value.maskedAccount)
        assertEquals(AccountDataScope.PERSONAL_DATA, invalidator.requests.single().scope)
        assertFalse(store.exists(AccountFormat.ACCOUNT_FILE))
    }

    @Test
    fun corruptCiphertextIsFixedErrorAndRecoverable() = runTest {
        val store = MemoryStore()
        val invalidator = RecordingInvalidator()
        val repository = repository(store, invalidators = listOf(invalidator))
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        store.files[AccountFormat.ACCOUNT_FILE] = "not-an-envelope".toByteArray()

        val reopened = repository(store, invalidators = listOf(invalidator))
        reopened.restore()
        assertEquals(SchoolAccountStatus.NEEDS_RECONFIGURATION, reopened.state.value.status)
        assertEquals(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED, reopened.state.value.errorCode)

        assertEquals(
            SchoolAccountWriteResult.Success,
            reopened.save(otherAccount, newPassword.toCharArray()),
        )
        assertEquals(SchoolAccountStatus.SAVED_UNVERIFIED, reopened.state.value.status)
        assertEquals("MASK-02", reopened.state.value.maskedAccount)
    }

    @Test
    fun unsupportedVersionIsNotTreatedAsSuccess() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()
        repository.save(account, oldPassword.toCharArray())
        store.files[AccountFormat.ACCOUNT_FILE] =
            """{"format":"${AccountFormat.CREDENTIAL_FORMAT}","version":99,"ciphertext":"AAAA"}"""
                .toByteArray()

        val reopened = repository(store, FakeCrypto())
        reopened.restore()
        assertEquals(SchoolAccountStatus.NEEDS_RECONFIGURATION, reopened.state.value.status)
        assertEquals(SchoolAccountErrorCode.UNSUPPORTED_VERSION, reopened.state.value.errorCode)
    }

    @Test
    fun invalidInputDoesNotWriteAnything() = runTest {
        val store = MemoryStore()
        val repository = repository(store)
        repository.restore()

        val invalidAccountSecret = oldPassword.toCharArray()
        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_ACCOUNT),
            repository.save("   ", invalidAccountSecret),
        )
        assertTrue(invalidAccountSecret.all { it == '\u0000' })
        assertEquals(
            SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.INVALID_PASSWORD),
            repository.save(account, CharArray(0)),
        )
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun controlledReaderAndStateExposeNoPasswordOrCredentialPayload() = runTest {
        val repository = repository(MemoryStore())
        repository.restore()
        repository.save(account, oldPassword.toCharArray())

        // 受控面只允许 accountId/学号/代次；toString 与状态字段都不得带回密码。
        assertFalse(repository.state.value.toString().contains(oldPassword))
        assertFalse(repository.studentIdentity.value.toString().contains(oldPassword))
        val readerMethods = com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader::class.java
            .methods
            .map { it.name.lowercase() }
        assertFalse(readerMethods.any { "password" in it || "credentialpayload" in it })
        val stateFields = com.upcpocket.app.account.api.SchoolAccountState::class.java
            .declaredFields
            .map { it.name.lowercase() }
        assertFalse(stateFields.any { "password" in it || "credential" in it })
    }

    private fun repository(
        store: MemoryStore,
        crypto: FakeCrypto = FakeCrypto(),
        invalidators: List<SchoolAccountInvalidator> = emptyList(),
    ) = SchoolAccountRepository(
        directory = java.io.File("/unused"),
        crypto = crypto,
        fileStore = store,
        clock = { 1_700_000_000_000 },
        ioDispatcher = Dispatchers.Unconfined,
        invalidators = invalidators,
        maskAccount = { "MASK-${it.takeLast(2)}" },
    )

    private fun assertNoCredentialPlaintext(
        store: MemoryStore,
        account: String,
        password: String,
    ) {
        store.files.forEach { (name, bytes) ->
            val text = bytes.toString(Charsets.UTF_8)
            assertFalse("$name 含账号明文", text.contains(account))
            assertFalse("$name 含密码明文", text.contains(password))
        }
    }

    private class FakeCrypto : ByteCrypto {
        var failEncrypt = false
        var failDecrypt = false

        override fun encrypt(plain: ByteArray): ByteArray {
            if (failEncrypt) throw GeneralSecurityException("synthetic")
            val output = ByteArray(plain.size + 1)
            output[0] = 7
            plain.forEachIndexed { index, byte -> output[index + 1] = (byte.toInt() xor 0x5A).toByte() }
            return output
        }

        override fun decrypt(stored: ByteArray): ByteArray {
            if (failDecrypt) throw GeneralSecurityException("synthetic")
            if (stored.isEmpty() || stored[0] != 7.toByte()) throw GeneralSecurityException("synthetic")
            return ByteArray(stored.size - 1) { index ->
                (stored[index + 1].toInt() xor 0x5A).toByte()
            }
        }
    }

    private class RecordingInvalidator(var fail: Boolean = false) : SchoolAccountInvalidator {
        val requests = mutableListOf<AccountInvalidationRequest>()

        override suspend fun invalidate(request: AccountInvalidationRequest): AccountInvalidationResult {
            requests += request
            return if (fail) {
                AccountInvalidationResult.failure(AccountInvalidationFailure.UNAVAILABLE)
            } else {
                AccountInvalidationResult.Success
            }
        }
    }

    private class SimulatedCrash : Error()

    private class MemoryStore : AccountFileStore {
        val files = linkedMapOf<String, ByteArray>()
        var failWrite: String? = null
        var crashAfterWrite: String? = null
        var failDelete: String? = null

        fun snapshot(): Map<String, ByteArray> = files.mapValues { it.value.copyOf() }

        override fun read(name: String, maxBytes: Int): ByteArray? =
            files[name]?.copyOf()?.takeIf { it.size <= maxBytes }

        override fun write(name: String, bytes: ByteArray) {
            if (failWrite == name) throw IOException("synthetic write failure")
            files[name] = bytes.copyOf()
            if (crashAfterWrite == name) throw SimulatedCrash()
        }

        override fun delete(name: String) {
            if (failDelete == name) throw IOException("synthetic delete failure")
            files.remove(name)
        }

        override fun exists(name: String): Boolean = files.containsKey(name)
    }
}
