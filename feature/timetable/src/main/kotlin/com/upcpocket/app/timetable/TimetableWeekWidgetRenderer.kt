package com.upcpocket.app.timetable

import android.app.PendingIntent
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.designsystem.WidgetRenderSupport
import com.upcpocket.app.feature.timetable.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「本周课表」RemoteViews 渲染器。
 *
 * 只接收七日日期/课程名/节次等渲染必要字段和显式导航 PendingIntent；不读账号、
 * 不联网、不写缓存。周日至周六固定七行，今日有强调条；部分缺日、冲突、学期外、
 * 读取失败、无账号、恢复与清理分别呈现。任何状态都不把缺数据伪装成“全周无课”。
 */
object TimetableWeekWidgetRenderer {

    private val ZONE: ZoneId = TIMETABLE_ZONE

    private val COURSE_IDS = intArrayOf(
        R.id.widget_timetable_week_day_course_1,
        R.id.widget_timetable_week_day_course_2,
        R.id.widget_timetable_week_day_course_3,
    )

    fun render(
        context: Context,
        snapshot: TimetableWeekWidgetSnapshot,
        layout: TimetableWeekWidgetLayout = TimetableWeekWidgetLayout(),
        openIntent: PendingIntent,
        appearance: WidgetAppearance = WidgetAppearance.SOLID,
        now: ZonedDateTime = ZonedDateTime.now(ZONE),
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_timetable_week)
        WidgetRenderSupport.applyAppearance(views, R.id.widget_timetable_week_root, appearance)
        views.setOnClickPendingIntent(R.id.widget_timetable_week_root, openIntent)

