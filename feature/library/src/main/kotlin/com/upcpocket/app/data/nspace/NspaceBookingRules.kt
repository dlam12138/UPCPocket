package com.upcpocket.app.data.nspace

import kotlin.math.min

/**
 * nspace 预约时段与表单规则。
 *
 * 规则来自公开前端的 openTime/closeTime/disableTime/timeSlice/bookPolicy 和
 * getRequiredOption；不猜测缺失字段。无法安全原生的房间（多人、选座、附件、
 * 未知表单类型）会明确拒绝，而不是提交一个可能失败的请求。
 */
object NspaceBookingRules {
    private val supportedTypes = setOf("INPUT", "TEXTAREA", "SELEC", "RADIO")

    fun nativeEligibilityReason(
        detail: NspaceRoomDetail,
        fields: List<NspaceFormField>,
        useTypes: List<NspaceUseType>,
    ): String? {
        if (useTypes.isEmpty()) return "学校未返回使用类型。"
        if (detail.timeSlice <= 0) return "学校未返回可用时段步长。"
        if (detail.minTime <= 0 || detail.maxTime < detail.minTime) {
            return "学校预约时长规则不完整。"
        }
        if (detail.minUser > 1) return "该房间要求多人组队，请使用官方页面。"
        if (detail.seatOpen.equals("OPEN", ignoreCase = true)) {
            return "该房间要求选座，请使用官方页面。"
        }
        if (detail.annexRequired) return "该房间要求上传附件，请使用官方页面。"
        if (fields.any { it.type !in supportedTypes }) {
            return "学校返回了尚未支持的表单类型，请使用官方页面。"
        }
        return null
    }

    fun startOptions(detail: NspaceRoomDetail, nowMinutes: Int? = null): List<Int> {
        if (detail.timeSlice <= 0 || detail.minTime <= 0) return emptyList()
        val earliest = if (nowMinutes != null && nowMinutes > detail.openTime) {
            alignUp(nowMinutes, detail.timeSlice)
        } else {
            detail.openTime
        }
        val result = mutableListOf<Int>()
        var start = alignUp(earliest, detail.timeSlice)
        while (start + detail.minTime <= detail.closeTime) {
            if (endOptions(detail, start).isNotEmpty()) result += start
            start += detail.timeSlice
        }
        return result
    }

    fun endOptions(detail: NspaceRoomDetail, start: Int): List<Int> {
        if (start < detail.openTime || start >= detail.closeTime) return emptyList()
        if (overlapsDisabled(detail, start, start + detail.minTime)) return emptyList()
        val result = mutableListOf<Int>()
        val latest = min(start + detail.maxTime, detail.closeTime)
        var end = start + detail.minTime
        while (end <= latest) {
            if (!overlapsDisabled(detail, start, end)) result += end
            end += detail.timeSlice
        }
        return result
    }

    fun validate(detail: NspaceRoomDetail, start: Int, end: Int): String? {
        if (start < detail.openTime || end > detail.closeTime) return "时间超出房间开放时段。"
        if (end <= start) return "结束时间必须晚于开始时间。"
        val duration = end - start
        if (duration < detail.minTime) return "预约时长不能少于 ${detail.minTime} 分钟。"
        if (duration > detail.maxTime) return "预约时长不能超过 ${detail.maxTime} 分钟。"
        if (overlapsDisabled(detail, start, end)) return "所选时间包含不可预约时段。"
        return null
    }

    fun initialValues(fields: List<NspaceFormField>): Map<String, String> =
        fields.associate { it.field to it.value }

    /**
     * 公开前端把 theme/phone/outline 放顶层，其余表单字段放 extraField。
     * 这里只发送用户已填写/已选择的字段，不补空字段。
     */
    fun splitValues(values: Map<String, String>): NspaceSplitFields {
        var theme: String? = null
        var phone: String? = null
        var outline: String? = null
        val extra = linkedMapOf<String, String>()
        values.forEach { (key, value) ->
            if (value.isNotBlank()) {
                when (key) {
                    "theme" -> theme = value
                    "phone" -> phone = value
                    "outline" -> outline = value
                    else -> extra[key] = value
                }
            }
        }
        return NspaceSplitFields(theme, phone, outline, extra)
    }

    private fun overlapsDisabled(detail: NspaceRoomDetail, start: Int, end: Int): Boolean =
        detail.disableTimes.any { disabled -> start < disabled.end && end > disabled.start }

    private fun alignUp(value: Int, step: Int): Int {
        if (step <= 0) return value
        val remainder = value % step
        return if (remainder == 0) value else value + (step - remainder)
    }
}

/** 顶层三个字段与 extraField 的明确分组。 */
data class NspaceSplitFields(
    val theme: String?,
    val phone: String?,
    val outline: String?,
    val extraField: Map<String, String>,
)
