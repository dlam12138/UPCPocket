package com.upcpocket.app.data.ecard

import android.util.AtomicFile
import java.io.File
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

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

class DirectCardBalanceRepository(
    private val store: DirectCardBalanceStore,
    private val request: (String) -> String = { requestDirectCardBalance(it) },
) {
    suspend fun cached(): DirectCardSnapshot? = withContext(Dispatchers.IO) { store.load() }
    suspend fun clear() = withContext(Dispatchers.IO) { store.clear() }
    suspend fun refresh(studentId: String): DirectCardSnapshot = withContext(Dispatchers.IO) {
        val form = DirectCardBalanceProtocol.form(studentId)
        val result = DirectCardBalanceProtocol.parse(request(form), studentId, System.currentTimeMillis())
        // Only a complete, verified response may replace the last successful snapshot.
        try { store.save(result) } catch (_: Exception) {
            throw CampusCardException("已读取余额，但本机保存失败，请重试")
        }
        result
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
