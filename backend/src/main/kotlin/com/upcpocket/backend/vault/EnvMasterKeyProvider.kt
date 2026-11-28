package com.upcpocket.backend.vault

import com.upcpocket.backend.contract.MasterKeyProvider
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local/environment KEK provider for isolated development. Replace it with a school-managed KMS
 * adapter before production; the provider interface is intentionally independent of this class.
 */
class EnvMasterKeyProvider(
    masterKeys: String?,
    masterKeysFile: String?,
) : MasterKeyProvider, AutoCloseable {
    private val random = SecureRandom()
    private val keys = parseKeys(SecretSource.read(masterKeys, masterKeysFile, "master keys"))
    private var closed = false

    @Synchronized
    override fun configuredVersions(): Set<String> {
        checkOpen()
        return keys.keys.toSet()
    }

    @Synchronized
    override fun wrap(keyVersion: String, dek: ByteArray): ByteArray {
        checkOpen()
        require(dek.size == DEK_LENGTH) { "DEK must be $DEK_LENGTH bytes" }
        val key = keys[keyVersion] ?: throw IllegalArgumentException("Unknown master key version")
        val nonce = ByteArray(NONCE_LENGTH)
        var encrypted: ByteArray? = null
        random.nextBytes(nonce)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, nonce))
            val encryptedBytes = cipher.doFinal(dek)
            encrypted = encryptedBytes
            ByteArray(nonce.size + encryptedBytes.size).also {
                nonce.copyInto(it)
                encryptedBytes.copyInto(it, destinationOffset = nonce.size)
            }
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Unable to wrap DEK", e)
        } finally {
            nonce.fill(0)
            encrypted?.fill(0)
        }
    }

    @Synchronized
    override fun unwrap(keyVersion: String, wrappedDek: ByteArray): ByteArray {
        checkOpen()
        require(wrappedDek.size >= NONCE_LENGTH + TAG_LENGTH) { "Wrapped DEK is malformed" }
        val key = keys[keyVersion] ?: throw IllegalArgumentException("Unknown master key version")
        val nonce = wrappedDek.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = wrappedDek.copyOfRange(NONCE_LENGTH, wrappedDek.size)
        var dek: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, nonce))
            val unwrapped = cipher.doFinal(ciphertext)
            dek = unwrapped
            if (unwrapped.size != DEK_LENGTH) {
                unwrapped.fill(0)
                dek = null
                throw IllegalStateException("Unwrapped DEK has an invalid size")
            }
            return unwrapped
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Unable to unwrap DEK", e)
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    @Synchronized
    override fun rewrap(fromVersion: String, toVersion: String, wrappedDek: ByteArray): ByteArray {
        val dek = unwrap(fromVersion, wrappedDek)
        return try {
            wrap(toVersion, dek)
        } finally {
            dek.fill(0)
        }
    }

    @Synchronized
    override fun healthCheck(): Boolean = !closed && keys.isNotEmpty() && keys.values.all { it.size == DEK_LENGTH }

    @Synchronized
    override fun close() {
        if (!closed) {
            keys.values.forEach { it.fill(0) }
            closed = true
        }
    }

    private fun checkOpen() {
        check(!closed) { "Master key provider is closed" }
    }

    private fun parseKeys(configuration: String?): MutableMap<String, ByteArray> {
        val parsed = linkedMapOf<String, ByteArray>()
        if (configuration.isNullOrBlank()) return parsed
        require(configuration.length <= MAX_CONFIGURATION_LENGTH) { "Master key configuration is too large" }

        try {
            configuration.split(',').forEach { entry ->
                val separator = entry.indexOf(':')
                require(separator > 0 && separator < entry.lastIndex) { "Master key configuration is malformed" }
                val version = entry.substring(0, separator).trim()
                require(version.matches(KEY_VERSION_PATTERN)) { "Master key version is malformed" }
                require(version !in parsed) { "Master key version is duplicated" }
                val key = Base64.getDecoder().decode(entry.substring(separator + 1).trim())
                if (key.size != DEK_LENGTH) {
                    key.fill(0)
                    throw IllegalArgumentException("Master key must be $DEK_LENGTH bytes")
                }
                parsed[version] = key
            }
            return parsed
        } catch (e: RuntimeException) {
            parsed.values.forEach { it.fill(0) }
            if (e is IllegalArgumentException && e.message?.startsWith("Master key") == true) throw e
            throw IllegalArgumentException("Master key configuration is invalid", e)
        }
    }

    companion object {
        private const val AES = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEK_LENGTH = 32
        private const val NONCE_LENGTH = 12
        private const val TAG_LENGTH = 16
        private const val TAG_BITS = TAG_LENGTH * 8
        private const val MAX_CONFIGURATION_LENGTH = 16 * 1024
        private val KEY_VERSION_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
    }
}
