package com.upcpocket.app.data.nspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * nspace 的严格 JSON 适配器。
 *
 * 只接受公开前端实际使用的 `/spa` 路径和字段；认证失效、空列表、协议缺字段分开处理。
 * 不把 nspace token 与 libseat token 混用，也不把 HTML 登录页当成成功响应。
 */
internal object NspaceProtocol {
    const val BASE_URL = "https://libspace.upc.edu.cn"
    const val ENTRY_URL =
        "$BASE_URL/remote/static/sso/login" +
            "?redirectUrl=https%3A%2F%2Flibspace.upc.edu.cn%2Fnspace%2F%23%2Flogin"
    const val OFFICIAL_PAGE = "$BASE_URL/nspace/"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseVenues(body: String): List<NspaceVenue> {
        val data = arrayData(body)
        return data.map { item ->
            val obj = item as? JsonObject ?: fail("馆区不是对象")
            NspaceVenue(
                id = string(obj, "id"),
                name = string(obj, "name"),
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    fun parseRoomTypes(body: String): List<NspaceRoomType> {
        val data = arrayData(body)
        return data.map { item ->
            val obj = item as? JsonObject ?: fail("空间类型不是对象")
            NspaceRoomType(
                id = string(obj, "id"),
                name = string(obj, "name"),
                enabled = int(obj, "status")?.let { it == 0 } ?: true,
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    fun parseDates(body: String): List<String> {
        val data = arrayData(body)
        return data.mapNotNull { item ->
            (item as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (item as? JsonObject)?.let { obj ->
                    stringOrNull(obj, "date")
                        ?: stringOrNull(obj, "onDate")
                        ?: stringOrNull(obj, "selectDate")
                }
        }.distinct()
    }

    fun parseRoomPage(body: String): NspaceRoomPage {
        val data = objectData(body)
        val list = data["pageList"] as? JsonArray ?: fail("房间响应缺少 pageList")
        val rooms = list.map { item ->
            val obj = item as? JsonObject ?: fail("房间不是对象")
            val time = obj["roomTimeSliceDto"] as? JsonObject
            NspaceRoom(
                id = string(obj, "id"),
                name = string(obj, "name"),
                floor = stringOrNull(obj, "floor").orEmpty(),
                minUser = int(obj, "minUser"),
                maxUser = int(obj, "maxUser"),
                area = stringOrNull(obj, "area")?.takeIf { it.isNotBlank() },
                status = stringOrNull(obj, "status"),
                seatOpen = stringOrNull(obj, "seatOpen"),
                openTime = stringOrNull(time, "openTime"),
                closeTime = stringOrNull(time, "closeTime"),
                timeSlice = stringOrNull(time, "timeSlice"),
                isSign = int(obj, "isSign")?.let { it == 1 },
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        return NspaceRoomPage(
            rooms = rooms,
            currentPage = int(data, "currentPage") ?: 1,
            totalPage = int(data, "totalPage") ?: 1,
            totalCount = int(data, "totalCount") ?: rooms.size,
        )
    }

    fun parseReservations(body: String): NspaceReservationPage {
        val data = objectData(body)
        val list = data["pageList"] as? JsonArray ?: fail("预约响应缺少 pageList")
        val reservations = list.map { item ->
            val obj = item as? JsonObject ?: fail("预约记录不是对象")
            NspaceReservation(
                id = string(obj, "id"),
                roomName = stringOrNull(obj, "roomName").orEmpty(),
                onDate = string(obj, "onDate"),
                begin = int(obj, "begin") ?: fail("预约记录缺少 begin"),
                end = int(obj, "end") ?: fail("预约记录缺少 end"),
                status = int(obj, "status") ?: fail("预约记录缺少 status"),
                statusDescription = stringOrNull(obj, "statusDes"),
                master = int(obj, "master") == 0,
            )
        }.filter { it.id.isNotBlank() }
        return NspaceReservationPage(
            reservations = reservations,
            currentPage = int(data, "currentPage") ?: 1,
            totalPage = int(data, "totalPage") ?: 1,
            totalCount = int(data, "totalCount") ?: reservations.size,
        )
    }

    fun parseRoomDetail(body: String): NspaceRoomDetail {
        val data = objectData(body)
        val time = data["roomTimeSliceDto"] as? JsonObject
            ?: fail("房间详情缺少 roomTimeSliceDto")
        val policy = data["bookPolicy"] as? JsonObject
            ?: fail("房间详情缺少 bookPolicy")
        val disable = (time["disableTime"] as? JsonArray).orEmpty().map { item ->
            val pair = item as? JsonArray ?: fail("disableTime 不是区间")
            if (pair.size < 2) fail("disableTime 缺少起止")
            val start = (pair[0] as? JsonPrimitive)?.intOrNull
                ?: fail("disableTime 起点不是整数")
            val end = (pair[1] as? JsonPrimitive)?.intOrNull
                ?: fail("disableTime 终点不是整数")
            NspaceInterval(start, end)
        }
        val open = int(time, "openTime") ?: fail("房间缺少 openTime")
        val close = int(time, "closeTime") ?: fail("房间缺少 closeTime")
        val slice = int(time, "timeSlice") ?: fail("房间缺少 timeSlice")
        val minTime = int(policy, "minTime") ?: fail("房间缺少 minTime")
        val maxTime = int(policy, "maxTime") ?: fail("房间缺少 maxTime")
        return NspaceRoomDetail(
            roomId = string(data, "id"),
            name = string(data, "name"),
            openTime = open,
            closeTime = close,
            timeSlice = slice,
            disableTimes = disable,
            minTime = minTime,
            maxTime = maxTime,
            minUser = int(data, "minUser") ?: 1,
            maxUser = int(data, "maxUser") ?: 1,
            seatOpen = stringOrNull(data, "seatOpen").orEmpty(),
            annexRequired = (int(data, "annex") ?: 0) != 0,
        )
    }

    fun parseUseTypes(body: String): List<NspaceUseType> {
        val data = arrayData(body)
        return data.map { item ->
            val obj = item as? JsonObject ?: fail("使用类型不是对象")
            NspaceUseType(
                id = string(obj, "id"),
                name = string(obj, "name"),
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    fun parseFormFields(body: String): List<NspaceFormField> {
        val data = arrayData(body)
        return data.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val field = stringOrNull(obj, "field") ?: return@mapNotNull null
            val options = (obj["optionList"] as? JsonArray).orEmpty().mapNotNull { raw ->
                val option = raw as? JsonObject ?: return@mapNotNull null
                val name = stringOrNull(option, "name") ?: return@mapNotNull null
                val value = stringOrNull(option, "value") ?: return@mapNotNull null
                NspaceFormOption(name, value)
            }
            NspaceFormField(
                field = field,
                label = stringOrNull(obj, "label").orEmpty().ifBlank { field },
                type = stringOrNull(obj, "type").orEmpty().uppercase(),
                required = int(obj, "required") == 0,
                value = stringOrNull(obj, "value").orEmpty(),
                options = options,
            )
        }
    }

    fun ensureActionSuccess(body: String) {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: fail("操作响应不是 JSON")
        val code = (root["code"] as? JsonPrimitive)?.let {
            it.intOrNull ?: it.contentOrNull?.toIntOrNull()
        }
        if (code == 401 || code == 403) throw NspaceSessionExpiredException()
        if (code != null && code != 0 && code != 200) {
            throw NspaceException("学校返回业务错误", "nspace_upstream")
        }
        val status = root["status"] as? JsonPrimitive
        if (status?.booleanOrNull == false ||
            status?.contentOrNull?.lowercase() in setOf("fail", "error")
        ) {
            throw NspaceException("学校返回业务失败", "nspace_upstream")
        }
        val success = code == 0 || code == 200 ||
            status?.booleanOrNull == true ||
            status?.contentOrNull?.lowercase() == "success"
        if (!success) fail("操作响应缺少成功标记")
    }

    private fun arrayData(body: String): List<JsonElement> {
        val root = envelope(body)
        return (root["data"] as? JsonArray) ?: fail("响应缺少 data 数组")
    }

    private fun objectData(body: String): JsonObject {
        val root = envelope(body)
        return (root["data"] as? JsonObject) ?: fail("响应缺少 data 对象")
    }

    private fun envelope(body: String): JsonObject {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: fail("响应不是 JSON")
        val code = (root["code"] as? JsonPrimitive)?.let {
            it.intOrNull ?: it.contentOrNull?.toIntOrNull()
        }
        if (code == 401 || code == 403) throw NspaceSessionExpiredException()
        if (code != null && code != 0 && code != 200) {
            throw NspaceException("学校返回业务错误", "nspace_upstream")
        }
        val status = root["status"] as? JsonPrimitive
        if (status?.booleanOrNull == false ||
            status?.contentOrNull?.lowercase() in setOf("fail", "error")
        ) {
            throw NspaceException("学校返回业务失败", "nspace_upstream")
        }
        if (root["data"] == null) fail("响应缺少 data")
        return root
    }

    private fun string(obj: JsonObject, key: String): String =
        stringOrNull(obj, key) ?: fail("缺少 $key")

    private fun stringOrNull(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun int(obj: JsonObject?, key: String): Int? {
        val value = (obj?.get(key) as? JsonPrimitive) ?: return null
        return value.intOrNull ?: value.contentOrNull?.toIntOrNull()
    }

    private fun fail(message: String): Nothing =
        throw NspaceException(message, "nspace_protocol")
}
