package com.upcpocket.app.grades

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

/**
 * 成绩完整快照的本地加密存储。
 *
 * `noBackupDir` 必须是 `Context.noBackupFilesDir`；文件固定写入其下的 `grades/`
 * 子目录。只保存逐学期完整结果与归属所需的 accountId/凭据代次，不保存 Cookie、
 * 密码、原始响应或诊断正文。不同业务使用独立 alias，且密钥不可导出。
 */
class GradesFiles internal constructor(
    private val noBackupDir: File,
    private val crypto: ByteCrypto,
) {
    constructor(noBackupDir: File) : this(
        noBackupDir,
        KeystoreAesGcmCrypto(KEY_ALIAS),
    )

    private val directory: File = File(noBackupDir, DIRECTORY)
    private val file: File = File(directory, FILE_NAME)

    /**
     * 读取并验证完整快照，同时区分“从未保存”和“有文件但无法读取/解密”。
     *
     * 后者不能被页面当成“尚未同步”：同账号的旧缓存损坏或 Keystore 密钥丢失时，
     * 网络同步仍可修复，但页面必须表达本机存储失败。
     */
    internal fun loadResult(): GradesLoadResult {
        if (!file.exists()) return GradesLoadResult.Missing
        if (!file.isFile || file.length() > MAX_BYTES) return GradesLoadResult.Unreadable
        return try {
            val stored = readLimited(file, MAX_BYTES)
                ?: return GradesLoadResult.Unreadable
            val envelope = JSON.decodeFromString<GradesEnvelope>(
                stored.toString(Charsets.UTF_8),
            )
            if (envelope.format != FORMAT || envelope.version != VERSION) {
                return GradesLoadResult.Unreadable
            }
            val encrypted = Base64.getDecoder().decode(envelope.ciphertext)
            if (encrypted.size > MAX_BYTES) return GradesLoadResult.Unreadable
            val plain = crypto.decrypt(encrypted)
            if (plain.size > MAX_BYTES) return GradesLoadResult.Unreadable
            val payload = JSON.decodeFromString<GradesSnapshot>(
                plain.toString(Charsets.UTF_8),
            )
            if (!payload.isValidSnapshot()) {
                GradesLoadResult.Unreadable
            } else {
                GradesLoadResult.Loaded(payload)
            }
        } catch (_: Exception) {
            if (file.exists()) GradesLoadResult.Unreadable else GradesLoadResult.Missing
        }
    }

    /** 原子替换完整快照；失败时保留上一份可读文件。 */
    internal fun save(snapshot: GradesSnapshot): Boolean {
        return try {
            if (!snapshot.isValidSnapshot()) return false
            val plain = JSON.encodeToString(GradesSnapshot.serializer(), snapshot)
                .toByteArray(Charsets.UTF_8)
            if (plain.size > MAX_BYTES) return false
            val encrypted = crypto.encrypt(plain)
            val envelope = GradesEnvelope(
                format = FORMAT,
                version = VERSION,
                ciphertext = Base64.getEncoder().encodeToString(encrypted),
            )
            val stored = JSON.encodeToString(GradesEnvelope.serializer(), envelope)
                .toByteArray(Charsets.UTF_8)
            writeAtomic(file, stored)
        } catch (_: Exception) {
            false
        }
    }

    internal fun clear(): Boolean =
        deleteConfirmed(file) && deleteConfirmed(File(directory, "$FILE_NAME.new"))

    companion object {
        const val DIRECTORY = "grades"
        const val FILE_NAME = "grades-snapshot-v1.json"
        const val FORMAT = "upcpocket.grades.snapshot"
        const val VERSION = 1
        const val KEY_ALIAS = "upcpocket.grades.snapshot.v1"
        private const val MAX_BYTES = 16 * 1024 * 1024

        /**
         * 供插件缺失时的宿主清理调用；只删除本业务目录，不需要构造加密器或依赖 feature。
         *
         * 返回值表示当前可观察到的删除结果；重复调用必须安全。
         */
        @JvmStatic
        fun clearAll(noBackupDir: File): Boolean =
            deleteTree(File(noBackupDir, DIRECTORY))
    }
}

