package com.upcpocket.backend.vault

import com.upcpocket.backend.contract.BlindIndexHasher
import com.upcpocket.backend.contract.LoginTag
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacBlindIndexHasher(
    loginIndexKey: String?,
    loginIndexKeyFile: String?,
    auditIndexKey: String?,
    auditIndexKeyFile: String?,
) : BlindIndexHasher, AutoCloseable {
    private val loginKey = decodeKey(SecretSource.read(loginIndexKey, loginIndexKeyFile, "login index key"), "login index key")
    private val auditKey = try {
        decodeKey(SecretSource.read(auditIndexKey, auditIndexKeyFile, "audit index key"), "audit index key")
    } catch (e: RuntimeException) {
        loginKey.fill(0)
        throw e
    }
    private var closed = false

    init {
        if (MessageDigest.isEqual(loginKey, auditKey)) {
            loginKey.fill(0)
            auditKey.fill(0)
            throw IllegalArgumentException("Login and audit index keys must be independent")
        }
    }

    @Synchronized
    override fun loginTag(loginName: CharArray): LoginTag {
        checkOpen()
        require(loginName.isNotEmpty()) { "Login name cannot be empty" }
        val encoded = SensitiveUtf8.encode(loginName)
        var digest: ByteArray? = null
        return try {
            val hash = hmac(loginKey, encoded)
            digest = hash
            LoginTag(hash)
        } finally {
            encoded.fill(0)
            digest?.fill(0)
        }
    }

    @Synchronized
    override fun auditRef(rawSubject: String): ByteArray {
        checkOpen()
        val characters = rawSubject.toCharArray()
        var encoded: ByteArray? = null
        return try {
            encoded = SensitiveUtf8.encode(characters)
            hmac(auditKey, encoded)
        } finally {
            characters.fill('\u0000')
            encoded?.fill(0)
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            loginKey.fill(0)
            auditKey.fill(0)
            closed = true
        }
    }

    private fun checkOpen() {
        check(!closed) { "Blind index hasher is closed" }
    }

    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(key, HMAC_SHA_256))
        return mac.doFinal(bytes)
    }

    private fun decodeKey(value: String?, label: String): ByteArray {
        require(!value.isNullOrBlank()) { "$label is required" }
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$label must be Base64", e)
        }
        if (decoded.size != LoginTag.BYTE_LENGTH) {
            decoded.fill(0)
            throw IllegalArgumentException("$label must be ${LoginTag.BYTE_LENGTH} bytes")
        }
        return decoded
    }

    companion object {
        private const val HMAC_SHA_256 = "HmacSHA256"
    }
}
