package com.upcpocket.app.timetable

import android.app.PendingIntent
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.designsystem.WidgetRenderSupport
import com.upcpocket.app.feature.timetable.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 课表组件 RemoteViews 渲染器。只接收最小今日快照和两个显式 PendingIntent，不读账号、
 * 不发网络；所有“无账号/未同步/清理中/非教学周/无课”状态都显式呈现。
 *
 * 空间顺序固定为：页头 → 日期/状态 → 主课（课程名最多两行、时间、地点）→ 次课 →
 * 更新时间。主课不在 weighted 容器里，空间再紧也不会先把课程名压没；短矮网格由
 * [TimetableWidgetLayout.dense] 先删日期/页脚，而不是删主课。
 */
object TimetableWidgetRenderer {

    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    fun render(
        context: Context,
        snapshot: TimetableWidgetSnapshot,
        layout: TimetableWidgetLayout = TimetableWidgetLayout(),
        refreshing: Boolean,
        failed: Boolean,
        openIntent: PendingIntent,
        refreshIntent: PendingIntent,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_timetable_today)
        views.setOnClickPendingIntent(R.id.widget_timetable_root, openIntent)
        views.setOnClickPendingIntent(R.id.widget_timetable_refresh, refreshIntent)

        val primary = context.getColor(DesignSystemR.color.widget_primary)
        val secondary = context.getColor(DesignSystemR.color.widget_secondary)
        val tertiary = context.getColor(DesignSystemR.color.widget_tertiary)
        val accent = context.getColor(DesignSystemR.color.widget_accent)
        val error = context.getColor(DesignSystemR.color.widget_error)

        views.setTextViewTextSize(
            R.id.widget_timetable_course,
            TypedValue.COMPLEX_UNIT_SP,
            if (layout.dense || layout.compact) 16f else 17f,
        )
        val metaSize = if (layout.dense || layout.compact) 12f else 12.5f
        views.setTextViewTextSize(
            R.id.widget_timetable_time,
            TypedValue.COMPLEX_UNIT_SP,
            metaSize,
        )
        views.setTextViewTextSize(
            R.id.widget_timetable_location,
            TypedValue.COMPLEX_UNIT_SP,
            metaSize,
        )
        val padding = dp(
            context,
            when {
                layout.dense -> 10
                layout.compact -> 9
                else -> 14
            },
        )
        views.setViewPadding(
            R.id.widget_timetable_content,
            padding,
            padding,
            padding,
            padding,
        )

        // 每次渲染先回到“只有页头 + 主课”的最小集合；状态分支再显式打开需要的行。
        views.setViewVisibility(R.id.widget_timetable_meta_row, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_date, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_status, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_secondary, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_footer, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_bar, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_time, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_location, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_main, View.VISIBLE)
        views.setTextColor(R.id.widget_timetable_course, primary)
        views.setTextColor(R.id.widget_timetable_time, secondary)
        views.setTextColor(R.id.widget_timetable_location, secondary)
        views.setTextColor(R.id.widget_timetable_footer, tertiary)
        views.setFloat(
            R.id.widget_timetable_refresh,
            "setAlpha",
            if (refreshing) 0.5f else 1f,
        )

        val today = LocalDate.now(ZONE)
        val dateLabel = formatDate(today)

        when (snapshot.status) {
            TimetableWidgetStatus.NO_ACCOUNT -> {
                showMeta(
                    context, views, layout, dateLabel,
                    R.string.widget_timetable_status_no_account, error,
                )
                showState(
                    views = views,
                    headline = context.getString(R.string.widget_timetable_no_account),
                    hint = context.getString(R.string.widget_timetable_no_account_hint),
                    hintColor = secondary,
                    footer = null,
                    footerColor = tertiary,
                    primary = primary,
                    layout = layout,
                )
                return views
            }

            TimetableWidgetStatus.ACCOUNT_LOADING -> {
                showMeta(
                    context, views, layout, dateLabel,
                    R.string.widget_timetable_status_loading, secondary,
                )
                showState(
                    views = views,
                    headline = context.getString(R.string.widget_timetable_loading),
                    hint = context.getString(R.string.widget_timetable_loading_hint),
                    hintColor = secondary,
                    footer = null,
                    footerColor = tertiary,
                    primary = primary,
                    layout = layout,
                )
                return views
            }

            TimetableWidgetStatus.CLEANUP -> {
                showMeta(
                    context, views, layout, dateLabel,
                    R.string.widget_timetable_status_cleanup, error,
                )
                showState(
                    views = views,
                    headline = context.getString(R.string.widget_timetable_cleanup),
                    hint = context.getString(R.string.widget_timetable_cleanup_hint),
                    hintColor = secondary,
                    footer = null,
                    footerColor = tertiary,
                    primary = primary,
                    layout = layout,
                )
                return views
            }

            TimetableWidgetStatus.NEEDS_SYNC -> {
                showMeta(
                    context, views, layout, dateLabel,
                    R.string.widget_timetable_status_error, error,
                )
                showState(
                    views = views,
                    headline = context.getString(R.string.widget_timetable_needs_sync),
                    hint = context.getString(R.string.widget_timetable_needs_sync_hint),
                    hintColor = secondary,
                    footer = null,
                    footerColor = tertiary,
                    primary = primary,
                    layout = layout,
                )
                return views
            }

            TimetableWidgetStatus.OUTSIDE_TERM -> {
                showMeta(
                    context, views, layout, dateLabel,
                    R.string.widget_timetable_status_outside_term, secondary,
                )
                showState(
                    views = views,
                    headline = context.getString(R.string.widget_timetable_outside_term),
                    hint = context.getString(R.string.widget_timetable_outside_term_hint),
                    hintColor = secondary,
                    footer = null,
                    footerColor = tertiary,
                    primary = primary,
                    layout = layout,
                )
                return views
            }

            TimetableWidgetStatus.OK -> Unit
        }

