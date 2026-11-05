package com.upcpocket.app.timetable

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.upcpocket.app.feature.timetable.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 课表组件 RemoteViews 渲染器。只接收最小今日快照和两个显式 PendingIntent，不读账号、
 * 不发网络；所有“无账号/未同步/清理中/非教学周/无课”状态都显式呈现。
 */
object TimetableWidgetRenderer {

    fun render(
        context: Context,
        snapshot: TimetableWidgetSnapshot,
        showSecondary: Boolean,
        refreshing: Boolean,
        failed: Boolean,
        openIntent: PendingIntent,
        refreshIntent: PendingIntent,
        showDate: Boolean = true,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_timetable_today)
        views.setOnClickPendingIntent(R.id.widget_timetable_root, openIntent)
        views.setOnClickPendingIntent(R.id.widget_timetable_refresh, refreshIntent)

        val today = LocalDate.now(TIMETABLE_ZONE)
        views.setViewVisibility(
            R.id.widget_timetable_date,
            if (showDate) View.VISIBLE else View.GONE,
        )
        views.setTextViewText(
            R.id.widget_timetable_date,
            "${today.monthValue}月${today.dayOfMonth}日 ${weekday(today.dayOfWeek.value)}",
        )
        views.setTextViewText(
            R.id.widget_timetable_footer,
            when {
                refreshing -> "正在刷新课表…"
                failed -> "刷新失败，点按打开检查"
                else -> context.getString(R.string.widget_timetable_open)
            },
        )
        views.setViewVisibility(R.id.widget_timetable_secondary, View.GONE)
        val primary = context.getColor(com.upcpocket.app.designsystem.R.color.widget_primary)
        val secondary = context.getColor(com.upcpocket.app.designsystem.R.color.widget_secondary)
        val accent = context.getColor(com.upcpocket.app.designsystem.R.color.widget_accent)
        val error = context.getColor(com.upcpocket.app.designsystem.R.color.widget_error)
        views.setTextColor(R.id.widget_timetable_course, primary)

        when (snapshot.status) {
            TimetableWidgetStatus.NO_ACCOUNT -> {
                views.setTextViewText(
                    R.id.widget_timetable_status,
                    context.getString(R.string.widget_timetable_no_account),
                )
                views.setTextColor(R.id.widget_timetable_status, error)
                views.setTextViewText(R.id.widget_timetable_course, "未绑定学校账号")
                views.setTextViewText(R.id.widget_timetable_meta, "")
                views.setInt(
                    R.id.widget_timetable_bar,
                    "setBackgroundColor",
                    error,
                )
                return views
            }
            TimetableWidgetStatus.ACCOUNT_LOADING -> {
                views.setTextViewText(
                    R.id.widget_timetable_status,
                    context.getString(R.string.widget_timetable_loading),
                )
                views.setTextColor(R.id.widget_timetable_status, secondary)
                views.setTextViewText(R.id.widget_timetable_course, "正在恢复账号")
                views.setTextViewText(R.id.widget_timetable_meta, "")
                return views
            }
            TimetableWidgetStatus.CLEANUP -> {
                views.setTextViewText(
                    R.id.widget_timetable_status,
                    context.getString(R.string.widget_timetable_cleanup),
                )
                views.setTextColor(R.id.widget_timetable_status, error)
                views.setTextViewText(R.id.widget_timetable_course, "账号清理中")
                views.setTextViewText(R.id.widget_timetable_meta, "")
                return views
            }
            TimetableWidgetStatus.NEEDS_SYNC -> {
                views.setTextViewText(
                    R.id.widget_timetable_status,
                    context.getString(R.string.widget_timetable_needs_sync),
                )
                views.setTextColor(R.id.widget_timetable_status, error)
                views.setTextViewText(R.id.widget_timetable_course, "课表尚未同步")
                views.setTextViewText(R.id.widget_timetable_meta, "不会把未同步当成没有课")
                views.setInt(R.id.widget_timetable_bar, "setBackgroundColor", error)
                return views
            }
            TimetableWidgetStatus.OUTSIDE_TERM -> {
                views.setTextViewText(
                    R.id.widget_timetable_status,
                    context.getString(R.string.widget_timetable_outside_term),
                )
                views.setTextColor(R.id.widget_timetable_status, secondary)
                views.setTextViewText(R.id.widget_timetable_course, "今天不在教学周")
                views.setTextViewText(R.id.widget_timetable_meta, "点按打开课表查看学期")
                return views
            }
            TimetableWidgetStatus.OK -> Unit
        }

