package com.upcpocket.app.data.nspace

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 公开 nspace 前端 `saveReservation` 的固定 JSON 形状。
 *
 * `extraField` 只放学校 `getRequiredOption` 返回的普通字段；theme/phone/outline
 * 是公开前端单独提升到顶层的三个字段。这里不计算签名，也不补造未知字段。
 */
object NspaceBookingPayload {
    fun toJson(request: NspaceBookingRequest): String {
        require(request.begin >= 0 && request.end > request.begin) { "预约时间不合法" }
        require(request.onDate.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) { "日期格式不合法" }
        require(request.roomId.isNotBlank() && request.useType.isNotBlank()) {
            "房间和使用类型不能为空"
        }
        return buildJsonObject {
            put("begin", request.begin)
            put("end", request.end)
            put("onDate", request.onDate)
            put("roomId", request.roomId)
            put("useType", request.useType)
            put("participants", request.participants)
            put("filePath", request.filePath)
            put("source", request.source)
            put("seatNo", request.seatNo)
            put("teamId", request.teamId)
            request.theme?.let { put("theme", it) }
            request.phone?.let { put("phone", it) }
            request.outline?.let { put("outline", it) }
            put(
                "extraField",
                buildJsonObject {
                    request.extraField.forEach { (key, value) -> put(key, value) }
                },
            )
        }.toString()
    }
}
