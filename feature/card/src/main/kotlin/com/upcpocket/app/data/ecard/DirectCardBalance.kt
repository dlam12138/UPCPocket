package com.upcpocket.app.data.ecard

import android.util.AtomicFile
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.StudentIdFormat
import com.upcpocket.app.platform.RefreshInvalidatedException
import java.io.File
import java.io.FileNotFoundException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 校园卡余额查询的业务异常。
 *
 * 轻量版第二步删除了平台登录与流水，这个异常仍被余额查询的传输/解析使用，
 * 因此保留在余额实现文件里（原先定义在已删除的平台客户端中）。
 */
class CampusCardException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** Contract supplied in 用户资料“石大校园卡余额查询实现方法”; no authenticated-session claim. */
object DirectCardBalanceProtocol {
    const val ENDPOINT = "http://jhzf.upc.edu.cn:20081/wechat/callinterface/getCardInfo.html"

    fun validStudentId(value: String) = StudentIdFormat.isValid(value)

    fun form(studentId: String): String {
        require(validStudentId(studentId)) { "请输入 8–16 位数字学号（当前输入限制）" }
        val id = URLEncoder.encode(studentId, "UTF-8")
        return "sno=$id&xxbh=synjones&idtype=sno&id=$id"
    }

    fun parse(body: String, expectedId: String, fetchedAt: Long): DirectCardSnapshot {
        try {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: error("root")
            require((root["retcode"] as? JsonPrimitive)?.content == "0")
            val cards = root["card"] as? JsonArray ?: error("card")
            require(cards.size == 1)
            val card = cards.single() as? JsonObject ?: error("record")
            require((card["sno"] as? JsonPrimitive)?.content == expectedId)
            fun cents(key: String): Long {
                val raw = (card[key] as? JsonPrimitive)?.content ?: error("amount")
                require(raw.matches(Regex("-?[0-9]+")))
                return raw.toLong()
            }
            require(validStudentId(expectedId))
            return DirectCardSnapshot(
                accountId = "",
                studentId = expectedId,
                credentialGeneration = 0,
                balanceCents = cents("db_balance"),
                transitionCents = cents("unsettle_amount"),
                fetchedAt = fetchedAt,
            )
        } catch (_: Exception) {
            // Never expose server bodies, names or student identifiers in errors.
            throw CampusCardException("学校未返回有效的本人校园卡余额（状态、学号或金额校验失败）")
        }
    }
}

/**
 * 成功读取的余额。
 *
 * [accountId] 与 [credentialGeneration] 是持久归属快照，不是“当前凭据仍有效”的
 * 证明；展示前必须再与当前绑定和业务代次核对。金额仍按“分”存储。
 */
data class DirectCardSnapshot(
    val accountId: String,
    val studentId: String,
    val credentialGeneration: Long,
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAt: Long,
) {
    val balanceText: String get() = BigDecimal.valueOf(balanceCents, 2).toPlainString()
    val transitionText: String get() = BigDecimal.valueOf(transitionCents, 2).toPlainString()

    fun belongsTo(binding: CardBalanceBinding): Boolean =
        accountId == binding.accountId.value &&
            studentId == binding.studentId &&
            credentialGeneration == binding.credentialGeneration.value

    fun sameOwnerAs(binding: CardBalanceBinding): Boolean =
        accountId == binding.accountId.value && studentId == binding.studentId
}

/**
 * 余额缓存 v2 + 旧 v1 迁移。
 *
 * 文件都位于应用私有、不参与备份目录：
 *  - `card-balance-v2.json`：带 accountId/学号/凭据代次的新格式；
 *  - `card-balance-v1.json`：旧版无归属快照，只允许在用户已绑定同一学号后迁移；
 *  - `card-balance-migration-v2.json`：显式迁移记录，保证幂等与中断恢复。
 *
 * 未绑定账号时不会读取 v1 展示。损坏、异号或未知归属的 v1 不迁移。
 */
class DirectCardBalanceStore(directory: File) {
    private val current = AtomicFile(File(directory, CURRENT_FILE))
    private val legacy = AtomicFile(File(directory, LEGACY_FILE))
    private val marker = AtomicFile(File(directory, MARKER_FILE))
    private val clearMarker = AtomicFile(File(directory, CLEAR_MARKER_FILE))

