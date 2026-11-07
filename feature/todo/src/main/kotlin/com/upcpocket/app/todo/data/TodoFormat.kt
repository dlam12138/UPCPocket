package com.upcpocket.app.todo.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 个人待办使用设备当前系统本地时区，不套用课表的上海时区。 */
object TodoFormat {

    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val date = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val full = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)

    fun shortTime(epochMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val selected = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val day = selected.toLocalDate()
        return when {
            day == today -> selected.format(time)
            day == today.plusDays(1) -> "明天 ${selected.format(time)}"
            day == today.minusDays(1) -> "昨天 ${selected.format(time)}"
            else -> "${selected.format(date)} ${selected.format(time)}"
        }
    }

    fun editorTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(full)

    fun clock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(time)

    fun date(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(date)
}
