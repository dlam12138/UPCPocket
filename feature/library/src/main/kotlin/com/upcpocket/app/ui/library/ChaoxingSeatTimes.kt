package com.upcpocket.app.ui.library

import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

internal val CHAOXING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
internal fun chaoxingNow(): ZonedDateTime = ZonedDateTime.now(CHAOXING_ZONE)

private fun ChaoxingSeatRoomDetail.validTimeRules(): Boolean =
    !closed && timeUnit > 0 && startMinute in 0..1439 && endMinute in 1..1440 && startMinute < endMinute

private fun allowedStart(detail: ChaoxingSeatRoomDetail, start: Int, date: String?, now: ZonedDateTime): Boolean {
    if (!detail.validTimeRules() || start < detail.startMinute || start >= detail.endMinute ||
        (start - detail.startMinute) % detail.timeUnit != 0) return false
    if (date == null) return true // Time-rule-only overload for existing callers/tests without a date.
    val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return false
    return !day.atStartOfDay(CHAOXING_ZONE).plusMinutes(start.toLong()).isBefore(now)
}

/** One source for defaults, picker choices, query validation and final preflight. */
internal fun chaoxingEndOptions(
    detail: ChaoxingSeatRoomDetail, start: Int, date: String? = null, now: ZonedDateTime = chaoxingNow(),
): List<Int> {
    if (!allowedStart(detail, start, date, now)) return emptyList()
    val result = mutableListOf<Int>()
    var end = start.toLong() + detail.timeUnit
    while (end <= detail.endMinute) {
        val duration = end - start
        if ((detail.minDurationMinutes == null || duration >= detail.minDurationMinutes) &&
            (detail.maxDurationMinutes == null || duration <= detail.maxDurationMinutes)) result += end.toInt()
        end += detail.timeUnit
    }
    return result
}

internal fun chaoxingStartOptions(
    detail: ChaoxingSeatRoomDetail, date: String? = null, now: ZonedDateTime = chaoxingNow(),
): List<Int> {
    if (!detail.validTimeRules()) return emptyList()
    val result = mutableListOf<Int>()
    var start = detail.startMinute.toLong()
    while (start < detail.endMinute) {
        if (chaoxingEndOptions(detail, start.toInt(), date, now).isNotEmpty()) result += start.toInt()
        start += detail.timeUnit
    }
    return result
}

internal fun chaoxingTimeUnavailableReason(detail: ChaoxingSeatRoomDetail, date: String, now: ZonedDateTime): String? {
    if (detail.closed) return "当前房间未开放"
    if (!detail.validTimeRules()) return "时间规则不可用"
    val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return "日期不可用"
    val today = now.withZoneSameInstant(CHAOXING_ZONE).toLocalDate()
    if (day < today) return "日期已过期"
    if (chaoxingStartOptions(detail, date, now).isNotEmpty()) return null
    return if (day == today) "今天已无符合时长规则的时段" else "没有符合时长规则的时段"
}