    fun loadCurrent(binding: CardBalanceBinding): DirectCardSnapshot? {
        // 清理已开始但未完成时，旧文件即使仍在磁盘上也不得被展示。
        if (isClearPending()) return null
        val parsed = readJson(current, MAX_CACHE_BYTES)
            ?.let { runCatching { json.decodeFromString<StoredCardSnapshot>(it) }.getOrNull() }
            ?: return null
        if (parsed.format != CACHE_FORMAT || parsed.version != CACHE_VERSION) return null
        if (parsed.accountId != binding.accountId.value) return null
        if (parsed.studentId != binding.studentId) return null
        if (!StudentIdFormat.isValid(parsed.studentId) || parsed.fetchedAt <= 0L) return null
        if (parsed.credentialGeneration < 0L) return null
        return DirectCardSnapshot(
            accountId = parsed.accountId,
            studentId = parsed.studentId,
            credentialGeneration = parsed.credentialGeneration,
            balanceCents = parsed.balanceCents,
            transitionCents = parsed.transitionCents,
            fetchedAt = parsed.fetchedAt,
        )
    }

    fun saveCurrent(value: DirectCardSnapshot) {
        val stored = StoredCardSnapshot(
            accountId = value.accountId,
            studentId = value.studentId,
            credentialGeneration = value.credentialGeneration,
            balanceCents = value.balanceCents,
            transitionCents = value.transitionCents,
            fetchedAt = value.fetchedAt,
        )
        writeJson(current, json.encodeToString(stored).toByteArray(Charsets.UTF_8))
    }

    fun hasCurrent(): Boolean = exists(current)

    fun hasLegacy(): Boolean = exists(legacy)

    fun hasMarker(): Boolean = exists(marker)

    internal fun loadLegacy(): LegacyCardSnapshot? {
        if (isClearPending()) return null
        val parsed = readJson(legacy, MAX_CACHE_BYTES)
            ?.let { runCatching { json.decodeFromString<LegacyCardSnapshot>(it) }.getOrNull() }
            ?: return null
        if (!StudentIdFormat.isValid(parsed.studentId) || parsed.fetchedAt <= 0L) return null
        return parsed
    }

    internal fun loadMarker(): CardMigrationMarker? {
        val parsed = readJson(marker, MAX_MARKER_BYTES)
            ?.let { runCatching { json.decodeFromString<CardMigrationMarker>(it) }.getOrNull() }
            ?: return null
        if (parsed.format != MARKER_FORMAT || parsed.version != MARKER_VERSION) return null
        if (!StudentIdFormat.isValid(parsed.studentId) || parsed.accountId.isBlank()) return null
        return parsed
    }

    internal fun writeMarker(binding: CardBalanceBinding, outcome: CardMigrationOutcome) {
        val value = CardMigrationMarker(
            accountId = binding.accountId.value,
            studentId = binding.studentId,
            outcome = outcome.name,
        )
        writeJson(marker, json.encodeToString(value).toByteArray(Charsets.UTF_8))
    }

    fun clearCurrent() = deleteConfirmed(current)

    fun clearLegacy() = deleteConfirmed(legacy)

    fun clearMarker() = deleteConfirmed(marker)

    /**
     * 用户主动清除缓存的持久门闩：先写它，再删数据；删除未全部成功时保留，
     * 重启后继续隐藏旧值并重试。成功后才删除门闩。
     */
    fun beginClear() {
        writeJson(
            clearMarker,
            json.encodeToString(StoredClearMarker()).toByteArray(Charsets.UTF_8),
        )
    }

    fun isClearPending(): Boolean = exists(clearMarker)

    /** 只删余额数据，保留“清理进行中”门闩，便于失败后重启继续隐藏/重试。 */
    fun clearBalanceData() {
        clearCurrent()
        clearLegacy()
        clearMarker()
    }

    fun finishClear() = deleteConfirmed(clearMarker)

    /** 换号/删号使用：余额、迁移记录和清理门闩全部删除。 */
    fun clearAll() {
        clearBalanceData()
        finishClear()
    }

