package com.upcpocket.app.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The only persisted Pocket session fields are encrypted with this independent alias. */
internal object ManagedSessionFormat {
    const val DIRECTORY = "managed-session"
    const val SESSION_FILE = "managed-session-v1.enc"
    const val CONFIG_FILE = "managed-session-config-v1.json"
    const val MIGRATION_FILE = "legacy-password-cleanup-v2.json"
    const val CLEANUP_FILE = "managed-personal-cleanup-v1.json"
    const val KEY_ALIAS = "upcpocket.managed-session.v1"
    const val ENVELOPE_FORMAT = "upcpocket.managed-session"
    const val CONFIG_FORMAT = "upcpocket.managed-session.config"
    const val MIGRATION_FORMAT = "upcpocket.legacy-password-cleanup"
    const val VERSION = 1
}

internal enum class ManagedSessionState {
    ACTIVE,
    UNBIND_PENDING,
    SIGN_OUT_PENDING,
    DEVICE_REVOKE_PENDING,
}

@Serializable
internal data class ManagedSessionRecord(
    val accessToken: String,
    val deviceId: String,
    val accountId: String,
    val credentialGeneration: Long,
    val state: String,
    /** True only after the local PERSONAL_DATA invalidation completed durably. */
    val personalDataCleared: Boolean = false,
) {
    override fun toString(): String =
        "ManagedSessionRecord(accessToken=[REDACTED], deviceId=[REDACTED], " +
            "accountId=[REDACTED], credentialGeneration=$credentialGeneration, state=$state)"
}

@Serializable
private data class ManagedSessionEnvelope(
    val format: String = ManagedSessionFormat.ENVELOPE_FORMAT,
    val version: Int = ManagedSessionFormat.VERSION,
    val iv: String,
    val ciphertext: String,
)

@Serializable
internal data class ManagedSessionConfig(
    val format: String = ManagedSessionFormat.CONFIG_FORMAT,
    val version: Int = ManagedSessionFormat.VERSION,
    val backendOrigin: String,
    val installationId: String,
    val transactionGeneration: Long,
)

@Serializable
internal data class LegacyPasswordCleanupMarker(
    val format: String = ManagedSessionFormat.MIGRATION_FORMAT,
    val version: Int = ManagedSessionFormat.VERSION,
    val pending: Boolean,
    val transactionGeneration: Long,
)

@Serializable
internal data class ManagedCleanupMarker(
    val format: String = "upcpocket.managed-personal-cleanup",
    val version: Int = ManagedSessionFormat.VERSION,
    val scope: String,
    val reason: String,
    val previousGeneration: Long,
    val nextGeneration: Long,
    val transactionGeneration: Long,
)