        val primary = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_primary))
        val secondary = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_secondary))
        val tertiary = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_tertiary))
        val accent = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_accent))
        val error = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_error))

        views.setViewVisibility(
            R.id.widget_timetable_week_header,
            if (layout.showTitle) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(
            R.id.widget_timetable_week_range,
            if (layout.showTitle && layout.showRange) View.VISIBLE else View.GONE,
        )
        views.setTextViewTextSize(
            R.id.widget_timetable_week_title,
            TypedValue.COMPLEX_UNIT_SP,
            if (layout.compact) 12f else 13f,
        )
        views.setTextColor(R.id.widget_timetable_week_title, primary)
        views.setTextViewText(R.id.widget_timetable_week_title, context.getString(R.string.widget_timetable_week_title))
        views.setTextViewText(R.id.widget_timetable_week_range, formatRange(snapshot.weekStart))
        views.setTextColor(R.id.widget_timetable_week_range, secondary)

        views.setViewVisibility(R.id.widget_timetable_week_days, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_week_state, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_week_footer, View.GONE)
        views.setTextColor(R.id.widget_timetable_week_footer, tertiary)
        views.setViewVisibility(R.id.widget_timetable_week_spacer, View.VISIBLE)

        if (snapshot.days.size != 7 ||
            snapshot.status == TimetableWeekWidgetStatus.NEEDS_SYNC ||
            snapshot.status == TimetableWeekWidgetStatus.CONFLICT ||
            snapshot.status == TimetableWeekWidgetStatus.READ_ERROR ||
            snapshot.status == TimetableWeekWidgetStatus.OUTSIDE_TERM ||
            snapshot.status == TimetableWeekWidgetStatus.NO_ACCOUNT ||
            snapshot.status == TimetableWeekWidgetStatus.ACCOUNT_LOADING ||
            snapshot.status == TimetableWeekWidgetStatus.CLEANUP ||
            snapshot.status == TimetableWeekWidgetStatus.READING
        ) {
            // 不只是隐藏：清空天数容器，避免系统重放 RemoteViews 时残留上次课程子树。
            views.removeAllViews(R.id.widget_timetable_week_days)
            showState(context, views, snapshot, secondary, error)
            return views
        }

        val today = now.withZoneSameInstant(ZONE).toLocalDate()
        var hasOverflow = false
        var hasTruncated = false
        val dayViews = snapshot.days.map { day ->
            val dayView = RemoteViews(context.packageName, R.layout.widget_timetable_week_day)
            val isToday = day.date == today
            dayView.setViewVisibility(
                R.id.widget_timetable_week_today_bar,
                if (isToday) View.VISIBLE else View.GONE,
            )
            dayView.setTextViewText(
                R.id.widget_timetable_week_day_label,
                formatDay(day.date, compact = layout.compactDayLabel),
            )
            dayView.setTextColor(
                R.id.widget_timetable_week_day_label,
                if (isToday) accent else secondary,
            )
            dayView.setTextViewTextSize(
                R.id.widget_timetable_week_day_label,
                TypedValue.COMPLEX_UNIT_SP,
                10f * layout.textScale,
            )
            // 额外课程数独立展示；大字号下 TextView 的二次省略只会影响课程名。
            dayView.setViewVisibility(R.id.widget_timetable_week_day_more, View.GONE)
            dayView.setTextColor(R.id.widget_timetable_week_day_more, secondary)
            dayView.setTextViewTextSize(
                R.id.widget_timetable_week_day_more,
                TypedValue.COMPLEX_UNIT_SP,
                (if (layout.dense) 9.5f else 10f) * layout.textScale,
            )
            COURSE_IDS.forEach { id ->
                dayView.setViewVisibility(id, View.GONE)
                dayView.setTextColor(id, primary)
                dayView.setTextViewTextSize(
                    id,
                    TypedValue.COMPLEX_UNIT_SP,
                    (if (layout.dense) 10.5f else 11f) * layout.textScale,
                )
            }
            when (day.state) {
                TimetableWeekWidgetDayState.COURSES -> {
                    val lines = weekCourseLines(
                        courses = day.courses,
                        maxCourses = layout.maxCoursesPerDay,
                        maxChars = layout.maxCourseChars,
                        compactPeriod = layout.compactPeriod,
                    )
                    hasOverflow = hasOverflow || lines.hiddenCount > 0
                    hasTruncated = hasTruncated || lines.truncated
                    lines.lines.forEachIndexed { index, line ->
                        val id = COURSE_IDS[index]
                        dayView.setTextViewText(id, line)
                        dayView.setViewVisibility(id, View.VISIBLE)
                    }
                    if (lines.hiddenCount > 0) {
                        dayView.setTextViewText(
                            R.id.widget_timetable_week_day_more,
                            "+${lines.hiddenCount}",
                        )
                        dayView.setViewVisibility(
                            R.id.widget_timetable_week_day_more,
                            View.VISIBLE,
                        )
                    }
                }
                TimetableWeekWidgetDayState.EMPTY -> {
                    dayView.setTextViewText(
                        COURSE_IDS[0],
                        context.getString(R.string.widget_timetable_week_day_empty),
                    )
                    dayView.setTextColor(COURSE_IDS[0], secondary)
                    dayView.setViewVisibility(COURSE_IDS[0], View.VISIBLE)
                }
                TimetableWeekWidgetDayState.UNSYNCED -> {
                    dayView.setTextViewText(
                        COURSE_IDS[0],
                        context.getString(R.string.widget_timetable_week_day_unsynced),
                    )
                    dayView.setTextColor(COURSE_IDS[0], secondary)
                    dayView.setViewVisibility(COURSE_IDS[0], View.VISIBLE)
                }
                TimetableWeekWidgetDayState.OUTSIDE_TERM -> {
                    dayView.setTextViewText(
                        COURSE_IDS[0],
                        context.getString(R.string.widget_timetable_week_day_outside_term),
                    )
                    dayView.setTextColor(COURSE_IDS[0], tertiary)
                    dayView.setViewVisibility(COURSE_IDS[0], View.VISIBLE)
                }
                TimetableWeekWidgetDayState.CONFLICT -> {
                    dayView.setTextViewText(
                        COURSE_IDS[0],
                        context.getString(R.string.widget_timetable_week_day_conflict),
                    )
                    dayView.setTextColor(COURSE_IDS[0], error)
                    dayView.setViewVisibility(COURSE_IDS[0], View.VISIBLE)
                }
            }
            dayView
        }
        views.removeAllViews(R.id.widget_timetable_week_days)
        dayViews.forEach { dayView ->
            views.addView(R.id.widget_timetable_week_days, dayView)
        }
        views.setViewVisibility(R.id.widget_timetable_week_days, View.VISIBLE)

        val footer = footerText(
            context = context,
            snapshot = snapshot,
            layout = layout,
            hasOverflow = hasOverflow,
            hasTruncated = hasTruncated,
        )
        if (footer != null) {
            views.setTextViewText(R.id.widget_timetable_week_footer, footer)
            views.setTextColor(
                R.id.widget_timetable_week_footer,
                if (footerIsError(snapshot)) error else tertiary,
            )
            views.setViewVisibility(R.id.widget_timetable_week_footer, View.VISIBLE)
        }
        return views
    }

    private fun showState(
        context: Context,
        views: RemoteViews,
        snapshot: TimetableWeekWidgetSnapshot,
        secondary: Int,
        error: Int,
    ) {
        val (titleRes, hintRes, isError) = when (snapshot.status) {
            TimetableWeekWidgetStatus.NO_ACCOUNT -> Triple(
                R.string.widget_timetable_week_state_no_account,
                R.string.widget_timetable_week_state_no_account_hint,
                true,
            )
            TimetableWeekWidgetStatus.ACCOUNT_LOADING -> Triple(
                R.string.widget_timetable_week_state_loading,
                R.string.widget_timetable_week_state_loading_hint,
                false,
            )
            TimetableWeekWidgetStatus.CLEANUP -> Triple(
                R.string.widget_timetable_week_state_cleanup,
                R.string.widget_timetable_week_state_cleanup_hint,
                true,
            )
            TimetableWeekWidgetStatus.READING -> Triple(
                R.string.widget_timetable_week_state_reading,
                R.string.widget_timetable_week_state_reading_hint,
                false,
            )
            TimetableWeekWidgetStatus.CONFLICT -> Triple(
                R.string.widget_timetable_week_state_conflict,
                R.string.widget_timetable_week_state_conflict_hint,
                true,
            )
            TimetableWeekWidgetStatus.READ_ERROR -> Triple(
                R.string.widget_timetable_week_state_read_error,
                R.string.widget_timetable_week_state_read_error_hint,
                true,
            )
            TimetableWeekWidgetStatus.OUTSIDE_TERM -> Triple(
                R.string.widget_timetable_week_state_outside_term,
                R.string.widget_timetable_week_state_outside_term_hint,
                false,
            )
            // 数据状态由 render 的七日分支处理；防御性回退到“未同步”，不伪造空周。
            TimetableWeekWidgetStatus.OK,
            TimetableWeekWidgetStatus.EMPTY,
            TimetableWeekWidgetStatus.PARTIAL,
            TimetableWeekWidgetStatus.NEEDS_SYNC,
            -> Triple(
                R.string.widget_timetable_week_state_needs_sync,
                R.string.widget_timetable_week_state_needs_sync_hint,
                true,
            )
        }
        views.setTextViewText(R.id.widget_timetable_week_state_title, context.getString(titleRes))
        views.setTextColor(R.id.widget_timetable_week_state_title, if (isError) error else secondary)
        views.setTextViewText(R.id.widget_timetable_week_state_hint, context.getString(hintRes))
        views.setTextColor(R.id.widget_timetable_week_state_hint, secondary)
        views.setViewVisibility(R.id.widget_timetable_week_state, View.VISIBLE)
        views.setViewVisibility(R.id.widget_timetable_week_spacer, View.GONE)
    }

    private fun footerText(
        context: Context,
        snapshot: TimetableWeekWidgetSnapshot,
        layout: TimetableWeekWidgetLayout,
        hasOverflow: Boolean,
        hasTruncated: Boolean,
    ): String? {
        if (!layout.showFooter) return null
        return when (snapshot.status) {
            TimetableWeekWidgetStatus.EMPTY ->
                context.getString(R.string.widget_timetable_week_empty_footer)
            TimetableWeekWidgetStatus.PARTIAL -> when {
                snapshot.days.any { it.state == TimetableWeekWidgetDayState.CONFLICT } ->
                    context.getString(R.string.widget_timetable_week_conflict_footer)
                snapshot.days.any { it.state == TimetableWeekWidgetDayState.UNSYNCED } ->
                    context.getString(R.string.widget_timetable_week_partial_footer)
                // 覆盖到学期外的部分周没有待同步日期，由日行“学期外”和整卡查看表达。
                else -> null
            }
            TimetableWeekWidgetStatus.OK -> when {
                hasOverflow || hasTruncated ->
                    context.getString(R.string.widget_timetable_week_open_footer)
                snapshot.fetchedAtEpochMillis != null -> WidgetRenderSupport.metaText(
                    context = context,
                    fetchedAt = snapshot.fetchedAtEpochMillis,
                    refreshing = false,
                    failed = false,
                    zone = ZONE,
                )
                else -> context.getString(R.string.widget_timetable_week_open_footer)
            }
            else -> null
        }
    }

    private fun footerIsError(snapshot: TimetableWeekWidgetSnapshot): Boolean =
        snapshot.status == TimetableWeekWidgetStatus.PARTIAL &&
            snapshot.days.any { it.state == TimetableWeekWidgetDayState.CONFLICT }

    private fun formatRange(start: LocalDate): String {
        val end = start.plusDays(6)
        return "${start.monthValue}月${start.dayOfMonth}日 – " +
            "${end.monthValue}月${end.dayOfMonth}日"
    }

    private fun formatDay(date: LocalDate, compact: Boolean): String =
        if (compact) {
            "${weekdayShort(date.dayOfWeek.value)} ${date.dayOfMonth}"
        } else {
            "${weekday(date.dayOfWeek.value)} ${date.dayOfMonth}"
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

    private fun weekdayShort(iso: Int): String = when (iso) {
        1 -> "一"
        2 -> "二"
        3 -> "三"
        4 -> "四"
        5 -> "五"
        6 -> "六"
        7 -> "日"
        else -> ""
    }
}
