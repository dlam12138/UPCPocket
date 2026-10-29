package com.upcpocket.app.data.ecard

import android.util.AtomicFile
import com.upcpocket.app.data.RefreshInvalidatedException
import java.io.File
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

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
    fun validStudentId(value: String) = value.matches(Regex("[0-9]{8,16}"))
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
            return DirectCardSnapshot(expectedId, cents("db_balance"), cents("unsettle_amount"), fetchedAt)
        } catch (_: Exception) {
            // Never expose server bodies, names or student identifiers in errors.
            throw CampusCardException("学校未返回有效的本人校园卡余额（状态、学号或金额校验失败）")
        }
    }
}

@Serializable
data class DirectCardSnapshot(val studentId: String, val balanceCents: Long, val transitionCents: Long, val fetchedAt: Long) {
    val balanceText: String get() = BigDecimal.valueOf(balanceCents, 2).toPlainString()
    val transitionText: String get() = BigDecimal.valueOf(transitionCents, 2).toPlainString()
}

/** Minimal private, non-backup cache. It is not encrypted and does not contain the raw response. */
class DirectCardBalanceStore(file: File) {
    private val atomic = AtomicFile(file)
    fun load(): DirectCardSnapshot? = runCatching {
        val text = atomic.openRead().use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= 4096)
            bytes.toString(Charsets.UTF_8)
        }
        Json.decodeFromString<DirectCardSnapshot>(text).also {
            require(DirectCardBalanceProtocol.validStudentId(it.studentId) && it.fetchedAt > 0)
        }
    }.getOrNull()
    fun save(value: DirectCardSnapshot) {
        val stream = atomic.startWrite()
        try {
            stream.write(Json.encodeToString(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
    fun clear() = atomic.delete()
}

/**
 * 校园卡余额的领域仓库。
 *
 * 生产环境只保留一个实例（[com.upcpocket.app.core.AppGraph]），页面和桌面组件共用，
 * 避免两个 AtomicFile 同时写 `card-balance-v1.json`。成功结果通过 [snapshot] 通知页面，
 * 组件侧由调用方读取同一份快照后刷新 RemoteViews。
 *
 * 目标语义：快照里的学号就是“最后一次成功查询的学号”。草稿输入变化不会改它；
 * 只有成功响应才会替换。清除会递增 generation，使还在路上的旧请求无法复活已删除的数据。
 */
class DirectCardBalanceRepository(
    private val store: DirectCardBalanceStore,
    private val request: suspend (String) -> String = { requestDirectCardBalance(it) },
) {
    private val mutableSnapshot = MutableStateFlow<DirectCardSnapshot?>(null)
    val snapshot: StateFlow<DirectCardSnapshot?> = mutableSnapshot.asStateFlow()

    private val fileLock = Mutex()
    private val refreshLock = Mutex()
    private val generation = AtomicLong(0)
    private val initialized = AtomicBoolean(false)

    suspend fun cached(): DirectCardSnapshot? = withContext(Dispatchers.IO) {
        mutableSnapshot.value?.let { return@withContext it }
        if (initialized.get()) return@withContext null
        val startGeneration = generation.get()
        val loaded = fileLock.withLock { store.load() }
        if (generation.get() != startGeneration) return@withContext null
        if (initialized.compareAndSet(false, true)) mutableSnapshot.value = loaded
        mutableSnapshot.value
    }

    /**
     * 同步读取，仅供 RemoteViews 在 onUpdate / 点击刷新时渲染缓存。
     * 只读 4 KB 内的本地文件，不做网络请求，也不在主线程等待协程。
     */
    fun cachedBlocking(): DirectCardSnapshot? {
        mutableSnapshot.value?.let { return it }
        if (initialized.get()) return null
        val startGeneration = generation.get()
        val loaded = runCatching { store.load() }.getOrNull()
        if (generation.get() != startGeneration) return null
        if (initialized.compareAndSet(false, true)) mutableSnapshot.value = loaded
        return mutableSnapshot.value
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        // 先让在途刷新失效，再删文件；即使旧请求随后返回也不会写回。
        generation.incrementAndGet()
        fileLock.withLock { store.clear() }
        mutableSnapshot.value = null
    }

    /** 页面查询：允许用户改成另一个学号，成功后替换目标。 */
    suspend fun refresh(studentId: String): DirectCardSnapshot =
        refreshInternal(requestedStudentId = studentId, requireSameTarget = false)
            ?: throw RefreshInvalidatedException()

    /**
     * 组件刷新：只刷新当前成功快照的学号，绝不因为草稿输入变化而切换目标。
     * 没有快照时返回 null，调用方只能引导用户打开应用，不能发空请求。
     */
    suspend fun refreshCurrent(): DirectCardSnapshot? =
        refreshInternal(requestedStudentId = null, requireSameTarget = true)

    private suspend fun refreshInternal(
        requestedStudentId: String?,
        requireSameTarget: Boolean,
    ): DirectCardSnapshot? = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val current = currentSnapshotLocked()
            val studentId = requestedStudentId ?: current?.studentId ?: return@withLock null
            if (requireSameTarget && current?.studentId != studentId) return@withLock null

            val startGeneration = generation.get()
            val form = DirectCardBalanceProtocol.form(studentId)
            val result = DirectCardBalanceProtocol.parse(
                request(form),
                studentId,
                System.currentTimeMillis(),
            )

            val committed = fileLock.withLock {
                when {
                    generation.get() != startGeneration -> false
                    requireSameTarget && mutableSnapshot.value?.studentId != studentId -> false
                    else -> {
                        try {
                            store.save(result)
                        } catch (_: Exception) {
                            throw CampusCardException("已读取余额，但本机保存失败，请重试")
                        }
                        mutableSnapshot.value = result
                        true
                    }
                }
            }
            if (committed) result else null
        }
    }

    private suspend fun currentSnapshotLocked(): DirectCardSnapshot? {
        mutableSnapshot.value?.let { return it }
        if (initialized.get()) return null
        val startGeneration = generation.get()
        val loaded = fileLock.withLock { store.load() }
        if (generation.get() != startGeneration) return null
        if (initialized.compareAndSet(false, true)) mutableSnapshot.value = loaded
        return mutableSnapshot.value ?: loaded
    }
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
        if (connection.responseCode !in 200..299) throw CampusCardException("校园卡余额服务暂不可用（HTTP ${connection.responseCode}）")
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
