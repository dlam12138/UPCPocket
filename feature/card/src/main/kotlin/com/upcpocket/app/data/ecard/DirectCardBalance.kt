package com.upcpocket.app.data.ecard

import android.util.AtomicFile
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendDataSource
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.platform.RefreshInvalidatedException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A read failure is kept separate from an account/cache failure; it never deletes an old snapshot. */
internal enum class DirectCardBalanceFailureKind { NETWORK, HTTP, SCHEMA, STORAGE, CLEANUP }

class CampusCardException internal constructor(
    message: String,
    internal val failureKind: DirectCardBalanceFailureKind?,
) : IOException(message) {
    constructor(message: String) : this(message, null)
}

/** Owner bound balance snapshot. The persisted format intentionally contains no student ID. */
data class DirectCardSnapshot(
    val accountReference: SchoolAccountReference,
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAt: Long,
) {
    val balanceText: String get() = BigDecimal.valueOf(balanceCents, 2).toPlainString()
    val transitionText: String get() = BigDecimal.valueOf(transitionCents, 2).toPlainString()
    val totalBalanceText: String get() = BigDecimal.valueOf(balanceCents, 2)
        .add(BigDecimal.valueOf(transitionCents, 2)).toPlainString()

    /** Credential rotations keep same-account offline history; transaction guards use the full ref. */
    fun belongsTo(binding: CardBalanceBinding): Boolean =
        accountReference.accountId == binding.accountReference.accountId

    fun sameOwnerAs(binding: CardBalanceBinding): Boolean = belongsTo(binding)
}

@Serializable
private data class StoredCardBalance(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val accountId: String,
    val credentialGeneration: Long,
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAt: Long,
) {
    companion object {
        const val FORMAT = "upcpocket.card-balance"
        const val VERSION = 3
    }
}

@Serializable
private data class ClearMarker(val format: String = "upcpocket.card-balance.clear", val version: Int = 3)

/**
 * Version 3 cache belongs to a backend account reference and deliberately has no student ID.
 * Version 1/2 files are never decoded or migrated; cleanup removes them with their AtomicFile
 * backup/new siblings so an older identity cannot reappear after a restart.
 */
class DirectCardBalanceStore(directory: File) {
    private val current = AtomicFile(File(directory, CURRENT_FILE))
    private val clearMarker = AtomicFile(File(directory, CLEAR_MARKER_FILE))

    fun loadCurrent(binding: CardBalanceBinding): DirectCardSnapshot? {
        if (isClearPending()) return null
        val parsed = read(current, MAX_CACHE_BYTES)
            ?.let { runCatching { json.decodeFromString<StoredCardBalance>(it) }.getOrNull() }
            ?: return null
        if (parsed.format != FORMAT || parsed.version != VERSION || parsed.fetchedAt <= 0L) return null
        if (parsed.accountId != binding.accountReference.accountId.value) return null
        if (parsed.credentialGeneration <= 0L) return null
        return DirectCardSnapshot(
            accountReference = SchoolAccountReference(
                accountId = SchoolAccountId(parsed.accountId),
                credentialGeneration = AccountGeneration(parsed.credentialGeneration),
            ),
            balanceCents = parsed.balanceCents,
            transitionCents = parsed.transitionCents,
            fetchedAt = parsed.fetchedAt,
        )
    }

    fun saveCurrent(value: DirectCardSnapshot) {
        val stored = StoredCardBalance(
            accountId = value.accountReference.accountId.value,
            credentialGeneration = value.accountReference.credentialGeneration.value,
            balanceCents = value.balanceCents,
            transitionCents = value.transitionCents,
            fetchedAt = value.fetchedAt,
        )
        write(current, json.encodeToString(stored).toByteArray(Charsets.UTF_8))
    }

    fun beginClear() {
        write(clearMarker, json.encodeToString(ClearMarker()).toByteArray(Charsets.UTF_8))
    }

    fun isClearPending(): Boolean = exists(clearMarker.baseFile)

    /** Delete the active v3 file and all legacy student-ID-bearing balance/cache files. */
    fun clearBalanceData() {
        deleteAtomic(current)
        LEGACY_FILES.forEach { name -> deleteBaseAndSiblings(File(current.baseFile.parentFile, name)) }
    }

    fun finishClear() = deleteBaseAndSiblings(clearMarker.baseFile)

