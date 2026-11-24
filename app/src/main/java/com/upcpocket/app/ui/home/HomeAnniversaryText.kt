package com.upcpocket.app.ui.home

import com.upcpocket.app.data.prefs.HomeAnniversaryMode
import com.upcpocket.app.data.prefs.HomeAnniversarySettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** 首页日期下方的一行：标题单行省略，计时值优先保留。 */
internal data class HomeAnniversaryLine(
    val title: String,
    val countText: String,
)

/**
 * 按上海日历日期计算首页纪念日文案。
 *
 * [today] 必须与首页日期标签使用同一个上海日期，避免跨日时两行不一致。
 * 隐藏、缺标题、缺日期或日期不可用时返回 null，首页不保留空行。
 */
internal fun homeAnniversaryLine(
    settings: HomeAnniversarySettings,
    today: LocalDate,
): HomeAnniversaryLine? {
    if (!settings.visible) return null
    val title = settings.title.trim()
    val date = settings.date ?: return null
    if (title.isEmpty()) return null

    val days = ChronoUnit.DAYS.between(today, date)
    val countText = when (settings.mode) {
        HomeAnniversaryMode.COUNT_UP -> when {
            days > 0L -> "还有 $days 天开始"
            days == 0L -> "第 1 天"
            else -> "第 ${-days + 1} 天"
        }

        HomeAnniversaryMode.COUNT_DOWN -> when {
            days > 0L -> "还有 $days 天"
            days == 0L -> "就是今天"
            else -> "已过 ${-days} 天"
        }
    }
    return HomeAnniversaryLine(title = title, countText = countText)
}

/**
 * 日期选择器用 UTC 零点表示日历日期，与设备时区无关。
 *
 * 这是 Material DatePicker 的约定；反方向转换见 [homeAnniversaryPickerDate]。
 */
internal fun homeAnniversaryPickerMillis(date: LocalDate?): Long? =
    date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

/** 把 DatePicker 的 UTC 毫秒解析为日历日期，不经过设备时区。 */
internal fun homeAnniversaryPickerDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
