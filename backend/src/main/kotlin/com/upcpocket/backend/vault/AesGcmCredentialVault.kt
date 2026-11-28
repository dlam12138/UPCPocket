package com.upcpocket.backend.vault

import com.upcpocket.backend.contract.AccountRecord
import com.upcpocket.backend.contract.CredentialLease
import com.upcpocket.backend.contract.CredentialUsePurpose
import com.upcpocket.backend.contract.CredentialVault
import com.upcpocket.backend.contract.EncryptedEnvelope
import com.upcpocket.backend.contract.LoginTag
import com.upcpocket.backend.contract.MasterKeyProvider
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CredentialVaultException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class AesGcmCredentialVault(
    private val masterKeyProvider: MasterKeyProvider,
) : CredentialVault {
    private val random = SecureRandom()

    override fun seal(
        accountId: UUID,
        loginTag: LoginTag,
        credentialGeneration: Long,
        keyVersion: String,
        loginName: CharArray,
        password: CharArray,
    ): EncryptedEnvelope {
        require(credentialGeneration > 0) { "Credential generation must be positive" }
        val dek = ByteArray(DEK_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(EncryptedEnvelope.GCM_NONCE_LENGTH).also(random::nextBytes)
        var loginBytes: ByteArray? = null
        var passwordBytes: ByteArray? = null
        var payload: ByteArray? = null
        var aad: ByteArray? = null
        var ciphertext: ByteArray? = null
        var wrappedDek: ByteArray? = null
        try {
            val encodedLogin = SensitiveUtf8.encode(loginName)
            loginBytes = encodedLogin
            val encodedPassword = SensitiveUtf8.encode(password)
            passwordBytes = encodedPassword
            val payloadLength = try {
                Math.addExact(8, Math.addExact(encodedLogin.size, encodedPassword.size))
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("Credential payload is too large", e)
            }
            val clearPayload = ByteBuffer.allocate(payloadLength)
                .putInt(encodedLogin.size)
                .put(encodedLogin)
                .putInt(encodedPassword.size)
                .put(encodedPassword)
                .array()
            payload = clearPayload
            val additionalData = createAad(EncryptedEnvelope.CURRENT_FORMAT, accountId, loginTag, credentialGeneration)
            aad = additionalData
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, AES), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(additionalData)
            val encryptedPayload = cipher.doFinal(clearPayload)
            ciphertext = encryptedPayload
            val encryptedDek = masterKeyProvider.wrap(keyVersion, dek)
            wrappedDek = encryptedDek
            return EncryptedEnvelope(
                format = EncryptedEnvelope.CURRENT_FORMAT,
                payloadNonce = nonce,
                payloadCiphertext = encryptedPayload,
                keyVersion = keyVersion,
                wrappedDek = encryptedDek,
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw CredentialVaultException("Unable to seal credential envelope", e)
        } finally {
            dek.fill(0)
            nonce.fill(0)
            loginBytes?.fill(0)
            passwordBytes?.fill(0)
            payload?.fill(0)
            aad?.fill(0)
            ciphertext?.fill(0)
            wrappedDek?.fill(0)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun <T> withCredential(
        account: AccountRecord,
        purpose: CredentialUsePurpose,
        block: (CredentialLease) -> T,
    ): T {
        val envelope = account.envelope
        var dek: ByteArray? = null
        var wrappedDek: ByteArray? = null
        var nonce: ByteArray? = null
        var ciphertext: ByteArray? = null
        var aad: ByteArray? = null
        var payload: ByteArray? = null
        var lease: CredentialLease? = null
        try {
            try {
                if (envelope.format != EncryptedEnvelope.CURRENT_FORMAT) {
                    throw CredentialVaultException("Unsupported credential envelope format")
                }
                val wrappedDekBytes = envelope.wrappedDek
                wrappedDek = wrappedDekBytes
                val dekBytes = masterKeyProvider.unwrap(envelope.keyVersion, wrappedDekBytes)
                dek = dekBytes
                if (dekBytes.size != DEK_LENGTH) throw CredentialVaultException("Unwrapped DEK has an invalid size")
                val nonceBytes = envelope.payloadNonce
                nonce = nonceBytes
                val ciphertextBytes = envelope.payloadCiphertext
                ciphertext = ciphertextBytes
                val aadBytes = createAad(envelope.format, account.accountId, account.loginTag, account.credentialGeneration)
                aad = aadBytes
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dekBytes, AES), GCMParameterSpec(TAG_BITS, nonceBytes))
                cipher.updateAAD(aadBytes)
                val payloadBytes = cipher.doFinal(ciphertextBytes)
                payload = payloadBytes
                lease = decodePayload(payloadBytes)
            } catch (e: CredentialVaultException) {
                throw e
            } catch (e: Exception) {
                throw CredentialVaultException("Unable to open stored credentials", e)
            }
            return requireNotNull(lease).use(block)
        } finally {
            lease?.close()
            dek?.fill(0)
            wrappedDek?.fill(0)
            nonce?.fill(0)
            ciphertext?.fill(0)
            aad?.fill(0)
            payload?.fill(0)
        }
    }

    override fun matchesStoredCredential(
        account: AccountRecord,
        loginName: CharArray,
        password: CharArray,
    ): Boolean {
        var providedLoginBytes: ByteArray? = null
        var providedPasswordBytes: ByteArray? = null
        var storedLoginBytes: ByteArray? = null
        var storedPasswordBytes: ByteArray? = null
        return try {
            withCredential(account, CredentialUsePurpose.VERIFY_MANUAL_SIGN_IN_COMPARE) { lease ->
                val providedLogin = SensitiveUtf8.encode(loginName)
                providedLoginBytes = providedLogin
                val providedPassword = SensitiveUtf8.encode(password)
                providedPasswordBytes = providedPassword
                val storedLogin = SensitiveUtf8.encode(lease.loginName)
                storedLoginBytes = storedLogin
                val storedPassword = SensitiveUtf8.encode(lease.password)
                storedPasswordBytes = storedPassword
                val loginMatches = MessageDigest.isEqual(providedLogin, storedLogin)
                val passwordMatches = MessageDigest.isEqual(providedPassword, storedPassword)
                loginMatches && passwordMatches
            }
        } finally {
            providedLoginBytes?.fill(0)
            providedPasswordBytes?.fill(0)
            storedLoginBytes?.fill(0)
            storedPasswordBytes?.fill(0)
        }
    }

    override fun rewrapDek(envelope: EncryptedEnvelope, targetKeyVersion: String): EncryptedEnvelope {
        val oldWrappedDek = envelope.wrappedDek
        var newWrappedDek: ByteArray? = null
        val nonce = envelope.payloadNonce
        val ciphertext = envelope.payloadCiphertext
        return try {
            val replacement = masterKeyProvider.rewrap(envelope.keyVersion, targetKeyVersion, oldWrappedDek)
            newWrappedDek = replacement
            EncryptedEnvelope(
                format = envelope.format,
                payloadNonce = nonce,
                payloadCiphertext = ciphertext,
                keyVersion = targetKeyVersion,
                wrappedDek = replacement,
            )
        } finally {
            oldWrappedDek.fill(0)
            newWrappedDek?.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun decodePayload(payload: ByteArray): CredentialLease {
        var loginName: CharArray? = null
        var password: CharArray? = null
        try {
            val input = ByteBuffer.wrap(payload)
            val loginLength = readLength(input)
            val decodedLoginName = SensitiveUtf8.decode(payload, input.position(), loginLength)
            loginName = decodedLoginName
            input.position(input.position() + loginLength)
            val passwordLength = readLength(input)
            val decodedPassword = SensitiveUtf8.decode(payload, input.position(), passwordLength)
            password = decodedPassword
            input.position(input.position() + passwordLength)
            require(!input.hasRemaining()) { "Credential payload has trailing bytes" }
            val lease = CredentialLease(decodedLoginName, decodedPassword)
            loginName = null
            password = null
            return lease
        } catch (e: Exception) {
            throw CredentialVaultException("Stored credential payload is invalid", e)
        } finally {
            loginName?.fill('\u0000')
            password?.fill('\u0000')
        }
    }

    private fun readLength(input: ByteBuffer): Int {
        require(input.remaining() >= Int.SIZE_BYTES) { "Credential payload is truncated" }
        val length = input.int
        require(length >= 0 && length <= input.remaining()) { "Credential field length is invalid" }
        return length
    }

    private fun createAad(
        format: Short,
        accountId: UUID,
        loginTag: LoginTag,
        credentialGeneration: Long,
    ): ByteArray {
        val domain = AAD_DOMAIN.toByteArray(StandardCharsets.US_ASCII)
        val tag = loginTag.copyBytes()
        return try {
            ByteBuffer.allocate(Short.SIZE_BYTES + domain.size + UUID_BYTES + tag.size + Long.SIZE_BYTES)
                .putShort(format)
                .put(domain)
                .putLong(accountId.mostSignificantBits)
                .putLong(accountId.leastSignificantBits)
                .put(tag)
                .putLong(credentialGeneration)
                .array()
        } finally {
            tag.fill(0)
        }
    }

    companion object {
        private const val AES = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEK_LENGTH = 32
        private const val TAG_BITS = EncryptedEnvelope.GCM_TAG_LENGTH * 8
        private const val UUID_BYTES = 16
        private const val AAD_DOMAIN = "pocket-credential-v1"
    }
}