    /** Account invalidation uses the same durable hide-before-delete barrier. */
    fun clearAll() {
        beginClear()
        clearBalanceData()
        finishClear()
    }

    private fun read(file: AtomicFile, limit: Int): String? {
        val input = try {
            file.openRead()
        } catch (_: FileNotFoundException) {
            return null
        }
        return input.use {
            val bytes = it.readBytes()
            require(bytes.size <= limit)
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun exists(base: File): Boolean =
        base.exists() || File(base.path + ".bak").exists() || File(base.path + ".new").exists()

    private fun deleteAtomic(file: AtomicFile) = deleteBaseAndSiblings(file.baseFile)

    private fun deleteBaseAndSiblings(base: File) {
        listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach { file ->
            if (file.exists() && !file.delete()) throw IOException("校园卡缓存清理失败")
            if (file.exists()) throw IOException("校园卡缓存清理失败")
        }
    }

    private companion object {
        const val CURRENT_FILE = "card-balance-v3.json"
        const val CLEAR_MARKER_FILE = "card-balance-clear-v3.json"
        const val FORMAT = "upcpocket.card-balance"
        const val VERSION = 3
        const val MAX_CACHE_BYTES = 8 * 1024
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        val LEGACY_FILES = listOf(
            "card-balance-v1.json",
            "card-balance-v2.json",
            "card-balance-migration-v2.json",
            "card-balance-migration-v3.json",
            "card-balance-clear-v2.json",
        )
    }
}

internal data class CachedRead(
    val snapshot: DirectCardSnapshot?,
    val valid: Boolean,
    val snapshotRevision: Long = 0,
)

/**
 * Owner and business epochs are captured before the backend call and checked again before cache
 * commit. Backend errors leave the previous same-owner cache and timestamp untouched.
 */
class DirectCardBalanceRepository internal constructor(
    private val store: DirectCardBalanceStore,
    private val account: SchoolAccountReferenceReader,
    private val backend: CardBackendDataSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val afterCacheRead: suspend () -> Unit = {},
    private val afterBlockingCacheRead: () -> Unit = {},
    private val beforeStoreSave: () -> Unit = {},
) {
    private val mutableSnapshot = MutableStateFlow<DirectCardSnapshot?>(null)
    val snapshot: Flow<DirectCardSnapshot?> = combine(
        mutableSnapshot,
        account.state,
        account.accountReference,
    ) { raw, state, reference ->
        val binding = currentCardBinding(state, reference) ?: return@combine null
        raw?.takeIf { it.belongsTo(binding) }
    }

    private val fileLock = Mutex()
    private val refreshLock = Mutex()
    private val businessEpoch = AtomicLong(0)
    private val snapshotLock = Any()
    private var snapshotRevision = 0L
    @Volatile private var clearing = false

    fun currentBinding(): CardBalanceBinding? = currentCardBinding(account)
    fun businessEpochNow(): Long = businessEpoch.get()
    fun snapshotRevisionNow(): Long = synchronized(snapshotLock) { snapshotRevision }

    private fun snapshotState(): Pair<DirectCardSnapshot?, Long> = synchronized(snapshotLock) {
        mutableSnapshot.value to snapshotRevision
    }

    suspend fun cached(): DirectCardSnapshot? = withContext(Dispatchers.IO) {
        val binding = currentBinding() ?: return@withContext null
        cacheRead(binding).takeIf { it.valid }?.snapshot
    }

    internal suspend fun cachedForWidget(binding: CardBalanceBinding): CachedRead =
        withContext(Dispatchers.IO) { cacheRead(binding) }

    fun cachedBlocking(): DirectCardSnapshot? {
        val binding = currentBinding() ?: return null
        return cacheReadBlocking(binding).takeIf { it.valid }?.snapshot
    }

    internal fun cachedBlockingForWidget(binding: CardBalanceBinding): CachedRead = cacheReadBlocking(binding)

    private suspend fun cacheRead(binding: CardBalanceBinding): CachedRead {
        recoverPendingClear()
        val startEpoch = businessEpoch.get()
        if (clearing || store.isClearPending()) return CachedRead(null, false)
        val start = snapshotState()
        val value = start.first?.takeIf { it.belongsTo(binding) }
            ?: runCatching { store.loadCurrent(binding) }.getOrNull()
        afterCacheRead()
        if (currentBinding() != binding || businessEpoch.get() != startEpoch ||
            snapshotState().second != start.second || clearing || store.isClearPending()
        ) return CachedRead(null, false)
        val version = publishReadIfUnchanged(start.second, value) ?: return CachedRead(null, false)
        return CachedRead(value, true, version)
    }

    private fun cacheReadBlocking(binding: CardBalanceBinding): CachedRead {
        if (clearing || store.isClearPending()) return CachedRead(null, false)
        val startEpoch = businessEpoch.get()
        val start = snapshotState()
        val value = start.first?.takeIf { it.belongsTo(binding) }
            ?: runCatching { store.loadCurrent(binding) }.getOrNull()
        afterBlockingCacheRead()
        if (currentBinding() != binding || businessEpoch.get() != startEpoch ||
            snapshotState().second != start.second || clearing || store.isClearPending()
        ) return CachedRead(null, false)
        val version = publishReadIfUnchanged(start.second, value) ?: return CachedRead(null, false)
        return CachedRead(value, true, version)
    }

    private fun publishReadIfUnchanged(expected: Long, value: DirectCardSnapshot?): Long? =
        synchronized(snapshotLock) {
            if (snapshotRevision != expected) return@synchronized null
            if (value != null && mutableSnapshot.value != value) {
                mutableSnapshot.value = value
                snapshotRevision++
            }
            snapshotRevision
        }

    private fun publishSnapshot(value: DirectCardSnapshot?) = synchronized(snapshotLock) {
        if (mutableSnapshot.value == value) return@synchronized
        mutableSnapshot.value = value
        snapshotRevision++
    }

    suspend fun prepareAccountBinding() = withContext(Dispatchers.IO) { recoverPendingClear() }

    suspend fun refreshCurrent(): DirectCardSnapshot {
        val context = currentCoroutineContext()
        val initial = currentBinding() ?: throw RefreshInvalidatedException()
        recoverPendingClear()
        if (clearing || store.isClearPending()) throw CampusCardException(
            "校园卡缓存清理尚未完成，请重试",
            DirectCardBalanceFailureKind.CLEANUP,
        )
        return refreshLock.withLock {
            context.ensureActive()
            val binding = currentBinding() ?: throw RefreshInvalidatedException()
            if (binding != initial) throw RefreshInvalidatedException()
            val epoch = businessEpoch.get()
            val remote = try {
                backend.balance()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CardBackendException) {
                throw failure.toCampusCardException()
            } catch (known: CampusCardException) {
                throw known
            } catch (_: Exception) {
                throw CampusCardException("校方服务暂时无法读取校园卡余额", DirectCardBalanceFailureKind.NETWORK)
            }
            context.ensureActive()
            if (remote.fetchedAtEpochMillis <= 0) {
                throw CampusCardException("校方服务未返回有效余额时间", DirectCardBalanceFailureKind.SCHEMA)
            }
            val value = DirectCardSnapshot(
                accountReference = binding.accountReference,
                balanceCents = remote.balanceCents,
                transitionCents = remote.transitionCents,
                fetchedAt = remote.fetchedAtEpochMillis.takeIf { it > 0 } ?: clock(),
            )
            if (currentBinding() != binding || businessEpoch.get() != epoch) throw RefreshInvalidatedException()
            fileLock.withLock {
                context.ensureActive()
                if (clearing || store.isClearPending()) throw CampusCardException(
                    "校园卡缓存清理尚未完成，请重试",
                    DirectCardBalanceFailureKind.CLEANUP,
                )
                if (currentBinding() != binding || businessEpoch.get() != epoch) throw RefreshInvalidatedException()
                try {
                    beforeStoreSave()
                    store.saveCurrent(value)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw CampusCardException("余额已读取，但本机保存失败", DirectCardBalanceFailureKind.STORAGE)
                }
                if (currentBinding() != binding || businessEpoch.get() != epoch) throw RefreshInvalidatedException()
                publishSnapshot(value)
            }
            value
        }
    }

    suspend fun clearCache(onStarted: () -> Unit = {}) = withContext(Dispatchers.IO) {
        clearInternal(onStarted)
    }

    suspend fun clearForAccountChange(onStarted: () -> Unit = {}) = withContext(Dispatchers.IO) {
        clearInternal(onStarted)
    }

    private suspend fun clearInternal(onStarted: () -> Unit) {
        fileLock.withLock {
            store.beginClear()
            clearing = true
            businessEpoch.incrementAndGet()
            publishSnapshot(null)
            onStarted()
            store.clearBalanceData()
            store.finishClear()
            clearing = false
        }
    }

    private suspend fun recoverPendingClear() = withContext(Dispatchers.IO) {
        if (!clearing && !store.isClearPending()) return@withContext
        fileLock.withLock {
            if (!store.isClearPending()) {
                clearing = false
                return@withLock
            }
            try {
                store.clearBalanceData()
                store.finishClear()
                clearing = false
            } catch (_: Exception) {
                clearing = true
            }
        }
    }

    companion object {
        /** AppGraph fallback for a disabled feature plugin still removes legacy student-ID files. */
        fun clearStoredData(directory: File): Boolean = runCatching {
            DirectCardBalanceStore(directory).clearAll()
        }.isSuccess
    }
}

private fun CardBackendException.toCampusCardException(): CampusCardException {
    val message = when (code) {
        CardBackendFailureCode.BACKEND_NOT_READY -> "校方服务未就绪"
        CardBackendFailureCode.TOKEN_INVALID,
        CardBackendFailureCode.UNAUTHENTICATED,
        CardBackendFailureCode.DISABLED -> "校方托管会话已失效，请重新绑定账号"
        CardBackendFailureCode.SIGN_IN_FAILED -> "校方未接受本次登录，请核对账号后重试"
        CardBackendFailureCode.INTERACTIVE_CHALLENGE -> "学校要求额外验证，请按校方提示完成验证"
        CardBackendFailureCode.CREDENTIAL_UPDATE_REQUIRED -> "请先在托管账号设置中更新学校密码"
        CardBackendFailureCode.VERIFICATION_BUSY -> "学校验证正在处理中，请稍后重试"
        CardBackendFailureCode.MODULE_DISABLED -> "校方暂未开放校园卡服务"
        CardBackendFailureCode.CIRCUIT_OPEN -> "校园卡服务暂时熔断，请稍后重试"
        CardBackendFailureCode.UPSTREAM_UNAVAILABLE,
        CardBackendFailureCode.DEPENDENCY_UNAVAILABLE -> "校方校园卡服务暂不可用"
        CardBackendFailureCode.UPSTREAM_TIMEOUT -> "校方校园卡服务响应超时"
        CardBackendFailureCode.VALIDATION_FAILED -> "校园卡查询参数未通过校验"
        CardBackendFailureCode.IDENTITY_UNSUPPORTED -> "当前校方账号暂不支持校园卡查询"
        CardBackendFailureCode.UPSTREAM_PROTOCOL_ERROR -> "校方校园卡服务返回格式异常"
        CardBackendFailureCode.RATE_LIMITED -> {
            val seconds = retryAfterMillis?.takeIf { it > 0L }
                ?.coerceAtMost(MAX_RETRY_AFTER_MILLIS)
                ?.let { (it + 999L) / 1_000L }
            if (seconds == null) "校园卡查询过于频繁，请稍后重试"
            else "校园卡查询过于频繁，请约 ${seconds} 秒后重试"
        }
        CardBackendFailureCode.NETWORK_UNAVAILABLE -> "暂时无法连接校方服务"
        CardBackendFailureCode.TLS_ERROR -> "校方服务安全连接失败"
        CardBackendFailureCode.UNKNOWN -> "校方服务暂时无法读取余额"
    }
    val kind = when (code) {
        CardBackendFailureCode.UPSTREAM_PROTOCOL_ERROR,
        CardBackendFailureCode.IDENTITY_UNSUPPORTED,
        CardBackendFailureCode.VALIDATION_FAILED -> DirectCardBalanceFailureKind.SCHEMA
        CardBackendFailureCode.NETWORK_UNAVAILABLE,
        CardBackendFailureCode.TLS_ERROR,
        CardBackendFailureCode.UPSTREAM_TIMEOUT -> DirectCardBalanceFailureKind.NETWORK
        else -> DirectCardBalanceFailureKind.HTTP
    }
    return CampusCardException(message, kind)
}

private const val MAX_RETRY_AFTER_MILLIS = 86_400_000L