        if (snapshot.date != today) {
            views.setTextViewText(
                R.id.widget_timetable_status,
                context.getString(R.string.widget_timetable_needs_sync),
            )
            views.setTextColor(R.id.widget_timetable_status, error)
            views.setTextViewText(R.id.widget_timetable_course, "课表不是今天")
            views.setTextViewText(R.id.widget_timetable_meta, "打开应用同步后再看")
            views.setInt(R.id.widget_timetable_bar, "setBackgroundColor", error)
            return views
        }

        if (snapshot.courses.isEmpty()) {
            views.setTextViewText(
                R.id.widget_timetable_status,
                context.getString(R.string.widget_timetable_empty),
            )
            views.setTextColor(R.id.widget_timetable_status, secondary)
            views.setTextViewText(R.id.widget_timetable_course, "今天没有排课")
            views.setTextViewText(R.id.widget_timetable_meta, "点按打开完整课表")
            views.setInt(R.id.widget_timetable_bar, "setBackgroundColor", accent)
            return views
        }

        val nowMinute = LocalTime.now(TIMETABLE_ZONE).let { it.hour * 60 + it.minute }
        val current = snapshot.courses.firstOrNull { course ->
            course.startMinute != null && course.endMinute != null &&
                nowMinute in course.startMinute until course.endMinute
        }
        val next = snapshot.courses.firstOrNull { course ->
            course.startMinute != null && course.startMinute > nowMinute
        }
        val unknownTime = snapshot.courses.firstOrNull { it.timeLabel == null }
        val main = current ?: next ?: unknownTime ?: snapshot.courses.last()
        val status = when {
            current != null -> "上课中"
            next != null -> "下一堂"
            main.timeLabel == null -> "时间待确认"
            else -> "今日已结束"
        }
        views.setTextViewText(R.id.widget_timetable_status, status)
        views.setTextColor(
            R.id.widget_timetable_status,
            if (current != null || next != null) accent else secondary,
        )
        views.setTextViewText(R.id.widget_timetable_course, main.name)
        views.setTextViewText(
            R.id.widget_timetable_meta,
            listOfNotNull(
                main.timeLabel ?: context.getString(R.string.widget_timetable_unknown),
                if (main.timeLabel == null) main.periodLabel else null,
                main.location,
            ).joinToString(" · "),
        )
        val slot = main.colorSlot ?: coursePaletteIndex(main.name)
        views.setInt(
            R.id.widget_timetable_bar,
            "setBackgroundColor",
            courseToneColors(slot, dark = false).container.toArgb(),
        )
        if (!refreshing && !failed) {
            views.setTextViewText(
                R.id.widget_timetable_footer,
                buildFooter(context, snapshot.fetchedAtEpochMillis),
            )
        }

        val secondaryCourse = when {
            current != null -> next
            next != null -> snapshot.courses.firstOrNull { it.startMinute != null && it.startMinute > (next.startMinute ?: 0) }
            else -> null
        }
        if (showSecondary && secondaryCourse != null) {
            views.setViewVisibility(R.id.widget_timetable_secondary, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_timetable_secondary_text,
                listOfNotNull(
                    secondaryCourse.timeLabel,
                    secondaryCourse.name,
                    secondaryCourse.location,
                ).joinToString(" · "),
            )
            val secondarySlot = secondaryCourse.colorSlot ?: coursePaletteIndex(secondaryCourse.name)
            views.setInt(
                R.id.widget_timetable_secondary_bar,
                "setBackgroundColor",
                courseToneColors(secondarySlot, dark = false).container.toArgb(),
            )
        }
        return views
    }

    private fun buildFooter(context: Context, fetchedAt: Long?): String {
        val open = context.getString(R.string.widget_timetable_open)
        if (fetchedAt == null) return open
        val time = java.time.Instant.ofEpochMilli(fetchedAt)
            .atZone(TIMETABLE_ZONE)
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        return "$open · 缓存 $time"
    }

    private fun weekday(iso: Int): String = when (iso) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> ""
    }
}
