package com.upcpocket.app.data.classroom

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class FreeClassroomCampus(val label: String) {
    TANGDAOWAN("唐岛湾校区"), GUZHENKOU("古镇口校区"),
}

/** 教室查询的节次来自学校公开页（1–13），与 App 内其它页面的作息设置无关。 */
data class FreeClassroomQuery(
    val campus: FreeClassroomCampus,
    val date: LocalDate,
    val building: String = "全部",
    val periods: Set<Int> = setOf(1, 2),
) {
    fun validate() {
        require(building.isNotBlank() && building.length <= 100) { "请选择教学楼" }
        require(periods.isNotEmpty() && periods.all { it in 1..13 }) { "请选择 1–13 节中的至少一节" }
    }
}

data class FreeClassroomRoom(
    val code: String,
    val name: String,
    val building: String,
    val campus: String,
    val capacity: Int?,
    /**
     * 只有学校 `js`“空闲教室”列表明确包含该教室时才为 true。
     *
     * `js` 是多节查询下**每一节都空闲**的交集，不是占用列表；未命中、空列表、
     * 缺字段或解析失败都不能当成空闲。
     */
    val available: Boolean,
)

data class FreeClassroomResult(
    val query: FreeClassroomQuery,
    val buildings: List<String>,
    val rooms: List<FreeClassroomRoom>,
    val fetchedAt: Instant,
) {
    val availableRooms: List<FreeClassroomRoom> get() = rooms.filter { it.available }
}

fun interface FreeClassroomRepository {
    suspend fun query(query: FreeClassroomQuery): FreeClassroomResult
}

/**
 * 仅执行学校公开页正常使用的只读查询；不认证、不带账号或 Cookie、不跟随重定向。
 *
 * 2026-11-05 用同一天、同校区、同教学楼、同节次的公开响应复核：
 * `d.js` 是**空闲教室**列表，多节查询返回“所选每一节都空闲”的交集；`d.all` 才是
 * 全部教室目录。旧实现把 `d.js` 当占用列表，导致未命中（例如卓越 B328 第 1–2 节
 * 有课、第 3–5 节空闲，查询第 1–5 节）反而显示为绿色“空闲”。任何未命中都不能
 * 推断为可用。
 */
class PublicFreeClassroomRepository : FreeClassroomRepository {
    override suspend fun query(query: FreeClassroomQuery): FreeClassroomResult = withContext(Dispatchers.IO) {
        query.validate()
        val connection = URI(ENDPOINT).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            val body = FreeClassroomProtocol.formBody(query).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            when (val status = connection.responseCode) {
                in 300..399 -> throw IOException("学校服务暂时不可用，请稍后重试")
                401, 403 -> throw IOException("学校服务暂时不可用，请稍后重试")
                200 -> Unit
                else -> throw IOException("学校服务暂时不可用，请稍后重试")
            }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) throw IOException("学校响应过大，请稍后重试")
                    bytes.write(buffer, 0, count)
                }
            }
            FreeClassroomProtocol.parse(bytes.toString(Charsets.UTF_8.name()), query, Instant.now())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val ENDPOINT = "https://app.upc.edu.cn/freeclass/wap/default/search-all"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

/** 独立协议解析便于用公开房间目录结构验证；错误响应绝不能变成“全部空闲”。 */
internal object FreeClassroomProtocol {
    fun formBody(query: FreeClassroomQuery): String {
        query.validate()
        val fields = listOf("xq" to query.campus.label, "date" to query.date.toString(), "lh" to query.building) +
            query.periods.sorted().map { "jc[]" to it.toString() }
        return fields.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    fun parse(body: String, query: FreeClassroomQuery, fetchedAt: Instant): FreeClassroomResult {
        try {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: invalid()
            if ((root["e"] as? JsonPrimitive)?.intOrNull != 0) {
                throw IOException("学校未返回有效查询结果，请稍后重试")
            }
            val data = root["d"] as? JsonObject ?: invalid()
            val all = groupedRooms(data["all"])
            // `js` 是空闲教室列表；它是 `all` 的子集，不是占用列表。
            val free = groupedRooms(data["js"])
            if (free.keys.any { it !in all.keys }) invalid()
            val choices = data["listLh"] as? JsonArray ?: invalid()
            val buildings = choices.map { choice ->
                val item = choice as? JsonObject ?: invalid()
                requiredText(item, "id")
            }.distinct().let { if ("全部" in it) it else listOf("全部") + it }
            val freeCodes = free.values.flatten().map { requiredText(it, "code") }.toSet()
            val rooms = all.flatMap { (groupName, groupRooms) ->
                groupRooms.map { item ->
                    val code = requiredText(item, "code")
                    val campus = requiredText(item, "xqm")
                    val building = requiredText(item, "jxlm")
                    if (campus != query.campus.label || building != groupName ||
                        (query.building != "全部" && building != query.building)
                    ) invalid()
                    FreeClassroomRoom(
                        code = code,
                        name = requiredText(item, "name"),
                        building = building,
                        campus = campus,
                        capacity = (item["num"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 },
                        available = code in freeCodes,
                    )
                }
            }
            val codes = rooms.map { it.code }.toSet()
            if (rooms.size != codes.size || !codes.containsAll(freeCodes)) invalid()
            return FreeClassroomResult(query, buildings, rooms, fetchedAt)
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            throw IOException("学校返回格式已变化，请稍后重试")
        }
    }

    private fun groupedRooms(value: JsonElement?): Map<String, List<JsonObject>> = when (value) {
        is JsonObject -> value.mapValues { (_, group) ->
            val entries = group as? JsonArray ?: invalid()
            entries.map { it as? JsonObject ?: invalid() }
        }
        // PHP 空关联数组可能序列化成 []，只接受空数组，非空数组不能猜字段。
        is JsonArray -> if (value.isEmpty()) emptyMap() else invalid()
        else -> invalid()
    }

    private fun requiredText(item: JsonObject, key: String): String =
        (item[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: invalid()

    private fun invalid(): Nothing = throw IOException("学校返回格式已变化，请稍后重试")
}
