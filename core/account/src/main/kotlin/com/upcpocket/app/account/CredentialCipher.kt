package com.upcpocket.app.account

import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.platform.crypto.ByteCrypto
import java.security.GeneralSecurityException
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 版本化凭据信封。
 *
 * 外层 JSON 只含格式、版本和 Base64 密文；账号、密码、代次都在 AES-GCM 内的
 * 内层 JSON 中。任何格式错误、错误密钥或完整性校验失败都以固定错误码返回。
 */
internal class CredentialCipher(
    private val crypto: ByteCrypto,
    private val json: Json = Json { encodeDefaults = true },
) {
    fun seal(payload: CredentialPayload): ByteArray {
        val plain = json.encodeToString(CredentialPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        return try {
            val encrypted = crypto.encrypt(plain)
            val envelope = CredentialEnvelope(
                ciphertext = Base64.getEncoder().encodeToString(encrypted),
            )
            json.encodeToString(CredentialEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            throw AccountStorageException(SchoolAccountErrorCode.ENCRYPTION_FAILED)
        } finally {
            plain.fill(0)
        }
    }

    fun open(bytes: ByteArray): CredentialPayload {
        val envelope = try {
            json.decodeFromString(CredentialEnvelope.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        } catch (_: IllegalArgumentException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }
        if (envelope.format != AccountFormat.CREDENTIAL_FORMAT) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }
        if (envelope.version != AccountFormat.VERSION) {
            throw AccountReadException(SchoolAccountErrorCode.UNSUPPORTED_VERSION)
        }

        val encrypted = try {
            Base64.getDecoder().decode(envelope.ciphertext)
        } catch (_: IllegalArgumentException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }
        val plain = try {
            crypto.decrypt(encrypted)
        } catch (_: GeneralSecurityException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE)
        } catch (_: SecurityException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE)
        } catch (_: Exception) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }

        val payload = try {
            json.decodeFromString(CredentialPayload.serializer(), plain.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        } catch (_: IllegalArgumentException) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        } finally {
            plain.fill(0)
        }
        if (payload.version != AccountFormat.VERSION) {
            throw AccountReadException(SchoolAccountErrorCode.UNSUPPORTED_VERSION)
        }
        if (payload.generation < 0 || payload.savedAt <= 0 ||
            payload.account.isBlank() || payload.account.length > MAX_ACCOUNT_CHARS ||
            payload.password.isEmpty() || payload.password.length > MAX_PASSWORD_CHARS
        ) {
            throw AccountReadException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }
        return payload
    }

    private companion object {
        const val MAX_ACCOUNT_CHARS = 128
        const val MAX_PASSWORD_CHARS = 512
    }
}
