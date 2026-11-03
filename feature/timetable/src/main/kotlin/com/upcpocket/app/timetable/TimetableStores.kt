package com.upcpocket.app.timetable

import android.content.Context
import com.upcpocket.app.platform.crypto.ByteCrypto
import com.upcpocket.app.platform.crypto.KeystoreAesGcmCrypto
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object TimetableStorage {
    const val DIRECTORY = "timetable"
    const val SESSION_FILE = "timetable-session-v1.enc"
    const val CACHE_FILE = "timetable-cache-v1.json"
    const val SESSION_FORMAT = "upcpocket.timetable.session"
    const val CACHE_FORMAT = "upcpocket.timetable.cache"
    const val VERSION = 1
    const val KEY_ALIAS = "upcpocket.timetable-session.v1"
    const val MAX_SESSION_BYTES = 128 * 1024
    const val MAX_CACHE_BYTES = 2 * 1024 * 1024
    const val MAX_COOKIES = 64

    fun directory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DIRECTORY)

    fun deleteAtomic(directory: File, name: String): Boolean {
        val base = File(directory, name)
        var ok = true
        listOf(base, File(directory, "$name.bak"), File(directory, "$name.new")).forEach { file ->
            if (file.exists()) {
                if (!file.delete() && file.exists()) ok = false
            }
        }
        return ok
    }

    /**
     * 同目录临时文件 + 原子替换。AndroidX AtomicFile 的 rename 在 Windows JVM
     * 测试宿主机上遇到已存在目标时会失败；这里显式 replace，桌面/设备语义一致。
     */
    fun writeAtomic(file: File, bytes: ByteArray): Boolean {
        val directory = file.parentFile ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val temporary = File(directory, file.name + ".new")
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    fun readAtomic(file: File, maximumBytes: Int): ByteArray? = try {
        if (!file.isFile || file.length() > maximumBytes) {
            null
        } else {
            file.readBytes()
        }
    } catch (_: Exception) {
        null
    }
}

@Serializable
data class TimetableSession(
    val accountId: String,
    val credentialGeneration: Long,
    val businessGeneration: Long,
    val savedAtEpochMillis: Long,
    val cookies: List<TimetableCookie>,
) {
    init {
        require(accountId.matches(Regex("[A-Za-z0-9_-]{8,128}"))) { "非法账号标识" }
        require(credentialGeneration > 0) { "会话必须绑定凭据代次" }
        require(businessGeneration >= 0) { "非法业务代次" }
        require(cookies.isNotEmpty() && cookies.size <= TimetableStorage.MAX_COOKIES) {
            "课表会话 Cookie 数量无效"
        }
        require(cookies.all { it.domain == "app.upc.edu.cn" }) { "课表会话包含其他主机 Cookie" }
    }
}

@Serializable
private data class SessionEnvelope(
    val format: String = TimetableStorage.SESSION_FORMAT,
    val version: Int = TimetableStorage.VERSION,
    val ciphertext: String,
)

@Serializable
private data class SessionPayload(
    val version: Int = TimetableStorage.VERSION,
    val accountId: String,
    val credentialGeneration: Long,
    val businessGeneration: Long,
    val savedAtEpochMillis: Long,
    val cookies: List<TimetableCookie>,
)

/**
 * 独立课表业务会话的加密存储。
 *
 * 使用专属 Keystore alias，不读取统一账号密文；解密失败只影响课表业务。
 * 文件位于 noBackupFilesDir，写入通过同目录临时文件原子替换，删除确认 base/.bak/.new。
 */
