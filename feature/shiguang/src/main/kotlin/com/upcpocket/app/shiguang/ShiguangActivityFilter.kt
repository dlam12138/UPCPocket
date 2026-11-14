package com.upcpocket.app.shiguang

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal enum class ShiguangActivityFilter { ALL, CAMPUS, SOCIAL_PRACTICE }

private val ShanghaiZone = ZoneId.of("Asia/Shanghai")
private val EventRange = Regex(
    "^\\s*(?<startDate>${DATE_PATTERN})\\s+(?<startTime>${TIME_PATTERN})" +
        "(?:\\s*(?:至|到|[-~～—–])\\s*(?:(?<endDate>${DATE_PATTERN})\\s*)?(?<endTime>${TIME_PATTERN}))?\\s*$"
)
private val ExplicitEndTime = Regex("^\\s*(?<date>${DATE_PATTERN})\\s+(?<time>${TIME_PATTERN})\\s*$")

private const val DATE_PATTERN = "(?:\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日)"
private const val TIME_PATTERN = "(?:[01]?\\d|2[0-3]):[0-5]\\d"

/** Filters only this already loaded server page. Unknown or partial event times stay visible. */
internal fun ShiguangPage.visibleActivities(
    filter: ShiguangActivityFilter,
    hideEnded: Boolean,
    now: Instant = Clock.systemUTC().instant(),
): List<ShiguangActivity> = items.filter { activity ->
    val kindMatches = when (filter) {
        ShiguangActivityFilter.ALL -> true
        ShiguangActivityFilter.CAMPUS -> activity.kind == ShiguangKind.ACTIVITY
        ShiguangActivityFilter.SOCIAL_PRACTICE -> activity.kind == ShiguangKind.SOCIAL_PRACTICE
    }
    kindMatches && (!hideEnded || !activity.hasExplicitlyEnded(now))
}

private fun ShiguangActivity.hasExplicitlyEnded(now: Instant): Boolean {
    val eventEnd = fields["活动时间"]?.let(::eventRangeEnd)
    val explicitEnd = fields["结束时间"]?.let(::explicitEnd)
    val end = eventEnd ?: explicitEnd ?: return false
    return end.isBefore(now)
}

private fun eventRangeEnd(value: String): Instant? {
    val match = EventRange.matchEntire(value) ?: return null
    val startDate = match.groups["startDate"]?.value?.let(::parseDate) ?: return null
    val startTime = match.groups["startTime"]?.value?.let(::parseTime) ?: return null
    val endTimeText = match.groups["endTime"]?.value ?: return null
    val endDateText = match.groups["endDate"]?.value
    val endDate = if (endDateText == null) startDate else parseDate(endDateText) ?: return null
    val endTime = parseTime(endTimeText) ?: return null
    val start = startDate.atTime(startTime)
    val end = endDate.atTime(endTime)
    if (end.isBefore(start)) return null
    return end.atZone(ShanghaiZone).toInstant()
}

private fun explicitEnd(value: String): Instant? {
    val match = ExplicitEndTime.matchEntire(value) ?: return null
    val date = match.groups["date"]?.value?.let(::parseDate) ?: return null
    val time = match.groups["time"]?.value?.let(::parseTime) ?: return null
    return date.atTime(time).atZone(ShanghaiZone).toInstant()
}

private fun parseDate(value: String): LocalDate? {
    val parts = Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
    if (parts.size != 3 || parts[0] !in 1000..9999) return null
    // LocalDate.of rejects invalid dates instead of normalizing them (for example, Feb 30).
    return runCatching { LocalDate.of(parts[0], parts[1], parts[2]) }.getOrNull()
}

private fun parseTime(value: String): LocalTime? {
    val parts = value.split(':').mapNotNull(String::toIntOrNull)
    if (parts.size != 2) return null
    return runCatching { LocalTime.of(parts[0], parts[1]) }.getOrNull()
}
