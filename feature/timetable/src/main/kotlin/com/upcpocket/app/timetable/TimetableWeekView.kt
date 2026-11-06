package com.upcpocket.app.timetable

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 周网格的连续“周日至周六”视窗。
 *
 * 学校 `get-datatmp` 的 dates 是真实连续 7 天；UPC 学期从周日起（例如 2026-09-06），
 * 因此正常情况下直接保留源顺序即可。若源周从周一或其他日期开始，不能把旧周列简单
 * 旋转（会把日期和课程错位），而是按**真实日期**构造周日到周六视窗：
 * 仅展示源周确实覆盖的日期，未覆盖的日期放入 [unknownDates]，不推断为无课。
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
    val start = sourceDates.first()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val dates = (0L..6L).map(start::plusDays)
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