class TimetableSessionStore internal constructor(
    private val directory: File,
    private val crypto: ByteCrypto,
) {
    constructor(context: Context) : this(
        TimetableStorage.directory(context),
        KeystoreAesGcmCrypto(TimetableStorage.KEY_ALIAS),
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val file = File(directory, TimetableStorage.SESSION_FILE)
    private val lock = Any()

    fun load(expectedAccountId: String, expectedCredentialGeneration: Long): TimetableSession? =
        synchronized(lock) {
            val bytes = TimetableStorage.readAtomic(
                file,
                TimetableStorage.MAX_SESSION_BYTES,
            ) ?: return null
            try {
                val envelope = json.decodeFromString(SessionEnvelope.serializer(), bytes.toString(Charsets.UTF_8))
                if (envelope.format != TimetableStorage.SESSION_FORMAT ||
                    envelope.version != TimetableStorage.VERSION
                ) {
                    return null
                }
                val plain = crypto.decrypt(Base64.getDecoder().decode(envelope.ciphertext))
                val payload = json.decodeFromString(
                    SessionPayload.serializer(),
                    plain.toString(Charsets.UTF_8),
                )
                if (payload.version != TimetableStorage.VERSION ||
                    payload.accountId != expectedAccountId ||
                    payload.credentialGeneration != expectedCredentialGeneration ||
                    payload.businessGeneration < 0 ||
                    payload.savedAtEpochMillis <= 0 ||
                    payload.cookies.isEmpty() ||
                    payload.cookies.size > TimetableStorage.MAX_COOKIES ||
                    payload.cookies.any { it.domain != "app.upc.edu.cn" }
                ) {
                    return null
                }
                TimetableSession(
                    accountId = payload.accountId,
                    credentialGeneration = payload.credentialGeneration,
                    businessGeneration = payload.businessGeneration,
                    savedAtEpochMillis = payload.savedAtEpochMillis,
                    cookies = payload.cookies,
                )
            } catch (_: Exception) {
                null
            }
        }

    fun save(session: TimetableSession): Boolean = synchronized(lock) {
        val payload = SessionPayload(
            accountId = session.accountId,
            credentialGeneration = session.credentialGeneration,
            businessGeneration = session.businessGeneration,
            savedAtEpochMillis = session.savedAtEpochMillis,
            cookies = session.cookies,
        )
        val plain = json.encodeToString(SessionPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        return try {
            val encrypted = crypto.encrypt(plain)
            if (encrypted.size > TimetableStorage.MAX_SESSION_BYTES) return false
            val envelope = SessionEnvelope(ciphertext = Base64.getEncoder().encodeToString(encrypted))
            val bytes = json.encodeToString(SessionEnvelope.serializer(), envelope)
                .toByteArray(Charsets.UTF_8)
            TimetableStorage.writeAtomic(file, bytes)
        } catch (_: Exception) {
            false
        } finally {
            plain.fill(0)
        }
    }

    fun clear(): Boolean = synchronized(lock) {
        TimetableStorage.deleteAtomic(directory, TimetableStorage.SESSION_FILE)
    }
}

@Serializable
data class TimetableCacheFile(
    val format: String = TimetableStorage.CACHE_FORMAT,
    val version: Int = TimetableStorage.VERSION,
    val accountId: String,
    val cachedAtEpochMillis: Long,
    val index: TimetableIndex? = null,
    val weeks: List<TimetableWeek> = emptyList(),
)

data class TimetableCache(
    val index: TimetableIndex?,
    val weeks: List<TimetableWeek>,
) {
    fun week(term: TimetableTerm, week: Int): TimetableWeek? =
        weeks.firstOrNull {
            it.week == week &&
                it.term.year == term.year &&
                it.term.term == term.term
        }
}

/**
 * 账号归属的已成功教学周缓存。
 *
 * 只保存完整成功响应；损坏或账号不一致的文件不展示、也不作为空周。
 * 同账号改密允许保留历史周（会话另行失效），换号/删号由宿主清理。
 */
class TimetableCacheStore internal constructor(
    private val directory: File,
) {
    constructor(context: Context) : this(TimetableStorage.directory(context))

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val file = File(directory, TimetableStorage.CACHE_FILE)
    private val lock = Any()

    fun load(accountId: String): TimetableCache = synchronized(lock) {
        val bytes = TimetableStorage.readAtomic(
            file,
            TimetableStorage.MAX_CACHE_BYTES,
        ) ?: return TimetableCache(null, emptyList())
        try {
            val parsed = json.decodeFromString(
                TimetableCacheFile.serializer(),
                bytes.toString(Charsets.UTF_8),
            )
            if (parsed.format != TimetableStorage.CACHE_FORMAT ||
                parsed.version != TimetableStorage.VERSION ||
                parsed.accountId != accountId ||
                parsed.cachedAtEpochMillis <= 0
            ) {
                return TimetableCache(null, emptyList())
            }
            val weeks = parsed.weeks.filter { it.complete }
            TimetableCache(parsed.index, weeks)
        } catch (_: Exception) {
            TimetableCache(null, emptyList())
        }
    }

    fun save(
        accountId: String,
        index: TimetableIndex?,
        week: TimetableWeek?,
        nowEpochMillis: Long,
    ): Boolean = synchronized(lock) {
        if (week == null && index == null) return false
        if (week != null && !week.complete) return false
        val existing = load(accountId)
        val weeks = existing.weeks
            .filterNot {
                week != null &&
                    it.week == week.week &&
                    it.term.year == week.term.year &&
                    it.term.term == week.term.term
            }
            .toMutableList()
        if (week != null) weeks += week
        val payload = TimetableCacheFile(
            accountId = accountId,
            cachedAtEpochMillis = nowEpochMillis.coerceAtLeast(1L),
            index = index ?: existing.index,
            weeks = weeks,
        )
        return try {
            val bytes = json.encodeToString(TimetableCacheFile.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
            if (bytes.size > TimetableStorage.MAX_CACHE_BYTES) return false
            TimetableStorage.writeAtomic(file, bytes)
        } catch (_: Exception) {
            false
        }
    }

    fun clear(): Boolean = synchronized(lock) {
        TimetableStorage.deleteAtomic(directory, TimetableStorage.CACHE_FILE)
    }
}

/** 插件未激活时宿主也能清理；不使用账号读取面，避免清理锁重入。 */
object TimetableFiles {
    fun clearSession(context: Context): Boolean =
        TimetableStorage.deleteAtomic(
            TimetableStorage.directory(context),
            TimetableStorage.SESSION_FILE,
        )

    fun clearAll(context: Context): Boolean {
        val directory = TimetableStorage.directory(context)
        val session = TimetableStorage.deleteAtomic(directory, TimetableStorage.SESSION_FILE)
        val cache = TimetableStorage.deleteAtomic(directory, TimetableStorage.CACHE_FILE)
        return session && cache
    }
}