/** 本机快照读取结果；缺失与不可读取必须由业务区别表达。 */
internal sealed interface GradesLoadResult {
    data object Missing : GradesLoadResult

    data class Loaded(val snapshot: GradesSnapshot) : GradesLoadResult

    data object Unreadable : GradesLoadResult
}

@Serializable
internal data class GradesSnapshot(
    val version: Int = GradesFiles.VERSION,
    val accountId: String,
    val credentialGeneration: Long,
    val savedAtEpochMillis: Long,
    val terms: List<GradesTerm> = emptyList(),
    val currentTerm: GradesTerm? = null,
    val results: List<GradesTermResult> = emptyList(),
    val lastSyncRetainedTerms: List<GradesTerm> = emptyList(),
    val lastSyncFailedTerms: List<GradesTerm> = emptyList(),
    val lastFailure: GradesFailure? = null,
) {
    fun isValidSnapshot(): Boolean {
        if (version != GradesFiles.VERSION) return false
        if (!accountId.matches(Regex("[A-Za-z0-9_-]{8,128}"))) return false
        if (credentialGeneration <= 0 || savedAtEpochMillis < 0) return false
        if (terms.isEmpty() || terms.size > MAX_TERMS) return false
        val termKeys = terms.map { it.key }
        if (termKeys.toSet().size != termKeys.size) return false
        if (currentTerm != null && currentTerm.key !in termKeys) return false
        if (results.size > terms.size) return false
        val resultKeys = results.map { it.term.key }
        if (resultKeys.toSet().size != resultKeys.size) return false
        if (resultKeys.any { it !in termKeys }) return false
        val retainedKeys = lastSyncRetainedTerms.map { it.key }
        val failedKeys = lastSyncFailedTerms.map { it.key }
        if (retainedKeys.toSet().size != retainedKeys.size) return false
        if (failedKeys.toSet().size != failedKeys.size) return false
        if (retainedKeys.any { it !in termKeys } || failedKeys.any { it !in termKeys }) {
            return false
        }
        if (retainedKeys.any { it in failedKeys }) return false
        val resultKeySet = resultKeys.toSet()
        // 沿用学期必须有逐项结果；无结果的失败学期只能出现在 failed 列表。
        if (retainedKeys.any { it !in resultKeySet }) return false
        if (failedKeys.any { it in resultKeySet }) return false
        if ((retainedKeys.isEmpty() && failedKeys.isEmpty()) != (lastFailure == null)) {
            return false
        }
        return results.all { result ->
            result.fetchedAtEpochMillis >= 0 &&
                result.courses.size <= GradesProtocol.MAX_COURSES_PER_TERM &&
                result.courses.all { it.term.key == result.term.key }
        }
    }
}

@Serializable
private data class GradesEnvelope(
    val format: String,
    val version: Int,
    val ciphertext: String,
)

private val JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

/** 快照允许的最大学期数；放在顶层，避免为常量声明私有伴生而遮蔽序列化器。 */
private const val MAX_TERMS = 200

private fun readLimited(file: File, maximumBytes: Int): ByteArray? = try {
    if (!file.isFile || file.length() > maximumBytes) {
        null
    } else {
        file.readBytes()
    }
} catch (_: Exception) {
    null
}

private fun writeAtomic(file: File, bytes: ByteArray): Boolean {
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

private fun deleteConfirmed(file: File): Boolean {
    if (!file.exists()) return true
    return file.delete() || !file.exists()
}

private fun deleteTree(directory: File): Boolean {
    if (!directory.exists()) return true
    if (!directory.isDirectory) return deleteConfirmed(directory)
    var ok = true
    directory.listFiles()?.forEach { child ->
        ok = if (child.isDirectory) deleteTree(child) && ok else deleteConfirmed(child) && ok
    }
    return deleteConfirmed(directory) && ok
}
