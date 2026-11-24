package com.upcpocket.app.todo.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 个人日程的时间口径与窗口换算。
 *
 * 日程录入固定使用上海时区；显示窗口按调用方给出的设备时区计算，二者不混用。
 */
object PersonalScheduleTime {

    val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 半开时间窗口 `[fromInclusive, untilExclusive)`。 */
    data class DayWindow(
        val fromInclusive: Long,
        val untilExclusive: Long,
    )

    /**
     * 将本地时间解析为确定时点。
     *
     * 只有恰好一个有效偏移时才返回毫秒值；时区规则造成本地时间跳过（无偏移）或
     * 重复（两个偏移）时返回 null，由表单提示改选，不能静默选择其中一个。
     */
    fun resolve(dateTime: LocalDateTime, zone: ZoneId = SHANGHAI): Long? {
        val offsets = zone.rules.getValidOffsets(dateTime)
        if (offsets.size != 1) return null
        return dateTime.toInstant(offsets.single()).toEpochMilli()
    }

    fun shanghaiDateTime(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(SHANGHAI).toLocalDateTime()

    fun shanghaiDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(SHANGHAI).toLocalDate()

    /** 设备本地某个日期的半开窗口；零点结束属于前一日。 */
    fun dayWindow(date: LocalDate, zone: ZoneId): DayWindow = DayWindow(
        fromInclusive = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        untilExclusive = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}