/** AES-GCM session file and non-secret origin/device configuration. */
internal class ManagedSessionStorage(
    noBackupDirectory: File,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val crypto: ManagedSessionCrypto = AndroidManagedSessionCrypto(),
) {
    private val directory = File(noBackupDirectory, ManagedSessionFormat.DIRECTORY)
    private val session = File(directory, ManagedSessionFormat.SESSION_FILE)
    private val config = File(directory, ManagedSessionFormat.CONFIG_FILE)
    private val migration = File(noBackupDirectory, ManagedSessionFormat.MIGRATION_FILE)
    private val cleanup = File(noBackupDirectory, ManagedSessionFormat.CLEANUP_FILE)

    fun loadOrCreateConfig(backendOrigin: String?): ManagedSessionConfig {
        ensureDirectory()
        val previous = readJson(config, MAX_CONFIG_BYTES) { raw ->
            json.decodeFromString<ManagedSessionConfig>(raw)
        }?.takeIf(::validConfig)
        val origin = normalizeOrigin(backendOrigin)
        val result = previous ?: run {
            ManagedSessionConfig(
                backendOrigin = origin,
                installationId = UUID.randomUUID().toString(),
                transactionGeneration = 0,
            )
        }
        if (previous == null) writeAtomic(config, json.encodeToString(result))
        return result
    }

    /** Read the origin before config creation so a missing/corrupt config cannot bless an old token. */
    fun existingBackendOrigin(): String? = readJson(config, MAX_CONFIG_BYTES) { raw ->
        json.decodeFromString<ManagedSessionConfig>(raw)
    }?.takeIf(::validConfig)?.backendOrigin

    fun hasSessionArtifacts(): Boolean =
        listOf(session, File(session.path + ".bak"), File(session.path + ".new")).any(File::exists)

    /** Origin changes are committed only after the caller has completed personal-data cleanup. */
    fun updateBackendOrigin(
        configValue: ManagedSessionConfig,
        backendOrigin: String?,
    ): ManagedSessionConfig = configValue.copy(backendOrigin = normalizeOrigin(backendOrigin)).also {
        require(validConfig(it))
        writeAtomic(config, json.encodeToString(it))
    }

    fun advanceTransaction(configValue: ManagedSessionConfig): ManagedSessionConfig =
        configValue.copy(transactionGeneration = configValue.transactionGeneration + 1).also {
            require(it.transactionGeneration > configValue.transactionGeneration)
            writeAtomic(config, json.encodeToString(it))
        }

    fun loadSession(): ManagedSessionRecord? {
        val bytes = readRaw(session, MAX_SESSION_BYTES) ?: return null
        val envelope = try {
            json.decodeFromString<ManagedSessionEnvelope>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw ManagedStorageException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        } catch (_: IllegalArgumentException) {
            throw ManagedStorageException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        }
        if (envelope.format != ManagedSessionFormat.ENVELOPE_FORMAT ||
            envelope.version != ManagedSessionFormat.VERSION
        ) {
            throw ManagedStorageException(SchoolAccountErrorCode.UNSUPPORTED_VERSION)
        }
        val iv = decodeBase64(envelope.iv)
        val ciphertext = decodeBase64(envelope.ciphertext)
        val plain = try {
            crypto.decrypt(iv, ciphertext)
        } catch (_: Exception) {
            throw ManagedStorageException(SchoolAccountErrorCode.CREDENTIAL_UNAVAILABLE)
        }
        val record = try {
            json.decodeFromString<ManagedSessionRecord>(plain.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw ManagedStorageException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        } catch (_: IllegalArgumentException) {
            throw ManagedStorageException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        } finally {
            plain.fill(0)
        }
        if (!validRecord(record)) throw ManagedStorageException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
        return record
    }

    fun saveSession(record: ManagedSessionRecord) {
        require(validRecord(record))
        val plain = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        val encrypted = try {
            crypto.encrypt(plain)
        } catch (_: Exception) {
            throw ManagedStorageException(SchoolAccountErrorCode.ENCRYPTION_FAILED)
        } finally {
            plain.fill(0)
        }
        try {
            val envelope = ManagedSessionEnvelope(
                iv = Base64.getEncoder().encodeToString(encrypted.first),
                ciphertext = Base64.getEncoder().encodeToString(encrypted.second),
            )
            writeAtomic(session, json.encodeToString(envelope))
        } finally {
            encrypted.first.fill(0)
            encrypted.second.fill(0)
        }
    }

    fun clearSession() = deleteAtomic(session)

    fun readMigrationMarker(): LegacyPasswordCleanupMarker? =
        readJson(migration, MAX_CONFIG_BYTES) { raw ->
            json.decodeFromString<LegacyPasswordCleanupMarker>(raw)
        }?.takeIf {
            it.format == ManagedSessionFormat.MIGRATION_FORMAT &&
                it.version == ManagedSessionFormat.VERSION &&
                it.transactionGeneration >= 0
        }

    fun writeMigrationMarker(marker: LegacyPasswordCleanupMarker) {
        writeAtomic(migration, json.encodeToString(marker))
    }

    fun deleteMigrationMarker() = deleteAtomic(migration)

    fun readCleanupMarker(): ManagedCleanupMarker? = readJson(cleanup, MAX_CONFIG_BYTES) { raw ->
        json.decodeFromString<ManagedCleanupMarker>(raw)
    }?.takeIf {
        it.format == "upcpocket.managed-personal-cleanup" &&
            it.version == ManagedSessionFormat.VERSION &&
            it.previousGeneration >= 0 && it.nextGeneration >= it.previousGeneration &&
            it.transactionGeneration >= 0 &&
            it.scope in setOf("SESSIONS_ONLY", "PERSONAL_DATA") &&
            it.reason.matches(Regex("[A-Z_]{1,40}"))
    }

    fun writeCleanupMarker(marker: ManagedCleanupMarker) {
        writeAtomic(cleanup, json.encodeToString(marker))
    }

    fun deleteCleanupMarker() = deleteAtomic(cleanup)

    private fun validConfig(value: ManagedSessionConfig): Boolean =
        value.format == ManagedSessionFormat.CONFIG_FORMAT &&
            value.version == ManagedSessionFormat.VERSION &&
            value.transactionGeneration >= 0 &&
            runCatching { UUID.fromString(value.installationId) }.isSuccess

    private fun normalizeOrigin(value: String?): String = value?.trim()?.trimEnd('/').orEmpty()

    private fun validRecord(value: ManagedSessionRecord): Boolean =
        value.accessToken.isNotBlank() && value.accessToken.length <= MAX_TOKEN_CHARS &&
        value.state in ManagedSessionState.entries.map(ManagedSessionState::name) &&
            value.credentialGeneration > 0 &&
            isUuid(value.accountId) && isUuid(value.deviceId)

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.getDecoder().decode(value).also {
            if (it.isEmpty() || it.size > MAX_SESSION_BYTES) throw IllegalArgumentException()
        }
    } catch (_: IllegalArgumentException) {
        throw ManagedStorageException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw ManagedStorageException(SchoolAccountErrorCode.STORAGE_FAILED)
        }
        if (!directory.isDirectory) throw ManagedStorageException(SchoolAccountErrorCode.STORAGE_FAILED)
    }

    private fun <T> readJson(file: File, maxBytes: Int, decode: (String) -> T): T? =
        readRaw(file, maxBytes)?.let { bytes ->
            try {
                decode(bytes.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }

    private fun readRaw(file: File, maxBytes: Int): ByteArray? {
        val backup = File(file.path + ".bak")
        val target = when {
            file.exists() -> file
            backup.exists() -> backup
            else -> return null
        }
        return try {
            FileInputStream(target).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) {
                        throw ManagedStorageException(SchoolAccountErrorCode.CREDENTIAL_CORRUPTED)
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (failure: ManagedStorageException) {
            throw failure
        } catch (_: Exception) {
            throw ManagedStorageException(SchoolAccountErrorCode.STORAGE_FAILED)
        }
    }

    private fun writeAtomic(file: File, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val tmp = File(file.path + ".new")
        val backup = File(file.path + ".bak")
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw ManagedStorageException(SchoolAccountErrorCode.STORAGE_FAILED)
            }
            FileOutputStream(tmp).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (backup.exists()) backup.delete()
        } catch (failure: ManagedStorageException) {
            tmp.delete()
            throw failure
        } catch (_: Exception) {
            tmp.delete()
            throw ManagedStorageException(SchoolAccountErrorCode.STORAGE_FAILED)
        } finally {
            bytes.fill(0)
        }
    }

    private fun deleteAtomic(file: File) {
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { candidate ->
            if (candidate.exists() && !candidate.delete()) {
                throw ManagedStorageException(SchoolAccountErrorCode.STORAGE_FAILED)
            }
            if (candidate.exists()) throw ManagedStorageException(SchoolAccountErrorCode.STORAGE_FAILED)
        }
    }

    private companion object {
        const val MAX_SESSION_BYTES = 16 * 1024
        const val MAX_CONFIG_BYTES = 8 * 1024
        const val MAX_TOKEN_CHARS = 8 * 1024
    }
}

internal class ManagedStorageException(val code: SchoolAccountErrorCode) :
    Exception(null, null, false, false)

internal interface ManagedSessionCrypto {
    fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray
}

private class AndroidManagedSessionCrypto : ManagedSessionCrypto {
    override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return cipher.iv to cipher.doFinal(plain)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ManagedSessionFormat.KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw java.security.UnrecoverableKeyException("managed session key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    ManagedSessionFormat.KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