    private fun readJson(file: AtomicFile, limit: Int): String? {
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

    private fun writeJson(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun exists(file: AtomicFile): Boolean {
        val base = file.baseFile
        return base.exists() || File(base.path + ".bak").exists()
    }

    private fun deleteConfirmed(file: AtomicFile) {
        file.delete()
        // AtomicFile.delete 删除 base/.bak；.new 可能是上次中断的残留，一并尝试清理。
        val base = file.baseFile
        listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach {
            if (it.exists()) {
                it.delete()
                if (it.exists()) throw java.io.IOException("余额缓存文件未能删除")
            }
        }
    }

    private companion object {
        const val CURRENT_FILE = "card-balance-v2.json"
        const val LEGACY_FILE = "card-balance-v1.json"
        const val MARKER_FILE = "card-balance-migration-v2.json"
        const val CLEAR_MARKER_FILE = "card-balance-clear-v2.json"
        const val CACHE_FORMAT = "upcpocket.card-balance"
        const val CACHE_VERSION = 2
        const val MARKER_FORMAT = "upcpocket.card-balance.migration"
        const val MARKER_VERSION = 2
        const val MAX_CACHE_BYTES = 8192
        const val MAX_MARKER_BYTES = 4096
    }
}

/**
 * 余额领域仓库。
 *
 * 查询目标只能来自 [account] 的受控学号；页面、Worker 和组件都不传任意学号。
 * 一次查询捕获 `(accountId, studentId, credentialGeneration, businessEpoch)`，
 * 请求返回、落盘和通知前都要再次核对。取消协程不能替代该核对。
 */
class DirectCardBalanceRepository internal constructor(
    private val store: DirectCardBalanceStore,
    private val account: SchoolAccountStudentIdentityReader,
    private val clock: () -> Long = System::currentTimeMillis,
    // 测试注入点：生产默认 no-op；用于确定性地在“缓存已读、尚未发布”时切换账号/清理。
    private val afterCacheRead: suspend () -> Unit = {},
    private val afterBlockingCacheRead: () -> Unit = {},
    // 测试可跳过 prepareMigration，单独验证“迟到磁盘读取不得回填内存”。
    private val prepareForCacheRead: (suspend (CardBalanceBinding) -> Unit)? = null,
    private val request: suspend (String) -> String = { requestDirectCardBalance(it) },
) {
    private val mutableSnapshot = MutableStateFlow<DirectCardSnapshot?>(null)

    /** 页面只看到属于当前绑定的快照；换号/清理/删号立即变为 null。 */
    val snapshot: Flow<DirectCardSnapshot?> = combine(
        mutableSnapshot,
        account.state,
        account.studentIdentity,
    ) { raw, state, identity ->
        val binding = currentCardBinding(state, identity) ?: return@combine null
        raw?.takeIf { it.sameOwnerAs(binding) }
    }

    private val fileLock = Mutex()
    private val refreshLock = Mutex()
    private val businessEpoch = AtomicLong(0)

    /**
     * 内存快照与发布版本必须一起读写，否则“新值 + 旧版本”会被组件发布误判为最新。
     */
    private val snapshotLock = Any()
    private var snapshotRevision = 0L

    /**
     * 内存中的清理门闩：开始清除后立即隐藏旧值；若落盘删除失败则保持为 true，
     * 直到恢复清理成功。重启后由持久门闩 [DirectCardBalanceStore.isClearPending] 接替。
     */
    @Volatile
    private var clearing = false

    fun currentBinding(): CardBalanceBinding? = currentCardBinding(account)

    fun businessEpochNow(): Long = businessEpoch.get()

    fun snapshotRevisionNow(): Long = synchronized(snapshotLock) { snapshotRevision }

    private fun snapshotState(): Pair<DirectCardSnapshot?, Long> = synchronized(snapshotLock) {
        mutableSnapshot.value to snapshotRevision
    }

    /**
     * 页面读取。返回值只在该绑定、业务代次和快照版本在读取结束后仍一致时有效；
     * 账号切换/清理把读到的旧值标记为 invalid，绝不回填内存。
     */
    suspend fun cached(): DirectCardSnapshot? = withContext(Dispatchers.IO) {
        val binding = currentBinding() ?: return@withContext null
        cacheRead(binding).takeIf { it.valid }?.snapshot
    }

    /**
     * RemoteViews 专用读取。调用方必须把 [CachedRead.valid] 与读后 revision
     * 带入最终发布校验；invalid 时不能渲染旧值。
     */
    internal suspend fun cachedForWidget(binding: CardBalanceBinding): CachedRead =
        withContext(Dispatchers.IO) { cacheRead(binding) }

    /**
     * 同步读取，仅供 RemoteViews 在 onUpdate / 点击时渲染缓存。
     * 不迁移、不联网、不解密凭据；文件上限 8 KB。读后同样复核绑定/业务代次/
     * 快照版本，迟到读取不回填内存。
     */
    fun cachedBlocking(): DirectCardSnapshot? {
        val binding = currentBinding() ?: return null
        return cacheReadBlocking(binding).takeIf { it.valid }?.snapshot
    }

    internal fun cachedBlockingForWidget(binding: CardBalanceBinding): CachedRead =
        cacheReadBlocking(binding)

    private suspend fun cacheRead(binding: CardBalanceBinding): CachedRead {
        val startEpoch = businessEpoch.get()
        // 迁移/清理只读/写本机缓存，不联网；失败不自动查询。
        runCatching {
            if (prepareForCacheRead != null) {
                prepareForCacheRead.invoke(binding)
            } else {
                prepareMigration(binding)
            }
        }
        if (currentBinding() != binding || businessEpoch.get() != startEpoch) {
            return CachedRead(null, valid = false)
        }
        if (clearing || store.isClearPending()) return CachedRead(null, valid = false)

        // prepareMigration 可能已发布迁移后的快照；版本从迁移之后开始计算。
        val start = snapshotState()
        val startRevision = start.second
        val value = start.first?.takeIf { it.sameOwnerAs(binding) }
            ?: runCatching { store.loadCurrent(binding) }.getOrNull()
        afterCacheRead()
        if (currentBinding() != binding ||
            businessEpoch.get() != startEpoch ||
            snapshotState().second != startRevision ||
            clearing ||
            store.isClearPending()
        ) {
            return CachedRead(null, valid = false)
        }
        // 检查与内存发布必须在同一把锁内：否则另一个发布者可在检查和赋值之间
        // 写入新值/新版本，旧读取随后又把旧值盖回去。
        val finalRevision = publishReadIfUnchanged(startRevision, value)
            ?: return CachedRead(null, valid = false)
        return CachedRead(value, valid = true, snapshotRevision = finalRevision)
    }

    private fun cacheReadBlocking(binding: CardBalanceBinding): CachedRead {
        val startEpoch = businessEpoch.get()
        if (clearing || store.isClearPending()) return CachedRead(null, valid = false)
        val start = snapshotState()
        val startRevision = start.second
        val value = start.first?.takeIf { it.sameOwnerAs(binding) }
            ?: runCatching { store.loadCurrent(binding) }.getOrNull()
        afterBlockingCacheRead()
        if (currentBinding() != binding ||
            businessEpoch.get() != startEpoch ||
            snapshotState().second != startRevision ||
            clearing ||
            store.isClearPending()
        ) {
            return CachedRead(null, valid = false)
        }
        val finalRevision = publishReadIfUnchanged(startRevision, value)
            ?: return CachedRead(null, valid = false)
        return CachedRead(value, valid = true, snapshotRevision = finalRevision)
    }

    /**
     * 只有快照版本仍是读取时的版本才允许回填；返回最终版本，版本已变则 null。
     * 读磁盘/渲染在锁外，锁内只做一次版本比较和内存赋值。
     */
    private fun publishReadIfUnchanged(
        expectedRevision: Long,
        value: DirectCardSnapshot?,
    ): Long? = synchronized(snapshotLock) {
        if (snapshotRevision != expectedRevision) return@synchronized null
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

    /**
     * 用户打开应用/账号变化后调用：只做本机缓存归属与旧 v1 迁移，不发网络。
     */
    suspend fun prepareAccountBinding() = withContext(Dispatchers.IO) {
        val binding = currentBinding() ?: return@withContext
        runCatching { prepareMigration(binding) }
        Unit
    }

    /**
     * 按当前绑定查询。首次无历史快照也能工作；没有绑定、绑定已变化或缓存清理
     * 尚未完成时抛出异常，调用方不得把旧数据当成本次结果，也不得联网。
     */
    suspend fun refreshCurrent(): DirectCardSnapshot = withContext(Dispatchers.IO) {
        val initial = currentBinding() ?: throw RefreshInvalidatedException()
        runCatching { prepareMigration(initial) }
        if (clearing || store.isClearPending()) {
            throw CampusCardException("余额缓存清理尚未完成，请先重试清除")
        }
        refreshLock.withLock {
            val binding = currentBinding() ?: throw RefreshInvalidatedException()
            if (binding != initial) throw RefreshInvalidatedException()
            val startEpoch = businessEpoch.get()
            val parsed = DirectCardBalanceProtocol.parse(
                request(DirectCardBalanceProtocol.form(binding.studentId)),
                binding.studentId,
                clock(),
            )
            val result = parsed.copy(
                accountId = binding.accountId.value,
                credentialGeneration = binding.credentialGeneration.value,
            )
            if (currentBinding() != binding || businessEpoch.get() != startEpoch) {
                throw RefreshInvalidatedException()
            }
            fileLock.withLock {
                if (currentBinding() != binding || businessEpoch.get() != startEpoch) {
                    throw RefreshInvalidatedException()
                }
                if (clearing || store.isClearPending()) throw RefreshInvalidatedException()
                try {
                    store.saveCurrent(result)
                } catch (_: Exception) {
                    throw CampusCardException("已读取余额，但本机保存失败，请重试")
                }
                // 保存期间账号/清理仍可能变化；落盘后再次复核，旧结果不作为新值发布。
                if (currentBinding() != binding || businessEpoch.get() != startEpoch) {
                    throw RefreshInvalidatedException()
                }
                publishSnapshot(result)
            }
            result
        }
    }

    /**
     * 仅清除余额缓存与旧迁移数据；账号/密码和同号绑定保持不变。
     * [onStarted] 在门闩已落盘、旧值已从内存撤下后调用，供组件立即重绘空状态。
     */
    suspend fun clearCache(onStarted: () -> Unit = {}) = withContext(Dispatchers.IO) {
        clearInternal(forAccountChange = false, onStarted = onStarted)
    }

    /** 换号/删号/凭据不可读时由账号失效器调用；不反向读取账号锁。 */
    suspend fun clearForAccountChange(onStarted: () -> Unit = {}) =
        withContext(Dispatchers.IO) {
            clearInternal(forAccountChange = true, onStarted = onStarted)
        }

    private suspend fun clearInternal(
        forAccountChange: Boolean,
        onStarted: () -> Unit,
    ) {
        fileLock.withLock {
            if (forAccountChange) {
                // 账号状态已在失效器外层置为清理中；这里仍先撤下内存旧值，避免
                // 任何并发读取在文件删除前看到旧余额。
                businessEpoch.incrementAndGet()
                publishSnapshot(null)
                clearing = true
                onStarted()
                store.clearAll()
                clearing = false
            } else {
                store.beginClear()
                businessEpoch.incrementAndGet()
                publishSnapshot(null)
                clearing = true
                onStarted()
                // 删除失败时保留门闩和 clearing=true，重启后继续隐藏并重试。
                store.clearBalanceData()
                store.finishClear()
                clearing = false
            }
        }
    }

    /**
     * 在 fileLock 内完成未竟的缓存清理。成功后解除内存门闩并返回 true；
     * 失败时保持隐藏，返回 false，由调用方拒绝联网。
     */
    private fun recoverPendingClearLocked(): Boolean {
        if (!clearing && !store.isClearPending()) return true
        return try {
            if (store.isClearPending()) {
                store.clearBalanceData()
                store.finishClear()
            }
            clearing = false
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * v1 → v2 迁移。只有当前绑定与 v1 学号完全一致时才迁移；迁移记录必须与
     * 当前 accountId+学号一致，旧账号/旧 marker 不能被当成当前数据。
     *
     * - 其他账号的 v2 或损坏 v2 是孤儿数据，先删除再考虑 v1，避免 FIRST_SAVE 漏隔离；
     * - 有门闩时先恢复清理，清理未完成时不得迁移或展示；
     * - 同账号 marker 存在但 v2 丢失（中断/手工删除）时，仍可从同号 v1 重建。
     */
    private suspend fun prepareMigration(binding: CardBalanceBinding) {
        fileLock.withLock {
            if (currentBinding() != binding) return
            if (!recoverPendingClearLocked()) return
            val startEpoch = businessEpoch.get()

            val existingMarker = store.loadMarker()
            val markerMatches = existingMarker?.matches(binding) == true
            if (existingMarker != null && !markerMatches) {
                // marker 属于其他账号：不继承旧 v1，也不让它阻塞当前账号。
                store.clearLegacy()
                store.clearMarker()
            }

            val current = store.loadCurrent(binding)
            if (current != null) {
                if (currentBinding() == binding && businessEpoch.get() == startEpoch) {
                    publishSnapshot(current)
                }
                if (store.hasLegacy()) store.clearLegacy()
                if (!markerMatches) {
                    store.writeMarker(binding, CardMigrationOutcome.ALREADY_OWNED)
                }
                return
            }

            // v2 存在但不属于当前绑定或已损坏：单账号模型下它是孤儿数据，必须隔离。
            if (store.hasCurrent()) store.clearCurrent()

            if (!store.hasLegacy()) {
                store.writeMarker(binding, CardMigrationOutcome.NO_LEGACY)
                return
            }
            val legacy = store.loadLegacy()
            if (legacy == null) {
                store.clearLegacy()
                store.writeMarker(binding, CardMigrationOutcome.CORRUPT)
                return
            }
            if (legacy.studentId != binding.studentId) {
                store.clearLegacy()
                store.writeMarker(binding, CardMigrationOutcome.MISMATCH)
                return
            }
            val migrated = DirectCardSnapshot(
                accountId = binding.accountId.value,
                studentId = legacy.studentId,
                credentialGeneration = binding.credentialGeneration.value,
                balanceCents = legacy.balanceCents,
                transitionCents = legacy.transitionCents,
                fetchedAt = legacy.fetchedAt,
            )
            store.saveCurrent(migrated)
            if (currentBinding() == binding && businessEpoch.get() == startEpoch) {
                publishSnapshot(migrated)
            }
            store.clearLegacy()
            store.writeMarker(binding, CardMigrationOutcome.MIGRATED)
        }
    }
}

/** 缓存读取结果；invalid 表示读期间绑定/业务代次/快照版本已变化，调用方必须丢弃。 */
internal data class CachedRead(
    val snapshot: DirectCardSnapshot?,
    val valid: Boolean,
    /** 本次读取完成后的内存快照版本；valid 时供组件发布复核。 */
    val snapshotRevision: Long = 0,
)

@Serializable
private data class StoredCardSnapshot(
    val format: String = "upcpocket.card-balance",
    val version: Int = 2,
    val accountId: String,
    val studentId: String,
    val credentialGeneration: Long,
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAt: Long,
)

@Serializable
internal data class LegacyCardSnapshot(
    val studentId: String,
    val balanceCents: Long,
    val transitionCents: Long,
    val fetchedAt: Long,
)

@Serializable
internal data class CardMigrationMarker(
    val format: String = "upcpocket.card-balance.migration",
    val version: Int = 2,
    val accountId: String,
    val studentId: String,
    val outcome: String,
) {
    fun matches(binding: CardBalanceBinding): Boolean =
        accountId == binding.accountId.value && studentId == binding.studentId
}

/** 用户清除缓存的持久门闩；存在即表示上次清理未完整结束。 */
@Serializable
private data class StoredClearMarker(
    val format: String = "upcpocket.card-balance.clear",
    val version: Int = 2,
)

internal enum class CardMigrationOutcome {
    MIGRATED,
    ALREADY_OWNED,
    NO_LEGACY,
    MISMATCH,
    CORRUPT,
}

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

internal fun requestDirectCardBalance(form: String, endpoint: String = DirectCardBalanceProtocol.ENDPOINT): String {
    val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        // This server produces text/plain JSON. application/json causes HTTP 406
        // before the business handler runs (verified on the owner's browser).
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        connection.setRequestProperty("User-Agent", "UPC-Pocket/0.1.8")
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        connection.setRequestProperty("Cookie", "")
        val bytes = form.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        if (connection.responseCode !in 200..299) throw CampusCardException("校园卡服务暂时不可用，请稍后重试")
        return connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 256 * 1024) throw CampusCardException("校园卡响应异常过大")
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    } catch (e: CampusCardException) {
        throw e
    } catch (_: java.io.IOException) {
        throw CampusCardException("无法连接学校校园卡余额服务，请检查网络后重试")
    } finally {
        connection.disconnect()
    }
}
