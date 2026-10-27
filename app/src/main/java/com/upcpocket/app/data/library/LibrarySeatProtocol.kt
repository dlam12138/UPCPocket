package com.upcpocket.app.data.library

import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 图书馆座位系统的请求签名与响应解析。
 *
 * 协议来自学校页面公开前端代码（2026-10-27 核验）：
 *  - `X-request-id`：随机 UUID v4；
 *  - `X-request-date`：毫秒时间戳；
 *  - `X-hmac-request-key`：HMAC-SHA256(`seat::<uuid>::<date>::<GET|POST>`, 公开配置中的密钥)；
 *  - `Authorization`：登录后 token；`loginType: PC`。
 *
 * 密钥是页面自带的公开配置，不是用户凭据。这里用 AES 解出，避免再复制一份
 * “看起来像密码”的常量；真正的用户 token 只在内存中保存，不落盘。
 */
internal object LibrarySeatProtocol {
    const val BASE_URL = "https://libspace.upc.edu.cn"
    const val LOGIN_URL =
        "$BASE_URL/remote/static/sso/login?redirectUrl=https%3A%2F%2Flibspace.upc.edu.cn%2Flibseat%2F%23%2Flogin"

    private const val NUM_CODE = "cDf+jadFUWncEn536MXItw=="
    private const val CONFIG_KEY = "server_date_time"
    private const val CONFIG_IV = "client_date_time"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun signedHeaders(
        token: String?,
        method: String,
        uuid: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        hmacKey: ByteArray = configKey(),
    ): Map<String, String> {
        require(method.uppercase() in setOf("GET", "POST")) { "不支持的请求方法" }
        val message = "seat::$uuid::$timestamp::${method.uppercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return buildMap {
            if (!token.isNullOrBlank()) put("Authorization", token)
            put("loginType", "PC")
            put("X-request-id", uuid)
            put("X-request-date", timestamp.toString())
            put("X-hmac-request-key", signature)
            put("Accept", "application/json")
        }
    }

    fun sign(method: String, uuid: String, timestamp: Long, hmacKey: ByteArray = configKey()): String {
        val message = "seat::$uuid::$timestamp::${method.uppercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** 页面配置里的公开 HMAC 密钥；用 AES-CBC 解出，便于单元测试和协议核对。 */
    fun configKey(): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(CONFIG_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(CONFIG_IV.toByteArray(Charsets.UTF_8)),
        )
        return cipher.doFinal(Base64.getDecoder().decode(NUM_CODE))
    }

    fun formEncode(fields: Map<String, String>): String = fields.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    fun queryEncode(fields: Map<String, String>): String = formEncode(fields)

    fun parseToken(body: String): String {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("登录响应缺少 data")
        return string(data, "token").takeIf { it.isNotBlank() } ?: fail("登录响应缺少 token")
    }

    fun parseUser(body: String): LibraryUser {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("用户信息缺少 data")
        return LibraryUser(
            id = string(data, "id"),
            username = string(data, "username"),
            name = string(data, "name").ifBlank { string(data, "username") },
        )
    }

    fun parseFilters(body: String): LibraryFilters {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("筛选条件缺少 data")
        val dates = (data["dates"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() } ?: fail("学校未返回可预约日期")
        val buildings = (data["buildings"] as? JsonArray)?.map { item ->
            when (item) {
                is JsonArray -> {
                    val id = (item.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val name = (item.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (id.isBlank() || name.isBlank()) fail("馆区字段不完整")
                    LibraryBuilding(id, name)
                }
                is JsonObject -> LibraryBuilding(string(item, "id"), string(item, "name"))
                else -> fail("馆区字段格式异常")
            }
        } ?: fail("学校未返回馆区")
        return LibraryFilters(dates, buildings)
    }

    fun parseFloors(body: String): List<LibraryFloor> {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonArray ?: fail("楼层响应缺少 data")
        return data.map { item ->
            val obj = item as? JsonObject ?: fail("楼层字段格式异常")
            LibraryFloor(int(obj, "floorNum"), string(obj, "floorName"))
        }
    }

    fun parseRooms(body: String, building: LibraryBuilding): List<LibraryRoom> {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonArray ?: fail("房间响应缺少 data")
        return data.map { item ->
            val obj = item as? JsonObject ?: fail("房间字段格式异常")
            LibraryRoom(
                roomId = string(obj, "roomId", "id"),
                name = string(obj, "room", "roomName", "name"),
                floor = int(obj, "floor"),
            )
        }.filter { it.roomId.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.roomId }
    }

    fun parseLayout(body: String): LibrarySeatLayout {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("座位布局缺少 data")
        val rows = int(data, "rows")
        val cols = int(data, "cols")
        if (rows <= 0 || cols <= 0 || rows > 200 || cols > 200) fail("座位布局尺寸异常")
        val layout = data["layout"] as? JsonObject ?: fail("座位布局缺少 layout")
        val seats = layout.mapNotNull { (key, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            // 页面把 seat 的数值 key 编码成 row*1000+col；同时兼容显式 row/col 或 "row_col"。
            val numericKey = key.toIntOrNull()
            val row = obj["row"]?.let { (it as? JsonPrimitive)?.intOrNull }
                ?: numericKey?.div(1000)
                ?: key.substringBefore('_').toIntOrNull()
                ?: return@mapNotNull null
            val col = obj["col"]?.let { (it as? JsonPrimitive)?.intOrNull }
                ?: numericKey?.rem(1000)
                ?: key.substringAfter('_', "").toIntOrNull()
                ?: return@mapNotNull null
            LibrarySeat(
                id = string(obj, "id"),
                name = string(obj, "name"),
                row = row,
                col = col,
                type = string(obj, "type").ifBlank { "seat" },
                enabled = boolean(obj, "enabled"),
                status = string(obj, "status").ifBlank { "UNKNOWN" },
                localOnly = boolean(obj, "local"),
            )
        }.filter { it.id.isNotBlank() }
        if (seats.isEmpty()) fail("学校未返回座位布局")
        return LibrarySeatLayout(rows, cols, seats)
    }

    fun parseStartTimes(body: String): List<LibraryTimeOption> {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("开始时间响应缺少 data")
        val arr = data["startTimes"] as? JsonArray ?: fail("学校未返回开始时间")
        return parseTimeArray(arr)
    }

    fun parseEndTimes(body: String): List<LibraryTimeOption> {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] as? JsonObject ?: fail("结束时间响应缺少 data")
        val arr = data["endTimes"] as? JsonArray ?: fail("学校未返回结束时间")
        return parseTimeArray(arr)
    }

    private fun parseTimeArray(arr: JsonArray): List<LibraryTimeOption> = arr.map { item ->
        val obj = item as? JsonObject ?: fail("时间字段格式异常")
        LibraryTimeOption(
            id = primitiveText(obj["id"]) ?: fail("时间缺少 id"),
            value = string(obj, "value").ifBlank { primitiveText(obj["id"]).orEmpty() },
        )
    }

    /** 当前预约（`/rest/v2/user/reservations`）返回数组；历史接口返回 `{reservations,count}`。 */
    fun parseReservations(body: String): List<LibraryReservation> {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
        val data = root["data"] ?: return emptyList()
        if (data is JsonNull) return emptyList()
        val arr = when (data) {
            is JsonArray -> data
            is JsonObject -> data["reservations"] as? JsonArray ?: emptyList()
            else -> fail("预约记录格式异常")
        }
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            LibraryReservation(
                id = string(obj, "id"),
                date = string(obj, "date", "onDate"),
                begin = string(obj, "begin"),
                end = string(obj, "end"),
                location = string(obj, "location", "loc"),
                status = string(obj, "stat", "status"),
            )
        }.filter { it.id.isNotBlank() }
    }

    fun ensureBooked(body: String) {
        val root = objectOf(body)
        ensureBusinessSuccess(root)
    }

    internal fun objectOf(body: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: fail("学校返回格式已变化，请稍后重试")
        return root
    }

    internal fun ensureBusinessSuccess(root: JsonObject) {
        // 页面返回里 code 可能是数字 12，也可能是字符串 "12"；status 可能是布尔值，
        // 也可能是 "success"/"fail"。两种都要按失败处理，不能落到“缺少 data”这种模糊错误。
        val code = (root["code"] as? JsonPrimitive)?.let {
            it.intOrNull ?: it.contentOrNull?.toIntOrNull()
        }
        if (code == 12) throw LibrarySessionExpiredException()
        val statusPrimitive = root["status"] as? JsonPrimitive
        val statusBoolean = statusPrimitive?.booleanOrNull
        val statusText = statusPrimitive?.contentOrNull?.lowercase()
        if (statusBoolean == false || statusText == "fail" || statusText == "error") {
            val message = (root["message"] as? JsonPrimitive)?.contentOrNull
                ?: "学校返回操作失败"
            throw LibrarySeatException(message)
        }
        if (statusBoolean == null && statusText == null) {
            // 少数接口用 code=0/200 表示成功；其它 code 一律当作失败。
            if (code != null && code != 0 && code != 200) {
                val message = (root["message"] as? JsonPrimitive)?.contentOrNull
                    ?: "学校返回错误（code=$code）"
                throw LibrarySeatException(message)
            }
            if (root["success"] == null && root["data"] == null && root["status"] == null) {
                fail("学校返回格式已变化，请稍后重试")
            }
        }
    }

    private fun string(obj: JsonObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> primitiveText(obj[key]) }.orEmpty()

    private fun primitiveText(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }

    private fun int(obj: JsonObject, vararg keys: String): Int =
        keys.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.intOrNull }
            ?: fail("缺少数字字段 ${keys.first()}")

    private fun boolean(obj: JsonObject, key: String): Boolean {
        val raw = (obj[key] as? JsonPrimitive)?.contentOrNull ?: return false
        return raw == "1" || raw.equals("true", ignoreCase = true)
    }

    private fun fail(message: String): Nothing = throw LibrarySeatException(message)
}