        if (snapshot.date != today) {
            showMeta(
                context, views, layout, dateLabel,
                R.string.widget_timetable_status_stale, error,
            )
            showState(
                views = views,
                headline = context.getString(R.string.widget_timetable_stale),
                hint = context.getString(R.string.widget_timetable_stale_hint),
                hintColor = secondary,
                footer = null,
                footerColor = tertiary,
                primary = primary,
                layout = layout,
            )
            return views
        }

        if (snapshot.courses.isEmpty()) {
            showMeta(
                context, views, layout, dateLabel,
                R.string.widget_timetable_status_empty, secondary,
            )
            showState(
                views = views,
                headline = context.getString(R.string.widget_timetable_empty),
                hint = context.getString(R.string.widget_timetable_empty_hint),
                hintColor = secondary,
                footer = footerText(context, snapshot, layout, refreshing, failed),
                footerColor = footerColor(context, refreshing, failed),
                primary = primary,
                layout = layout,
            )
            return views
        }

        val nowMinute = LocalTime.now(ZONE).let { it.hour * 60 + it.minute }
        val current = snapshot.courses.firstOrNull { course ->
            course.startMinute != null && course.endMinute != null &&
                nowMinute in course.startMinute until course.endMinute
        }
        val next = snapshot.courses.firstOrNull { course ->
            course.startMinute != null && course.startMinute > nowMinute
        }
        val unknownTime = snapshot.courses.firstOrNull { it.timeLabel == null }
        val main = current ?: next ?: unknownTime ?: snapshot.courses.last()
        val statusText = when {
            current != null -> R.string.widget_timetable_status_current
            next != null -> R.string.widget_timetable_status_next
            main.timeLabel == null -> R.string.widget_timetable_status_unknown
            else -> R.string.widget_timetable_status_ended
        }
        val statusColor = if (current != null || next != null) accent else secondary

        views.setTextViewText(R.id.widget_timetable_course, main.name)
        views.setTextViewText(
            R.id.widget_timetable_time,
            main.timeLabel ?: main.periodLabel,
        )
        views.setViewVisibility(R.id.widget_timetable_time, View.VISIBLE)
        val location = main.location?.trim().orEmpty()
        if (layout.showLocation && location.isNotEmpty()) {
            views.setTextViewText(R.id.widget_timetable_location, location)
            views.setViewVisibility(R.id.widget_timetable_location, View.VISIBLE)
        }

        val slot = main.colorSlot ?: coursePaletteIndex(main.name)
        views.setInt(
            R.id.widget_timetable_bar,
            "setBackgroundColor",
            courseToneColors(slot, dark = false).container.toArgb(),
        )
        // 大字号下日期/状态行更宽，色条先让位，避免把日期挤成省略号。
        views.setViewVisibility(
            R.id.widget_timetable_bar,
            if (layout.dense) View.GONE else View.VISIBLE,
        )

