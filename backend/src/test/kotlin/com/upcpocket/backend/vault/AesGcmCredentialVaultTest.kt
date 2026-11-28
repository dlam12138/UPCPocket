package com.upcpocket.backend.vault

import com.upcpocket.backend.contract.AccountRecord
import com.upcpocket.backend.contract.CasVerificationResult
import com.upcpocket.backend.contract.CredentialLease
import com.upcpocket.backend.contract.CredentialUsePurpose
import com.upcpocket.backend.contract.EncryptedEnvelope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class AesGcmCredentialVaultTest {
    private val kekV1 = ByteArray(32) { (it + 1).toByte() }
    private val kekV2 = ByteArray(32) { (it + 51).toByte() }
    private lateinit var keyProvider: EnvMasterKeyProvider
    private lateinit var hasher: HmacBlindIndexHasher
    private lateinit var vault: AesGcmCredentialVault

    @BeforeEach
    fun setUp() {
        keyProvider = EnvMasterKeyProvider(
            "v1:${Base64.getEncoder().encodeToString(kekV1)},v2:${Base64.getEncoder().encodeToString(kekV2)}",
            null,
        )
        hasher = HmacBlindIndexHasher(
            loginIndexKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
            loginIndexKeyFile = null,
            auditIndexKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            auditIndexKeyFile = null,
        )
        vault = AesGcmCredentialVault(keyProvider)
    }

    @AfterEach
    fun tearDown() {
        hasher.close()
        keyProvider.close()
        kekV1.fill(0)
        kekV2.fill(0)
    }

    @Test
    fun `seal opens credentials and clears lease on normal return`() {
        val login = "S12345678".toCharArray()
        val password = "pássword-测试".toCharArray()
        val account = sealAccount(login, password)
        var captured: CredentialLease? = null

        val result = vault.withCredential(account, CredentialUsePurpose.AUTO_BUSINESS_LOGIN) { lease ->
            captured = lease
            assertArrayEquals(login, lease.loginName)
            assertArrayEquals(password, lease.password)
            "used"
        }

        assertEquals("used", result)
        assertArrayEquals(CharArray(login.size) { '\u0000' }, captured!!.loginName)
        assertArrayEquals(CharArray(password.size) { '\u0000' }, captured!!.password)
        assertEquals("CredentialLease([REDACTED])", captured.toString())
        login.fill('\u0000')
        password.fill('\u0000')
    }

    @Test
    fun `lease arrays are cleared when caller block throws`() {
        val login = "student01".toCharArray()
        val password = "one-time-test".toCharArray()
        val account = sealAccount(login, password)
        var captured: CredentialLease? = null

        assertThrows(IllegalStateException::class.java) {
            vault.withCredential(account, CredentialUsePurpose.AUTO_BUSINESS_LOGIN) { lease ->
                captured = lease
                throw IllegalStateException("test failure")
            }
        }

        assertArrayEquals(CharArray(login.size) { '\u0000' }, captured!!.loginName)
        assertArrayEquals(CharArray(password.size) { '\u0000' }, captured!!.password)
        login.fill('\u0000')
        password.fill('\u0000')
    }

    @Test
    fun `aad binds account id generation and login tag`() {
        val login = "S00000001".toCharArray()
        val password = "test-password".toCharArray()
        val account = sealAccount(login, password)
        val differentTagChars = "TS000000001".toCharArray()
        val differentTag = hasher.loginTag(differentTagChars)
        differentTagChars.fill('\u0000')

        val wrongAccounts = listOf(
            account.copy(accountId = UUID.randomUUID()),
            account.copy(credentialGeneration = account.credentialGeneration + 1),
            account.copy(loginTag = differentTag),
        )
        wrongAccounts.forEach { wrong ->
            assertThrows(CredentialVaultException::class.java) {
                vault.withCredential(wrong, CredentialUsePurpose.AUTO_BUSINESS_LOGIN) { error("must not decrypt") }
            }
        }
        login.fill('\u0000')
        password.fill('\u0000')
    }

    @Test
    fun `gcm tag tampering is rejected`() {
        val login = "student02".toCharArray()
        val password = "test-payload-value".toCharArray()
        val account = sealAccount(login, password)
        val envelope = account.envelope
        val tamperedCiphertext = envelope.payloadCiphertext
        tamperedCiphertext[tamperedCiphertext.lastIndex] = (tamperedCiphertext.last().toInt() xor 1).toByte()
        val tamperedEnvelope = EncryptedEnvelope(
            envelope.format,
            envelope.payloadNonce,
            tamperedCiphertext,
            envelope.keyVersion,
            envelope.wrappedDek,
        )
        tamperedCiphertext.fill(0)

        assertThrows(CredentialVaultException::class.java) {
            vault.withCredential(account.copy(envelope = tamperedEnvelope), CredentialUsePurpose.AUTO_BUSINESS_LOGIN) {
                error("must not decrypt")
            }
        }
        login.fill('\u0000')
        password.fill('\u0000')
    }

    @Test
    fun `rewrap changes only wrapped dek and new envelope still decrypts`() {
        val login = "student03".toCharArray()
        val password = "rewrap-test-value".toCharArray()
        val account = sealAccount(login, password)
        val oldEnvelope = account.envelope
        val oldNonce = oldEnvelope.payloadNonce
        val oldCiphertext = oldEnvelope.payloadCiphertext

        val rotated = vault.rewrapDek(oldEnvelope, "v2")
        assertEquals("v2", rotated.keyVersion)
        assertArrayEquals(oldNonce, rotated.payloadNonce)
        assertArrayEquals(oldCiphertext, rotated.payloadCiphertext)
        vault.withCredential(account.copy(envelope = rotated), CredentialUsePurpose.AUTO_BUSINESS_LOGIN) { lease ->
            assertArrayEquals(login, lease.loginName)
            assertArrayEquals(password, lease.password)
        }
        oldNonce.fill(0)
        oldCiphertext.fill(0)
        login.fill('\u0000')
        password.fill('\u0000')
    }

    @Test
    fun `stored credential comparison matches and rejects either mismatch`() {
        val login = "M00000001".toCharArray()
        val password = "credential-compare-test".toCharArray()
        val account = sealAccount(login, password)
        assertTrue(vault.matchesStoredCredential(account, login, password))

        val wrongLogin = "M00000002".toCharArray()
        val wrongPassword = "different-test-value".toCharArray()
        assertFalse(vault.matchesStoredCredential(account, wrongLogin, password))
        assertFalse(vault.matchesStoredCredential(account, login, wrongPassword))
        login.fill('\u0000')
        password.fill('\u0000')
        wrongLogin.fill('\u0000')
        wrongPassword.fill('\u0000')
    }

    @Test
    fun `login tag preserves case and prefix`() {
        val upper = "S12345678".toCharArray()
        val lower = "s12345678".toCharArray()
        val withoutPrefix = "12345678".toCharArray()
        val upperTag = hasher.loginTag(upper)
        assertNotEquals(upperTag, hasher.loginTag(lower))
        assertNotEquals(upperTag, hasher.loginTag(withoutPrefix))
        assertEquals(32, upperTag.copyBytes().size)
        upper.fill('\u0000')
        lower.fill('\u0000')
        withoutPrefix.fill('\u0000')
    }

    @Test
    fun `cas verified result clears normalized login name`() {
        val verified = CasVerificationResult.Verified("S12345678".toCharArray())
        assertEquals("CasVerificationResult.Verified([REDACTED])", verified.toString())
        verified.close()
        assertArrayEquals(CharArray(9) { '\u0000' }, verified.normalizedLoginName)
    }

    private fun sealAccount(login: CharArray, password: CharArray): AccountRecord {
        val accountId = UUID.randomUUID()
        val loginTag = hasher.loginTag(login)
        val envelope = vault.seal(accountId, loginTag, 1, "v1", login, password)
        return AccountRecord(
            accountId = accountId,
            loginTag = loginTag,
            disabled = false,
            reauthRequired = false,
            credentialGeneration = 1,
            gateRevision = 1,
            pendingAttemptId = null,
            envelope = envelope,
        )
    }
}
