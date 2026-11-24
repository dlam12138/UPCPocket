package com.upcpocket.app.ui.todo

import com.upcpocket.app.todo.PersonalSchedule
import com.upcpocket.app.todo.data.PersonalScheduleTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 个人日程的纯展示规则。
 *
 * 列表只展示所选设备日期内的片段：先按设备时区的半开日窗口裁剪，再用设备本地
 * 时刻显示；跨到次日零点的片段以 `24:00` 结束，零点本身不进入次日。编辑表单
 * 仍固定使用上海时区。
 */
internal object PersonalSchedulePresentation {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    fun sorted(schedules: List<PersonalSchedule>): List<PersonalSchedule> =
        schedules.sortedWith(
            compareBy<PersonalSchedule> { it.startAt }
                .thenBy { it.endAt }
                .thenBy { it.title }
                .thenBy { it.id },
        )

    fun timeLabel(
        schedule: PersonalSchedule,
        date: LocalDate,
        zone: ZoneId,
    ): String {
        val window = PersonalScheduleTime.dayWindow(date, zone)
        val clippedStart = maxOf(schedule.startAt, window.fromInclusive)
        val clippedEnd = minOf(schedule.endAt, window.untilExclusive)
        val start = clockLabel(clippedStart, zone)
        val end = if (clippedEnd == window.untilExclusive) {
            "24:00"
        } else {
            clockLabel(clippedEnd, zone)
        }
        return "$start–$end"
    }

    fun dateLabel(date: LocalDate): String =
        "${date.year}年${date.monthValue}月${date.dayOfMonth}日"

    private fun clockLabel(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime().format(timeFormat)
}
