package com.upcpocket.app.data.chaoxing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Chaoxing 座位的严格只读适配器。
 *
 * 字段名来自已登录页面真实响应；缺字段/HTML 登录页/`success=false` 分别失败，
 * 不把空列表当成“无座位”。时间统一转为北京时间当天分钟。
 */
object ChaoxingProtocol {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val weekdayKeys = arrayOf("mon", "tues", "wed", "thur", "fri", "sat", "sun")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseIdentity(html: String): ChaoxingUser {
        val raw = extractJsonObject(html, "userLoginInfo")
            ?: throw ChaoxingException("登录页缺少 userLoginInfo", "chaoxing_protocol")
        val root = parseObject(raw) ?: throw ChaoxingException(
            "userLoginInfo 不是 JSON",
            "chaoxing_protocol",
        )
        val user = root["userInfo"] as? JsonObject
            ?: throw ChaoxingException("userLoginInfo 缺少 userInfo", "chaoxing_protocol")
        val sno = stringOrNull(user, "sno")
            ?.takeIf { it.isNotBlank() }
            ?: throw ChaoxingException("userInfo 缺少 sno", "chaoxing_protocol")
        return ChaoxingUser(sno)
    }

    fun parseSeatRooms(body: String, kind: ChaoxingSeatKind, date: String): ChaoxingSeatRoomPage {
        val data = objectData(body)
        val list = data["seatRoomList"] as? JsonArray
            ?: throw ChaoxingException("座位响应缺少 seatRoomList", "chaoxing_protocol")
        val day = parseDate(date)
        val rooms = list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = int(obj, "id") ?: return@mapNotNull null
            val hours = hours(obj["seatSpecialTime"] as? JsonObject, day)
                ?: hours(obj["seatEngineSpecialTime"] as? JsonObject, day)
            if (hours == null) return@mapNotNull null
            ChaoxingSeatRoom(
                id = id,
                name = listOfNotNull(
                    stringOrNull(obj, "firstLevelName"),
                    stringOrNull(obj, "secondLevelName"),
                    stringOrNull(obj, "thirdLevelName"),
                ).joinToString("-").ifBlank { "区域 $id" },
                floor = stringOrNull(obj, "secondLevelName").orEmpty(),
                capacity = int(obj, "capacity") ?: 0,
                usedCount = int(obj, "curUsedCount"),
                startMinute = hours.first,
                endMinute = hours.second,
            )
        }
        return ChaoxingSeatRoomPage(
            rooms = rooms,
            totalRow = int(data, "totalRow") ?: rooms.size,
            totalPage = int(data, "totalPage") ?: 1,
        )
    }

    fun parseSeatRoomDetail(
        body: String,
        date: String,
        expectedRoomId: Int,
    ): ChaoxingSeatRoomDetail {
        val data = objectData(body)
        val config = data["seatConfig"] as? JsonObject
            ?: throw ChaoxingException("房间详情缺少 seatConfig", "chaoxing_protocol")
        val room = data["seatRoom"] as? JsonObject
            ?: throw ChaoxingException("房间详情缺少 seatRoom", "chaoxing_protocol")
        val timeUnit = int(config, "timeUnit")
            ?.takeIf { it > 0 }
            ?: throw ChaoxingException("房间详情缺少 timeUnit", "chaoxing_protocol")
        val day = parseDate(date)
        val hours = hours(config["commonTimeConfig"] as? JsonObject, day)
            ?: hours(room["seatSpecialTime"] as? JsonObject, day)
            ?: throw ChaoxingException("房间详情缺少开放时段", "chaoxing_protocol")
        val roomId = int(room, "id") ?: expectedRoomId
        if (roomId != expectedRoomId) {
            throw ChaoxingException("房间详情与请求区域不一致", "chaoxing_protocol")
        }
        return ChaoxingSeatRoomDetail(
            roomId = roomId,
            name = listOfNotNull(
                stringOrNull(room, "firstLevelName"),
                stringOrNull(room, "secondLevelName"),
                stringOrNull(room, "thirdLevelName"),
            ).joinToString("-").ifBlank { "区域 $roomId" },
            capacity = int(room, "capacity") ?: 0,
            timeUnit = timeUnit,
            startMinute = hours.first,
            endMinute = hours.second,
            places = emptyList(),
        )
    }

    fun parseSeatPlaces(body: String): List<ChaoxingSeatPlace> {
        val data = dataArray(body)
        return data.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val seatNum = stringOrNull(obj, "seatNum") ?: return@mapNotNull null
            ChaoxingSeatPlace(
                seatNum = seatNum,
                x = int(obj, "xleft") ?: 0,
                y = int(obj, "ytop") ?: 0,
                width = int(obj, "width") ?: 0,
                height = int(obj, "height") ?: 0,
                rotate = int(obj, "rotate") ?: 0,
            )
        }
    }

    fun parseUsedSeatNums(body: String): Set<String> {
        val data = objectData(body)
        val list = data["seatReserves"] as? JsonArray
            ?: throw ChaoxingException("已用座位响应缺少 seatReserves", "chaoxing_protocol")
        return list.mapNotNull { item ->
            (item as? JsonObject)?.let { stringOrNull(it, "seatNum") }
        }.toSet()
    }

    fun parseUsedTimes(body: String): List<ChaoxingTimeInterval> {
        val data = dataArray(body)
        return data.mapNotNull { item ->
            val pair = item as? JsonArray ?: return@mapNotNull null
            if (pair.size < 2) return@mapNotNull null
            val start = (pair[0] as? JsonPrimitive)?.longOrNull() ?: return@mapNotNull null
            val end = (pair[1] as? JsonPrimitive)?.longOrNull() ?: return@mapNotNull null
            val startMinute = epochMinute(start)
            val endMinute = epochMinute(end)
            ChaoxingTimeInterval(
                startMinute = startMinute,
                endMinute = if (endMinute <= startMinute) endMinute + 24 * 60 else endMinute,
            )
        }
    }

    fun dayLabel(date: String): String = parseDate(date).toString()

    private fun hours(container: JsonObject?, day: LocalDate): Pair<Int, Int>? {
        container ?: return null
        val key = weekdayKeys[day.dayOfWeek.value - 1]
        val start = parseMinute(stringOrNull(container, "${key}StartTime")) ?: return null
        val end = parseMinute(stringOrNull(container, "${key}EndTime")) ?: return null
        if (end <= start) return null
        return start to end
    }

    private fun parseMinute(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.matches(Regex("""\d{1,2}:\d{2}""")) } ?: return null
        val parts = text.split(':')
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun epochMinute(value: Long): Int {
        val instant = if (value > 1_000_000_000_000L) {
            Instant.ofEpochMilli(value)
        } else {
            Instant.ofEpochSecond(value)
        }
        val time = instant.atZone(zone).toLocalTime()
        return time.hour * 60 + time.minute
    }

    private fun parseDate(raw: String): LocalDate =
        runCatching { LocalDate.parse(raw) }
            .getOrElse { throw ChaoxingException("日期格式不合法", "chaoxing_protocol") }

    private fun dataArray(body: String): List<JsonElement> =
        (envelope(body)["data"] as? JsonArray)
            ?: throw ChaoxingException("响应缺少 data 数组", "chaoxing_protocol")

    private fun objectData(body: String): JsonObject =
        (envelope(body)["data"] as? JsonObject)
            ?: throw ChaoxingException("响应缺少 data 对象", "chaoxing_protocol")

    private fun envelope(body: String): JsonObject {
        val root = parseObject(body)
            ?: throw ChaoxingException("响应不是 JSON", "chaoxing_protocol")
        if (root["success"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == false) {
            throw ChaoxingException("学校返回业务失败", "chaoxing_upstream")
        }
        if (root["data"] == null) {
            throw ChaoxingException("响应缺少 data", "chaoxing_protocol")
        }
        return root
    }

    private fun parseObject(body: String): JsonObject? = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun stringOrNull(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun int(obj: JsonObject?, key: String): Int? {
        val value = obj?.get(key) as? JsonPrimitive ?: return null
        return value.intOrNull ?: value.contentOrNull?.toIntOrNull()
    }

    private fun JsonPrimitive.longOrNull(): Long? =
        contentOrNull?.toLongOrNull()

    /**
     * 找到 marker 后的第一个 JSON 对象，按字符串/转义规则配平花括号。
     * 不能使用非贪婪正则，因为 userInfo 是嵌套对象。
     */
    private fun extractJsonObject(html: String, marker: String): String? {
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val start = html.indexOf('{', markerIndex)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
