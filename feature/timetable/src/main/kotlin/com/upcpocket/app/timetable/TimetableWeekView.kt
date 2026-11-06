package com.upcpocket.app.timetable

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 周网格视窗。
 *
 * 学校 `get-datatmp` 的 dates 是真实连续 7 天；UPC 学期通常从周日起
 * （例如 2026-09-06），此时保持周日至周六。若源周不是周日起，不能旋转或
 * 强行套周日框架（会把最后一天挤出视窗、丢课程），直接使用源周 7 天；
 * 只有源周没有覆盖的日期才进入 [unknownDates]，不推断为无课。
 */
internal data class TimetableWeekView(
    val dates: List<LocalDate>,
    val meetings: List<TimetableMeeting>,
    val knownDates: Set<LocalDate>,
    val unknownDates: Set<LocalDate>,
)

internal fun buildTimetableWeekView(
    sourceDates: List<LocalDate>,
    meetings: List<TimetableMeeting>,
): TimetableWeekView {
    require(sourceDates.size == 7) { "周视图必须恰好 7 天" }
    require(sourceDates.zipWithNext().all { (a, b) -> a.plusDays(1) == b }) {
        "周视图日期必须连续"
    }
    val dates = if (sourceDates.first().dayOfWeek == DayOfWeek.SUNDAY) {
        (0L..6L).map(sourceDates.first()::plusDays)
    } else {
        // 非周日起：保留源周真实 7 天，避免最后一天被挤出周日至周六视窗。
        sourceDates
    }
    val known = sourceDates.toSet()
    return TimetableWeekView(
        dates = dates,
        meetings = meetings
            .filter { it.date in dates }
            .sortedWith(
                compareBy<TimetableMeeting>(
                    { it.date },
                    { it.startPeriod },
                    { it.endPeriod },
                    { it.courseName },
                    { it.location.orEmpty() },
                    { it.teacher.orEmpty() },
                    { it.instanceId },
                ),
            ),
        knownDates = known,
        unknownDates = dates.filterNot(known::contains).toSet(),
    )
}