        showMeta(context, views, layout, dateLabel, statusText, statusColor)
        val footer = if (layout.showFooter) {
            footerText(context, snapshot, layout, refreshing, failed)
        } else {
            null
        }
        if (footer != null) {
            views.setTextViewText(R.id.widget_timetable_footer, footer)
            views.setTextColor(
                R.id.widget_timetable_footer,
                footerColor(context, refreshing, failed),
            )
            views.setViewVisibility(R.id.widget_timetable_footer, View.VISIBLE)
        } else if (failed) {
            // 短矮网格没有页脚：把失败放在地点行，主课名与时间仍完整保留。
            views.setTextViewText(
                R.id.widget_timetable_location,
                context.getString(R.string.widget_timetable_failed_compact),
            )
            views.setTextColor(R.id.widget_timetable_location, error)
            views.setViewVisibility(R.id.widget_timetable_location, View.VISIBLE)
        }

        val secondaryCourse = when {
            current != null -> next
            next != null -> snapshot.courses.firstOrNull { course ->
                course.startMinute != null && course.startMinute > (next.startMinute ?: 0)
            }
            else -> null
        }
        if (layout.showSecondary && secondaryCourse != null) {
            views.setViewVisibility(R.id.widget_timetable_secondary, View.VISIBLE)
            // 2×2 只有一行：用开始时间 + 课程名，给名称多留几个字；4×2 才展示完整起止。
            val secondaryTime = if (layout.compact) {
                secondaryCourse.startMinute?.let(::clock) ?: secondaryCourse.periodLabel
            } else {
                secondaryCourse.timeLabel ?: secondaryCourse.periodLabel
            }
            views.setTextViewText(
                R.id.widget_timetable_secondary_text,
                listOf(secondaryTime, secondaryCourse.name).joinToString(" · "),
            )
            val secondarySlot = secondaryCourse.colorSlot
                ?: coursePaletteIndex(secondaryCourse.name)
            views.setInt(
                R.id.widget_timetable_secondary_bar,
                "setBackgroundColor",
                courseToneColors(secondarySlot, dark = false).container.toArgb(),
            )
        }
        return views
    }

    private fun showState(
        views: RemoteViews,
        headline: String,
        hint: String,
        hintColor: Int,
        footer: String?,
        footerColor: Int,
        primary: Int,
        layout: TimetableWidgetLayout,
    ) {
        views.setTextViewText(R.id.widget_timetable_course, headline)
        views.setTextColor(R.id.widget_timetable_course, primary)
        if (hint.isNotBlank()) {
            views.setTextViewText(R.id.widget_timetable_time, hint)
            views.setTextColor(R.id.widget_timetable_time, hintColor)
            views.setViewVisibility(R.id.widget_timetable_time, View.VISIBLE)
        }
        if (footer != null && layout.showFooter) {
            views.setTextViewText(R.id.widget_timetable_footer, footer)
            views.setTextColor(R.id.widget_timetable_footer, footerColor)
            views.setViewVisibility(R.id.widget_timetable_footer, View.VISIBLE)
        }
    }

    private fun showMeta(
        context: Context,
        views: RemoteViews,
        layout: TimetableWidgetLayout,
        dateLabel: String,
        statusRes: Int,
        statusColor: Int,
    ) {
        if (!layout.showDate) return
        views.setViewVisibility(R.id.widget_timetable_meta_row, View.VISIBLE)
        views.setTextViewText(R.id.widget_timetable_date, dateLabel)
        views.setViewVisibility(R.id.widget_timetable_date, View.VISIBLE)
        views.setTextViewText(R.id.widget_timetable_status, context.getString(statusRes))
        views.setTextColor(R.id.widget_timetable_status, statusColor)
        views.setViewVisibility(R.id.widget_timetable_status, View.VISIBLE)
    }

    private fun footerText(
        context: Context,
        snapshot: TimetableWidgetSnapshot,
        layout: TimetableWidgetLayout,
        refreshing: Boolean,
        failed: Boolean,
    ): String? {
        if (!layout.showFooter) return null
        val fetchedAt = snapshot.fetchedAtEpochMillis
        return when {
            refreshing -> context.getString(DesignSystemR.string.widget_refreshing)
            failed && fetchedAt == null ->
                context.getString(DesignSystemR.string.widget_refresh_failed)
            fetchedAt == null -> null
            else -> WidgetRenderSupport.metaText(
                context = context,
                fetchedAt = fetchedAt,
                refreshing = false,
                failed = failed,
                zone = ZONE,
            )
        }
    }

    private fun footerColor(context: Context, refreshing: Boolean, failed: Boolean): Int =
        context.getColor(WidgetRenderSupport.metaColor(refreshing, failed))

    private fun formatDate(date: LocalDate): String =
        "${date.monthValue}月${date.dayOfMonth}日 ${weekday(date.dayOfWeek.value)}"

    private fun clock(minute: Int): String =
        "%02d:%02d".format((minute / 60) % 24, minute % 60)

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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
