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

/**
 * 「今明课表」RemoteViews 渲染器。
 *
 * 只接收两天日期/课程名/节次与显式导航 PendingIntent；不读账号、不联网、不写缓存。
 * 两列始终同时保留：窄卡/大字号只压缩日期格式、课程行数和行内名称，节次、两日
 * 各自的状态与独立“+N”不合并。缓存整体不可读时保留两天日期并分别标注读取失败。
 */
object TimetableTodayTomorrowWidgetRenderer {

    private val DATE_IDS = intArrayOf(
        R.id.widget_timetable_tt_today_date,
        R.id.widget_timetable_tt_tomorrow_date,
    )

    private val MORE_IDS = intArrayOf(
        R.id.widget_timetable_tt_today_more,
        R.id.widget_timetable_tt_tomorrow_more,
    )

    private val COURSE_IDS = arrayOf(
        intArrayOf(
            R.id.widget_timetable_tt_today_course_1,
            R.id.widget_timetable_tt_today_course_2,
            R.id.widget_timetable_tt_today_course_3,
            R.id.widget_timetable_tt_today_course_4,
            R.id.widget_timetable_tt_today_course_5,
            R.id.widget_timetable_tt_today_course_6,
            R.id.widget_timetable_tt_today_course_7,
            R.id.widget_timetable_tt_today_course_8,
        ),
        intArrayOf(
            R.id.widget_timetable_tt_tomorrow_course_1,
            R.id.widget_timetable_tt_tomorrow_course_2,
            R.id.widget_timetable_tt_tomorrow_course_3,
            R.id.widget_timetable_tt_tomorrow_course_4,
            R.id.widget_timetable_tt_tomorrow_course_5,
            R.id.widget_timetable_tt_tomorrow_course_6,
            R.id.widget_timetable_tt_tomorrow_course_7,
            R.id.widget_timetable_tt_tomorrow_course_8,
        ),
    )

    fun render(
        context: Context,
        snapshot: TimetableTodayTomorrowWidgetSnapshot,
        layout: TimetableTodayTomorrowWidgetLayout = TimetableTodayTomorrowWidgetLayout(),
        openIntent: PendingIntent,
        appearance: WidgetAppearance = WidgetAppearance.SOLID,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_timetable_today_tomorrow)
        WidgetRenderSupport.applyAppearance(views, R.id.widget_timetable_tt_root, appearance)
        views.setOnClickPendingIntent(R.id.widget_timetable_tt_root, openIntent)

        val primary = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_primary))
        val secondary = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_secondary))
        val tertiary = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_tertiary))
        val accent = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_accent))
        val error = context.getColor(appearance.textColorResource(DesignSystemR.color.widget_error))

        val hasDays = snapshot.days.size == 2
        val mainStatus = snapshot.status == TimetableTodayTomorrowWidgetStatus.OK ||
            snapshot.status == TimetableTodayTomorrowWidgetStatus.READ_ERROR
        if (!hasDays || !mainStatus) {
            views.setViewVisibility(R.id.widget_timetable_tt_content, View.GONE)
            showState(context, views, snapshot, secondary, error)
            return views
        }

        views.setViewVisibility(R.id.widget_timetable_tt_state, View.GONE)
        views.setViewVisibility(R.id.widget_timetable_tt_content, View.VISIBLE)

        snapshot.days.forEachIndexed { index, day ->
            val dateId = DATE_IDS[index]
            val moreId = MORE_IDS[index]
            val courseIds = COURSE_IDS[index]
            views.setTextViewText(
                dateId,
                formatDate(context, day.date, snapshot.today, compact = layout.compactDate),
            )
            views.setTextColor(dateId, if (index == 0) accent else secondary)
            views.setTextViewTextSize(
                dateId,
                TypedValue.COMPLEX_UNIT_SP,
                (if (layout.compactDate) 10.5f else 11.5f) * layout.textScale,
            )
            views.setViewVisibility(moreId, View.GONE)
            views.setTextViewText(moreId, "")
            views.setTextColor(moreId, secondary)
            views.setTextViewTextSize(
                moreId,
                TypedValue.COMPLEX_UNIT_SP,
                9.5f * layout.textScale,
            )
            courseIds.forEach { id ->
                views.setViewVisibility(id, View.GONE)
                views.setTextViewText(id, "")
                views.setTextColor(id, primary)
                views.setTextViewTextSize(
                    id,
                    TypedValue.COMPLEX_UNIT_SP,
                    (if (layout.dense) 10f else 10.5f) * layout.textScale,
                )
            }
            when (day.state) {
                TimetableTodayTomorrowWidgetDayState.COURSES -> {
                    val lines = todayTomorrowCourseLines(
                        courses = day.courses,
                        maxCourses = layout.maxCoursesPerDay,
                        maxChars = layout.maxCourseChars,
                        compactPeriod = layout.compactPeriod,
                    )
                    lines.lines.forEachIndexed { lineIndex, line ->
                        val id = courseIds[lineIndex]
                        views.setTextViewText(id, line)
                        views.setViewVisibility(id, View.VISIBLE)
                    }
                    if (lines.hiddenCount > 0) {
                        views.setTextViewText(moreId, "+${lines.hiddenCount}")
                        views.setViewVisibility(moreId, View.VISIBLE)
                    }
                }
                TimetableTodayTomorrowWidgetDayState.EMPTY -> {
                    views.setTextViewText(
                        courseIds[0],
                        context.getString(R.string.widget_timetable_tt_day_empty),
                    )
                    views.setTextColor(courseIds[0], secondary)
                    views.setViewVisibility(courseIds[0], View.VISIBLE)
                }
                TimetableTodayTomorrowWidgetDayState.UNSYNCED -> {
                    views.setTextViewText(
                        courseIds[0],
                        context.getString(R.string.widget_timetable_tt_day_unsynced),
                    )
                    views.setTextColor(courseIds[0], secondary)
                    views.setViewVisibility(courseIds[0], View.VISIBLE)
                }
                TimetableTodayTomorrowWidgetDayState.OUTSIDE_TERM -> {
                    views.setTextViewText(
                        courseIds[0],
                        context.getString(R.string.widget_timetable_tt_day_outside_term),
                    )
                    views.setTextColor(courseIds[0], tertiary)
                    views.setViewVisibility(courseIds[0], View.VISIBLE)
                }
                TimetableTodayTomorrowWidgetDayState.CONFLICT -> {
                    views.setTextViewText(
                        courseIds[0],
                        context.getString(R.string.widget_timetable_tt_day_conflict),
                    )
                    views.setTextColor(courseIds[0], error)
                    views.setViewVisibility(courseIds[0], View.VISIBLE)
                }
                TimetableTodayTomorrowWidgetDayState.READ_ERROR -> {
                    views.setTextViewText(
                        courseIds[0],
                        context.getString(R.string.widget_timetable_tt_day_read_error),
                    )
                    views.setTextColor(courseIds[0], error)
                    views.setViewVisibility(courseIds[0], View.VISIBLE)
                }
            }
        }
        return views
    }

    private fun showState(
        context: Context,
        views: RemoteViews,
        snapshot: TimetableTodayTomorrowWidgetSnapshot,
        secondary: Int,
        error: Int,
    ) {
        val (titleRes, hintRes, isError) = when (snapshot.status) {
            TimetableTodayTomorrowWidgetStatus.NO_ACCOUNT -> Triple(
                R.string.widget_timetable_tt_state_no_account,
                R.string.widget_timetable_tt_state_no_account_hint,
                true,
            )
            TimetableTodayTomorrowWidgetStatus.ACCOUNT_LOADING -> Triple(
                R.string.widget_timetable_tt_state_loading,
                R.string.widget_timetable_tt_state_loading_hint,
                false,
            )
            TimetableTodayTomorrowWidgetStatus.CLEANUP -> Triple(
                R.string.widget_timetable_tt_state_cleanup,
                R.string.widget_timetable_tt_state_cleanup_hint,
                true,
            )
            TimetableTodayTomorrowWidgetStatus.READING -> Triple(
                R.string.widget_timetable_tt_state_reading,
                R.string.widget_timetable_tt_state_reading_hint,
                false,
            )
            TimetableTodayTomorrowWidgetStatus.UNAVAILABLE -> Triple(
                R.string.widget_timetable_tt_state_unavailable,
                R.string.widget_timetable_tt_state_unavailable_hint,
                true,
            )
            // 数据状态由 render 的两日分支处理；防御性回退为读取失败，不伪造无课。
            TimetableTodayTomorrowWidgetStatus.OK,
            TimetableTodayTomorrowWidgetStatus.READ_ERROR,
            -> Triple(
                R.string.widget_timetable_tt_state_read_error,
                R.string.widget_timetable_tt_state_read_error_hint,
                true,
            )
        }
        views.setTextViewText(R.id.widget_timetable_tt_state_title, context.getString(titleRes))
        views.setTextColor(R.id.widget_timetable_tt_state_title, if (isError) error else secondary)
        views.setTextViewText(R.id.widget_timetable_tt_state_hint, context.getString(hintRes))
        views.setTextColor(R.id.widget_timetable_tt_state_hint, secondary)
        views.setViewVisibility(R.id.widget_timetable_tt_state, View.VISIBLE)
    }

    /**
     * 常规显示“今天 11月24日 / 明天 12月1日”；窄卡或大字号只缩短日期本身，
     * 跨月仍保留月份，不能只留一个无法判断月份的日号。
     */
    private fun formatDate(
        context: Context,
        date: LocalDate,
        today: LocalDate,
        compact: Boolean,
    ): String {
        val prefix = when (date) {
            today -> context.getString(R.string.widget_timetable_tt_today)
            today.plusDays(1) -> context.getString(R.string.widget_timetable_tt_tomorrow)
            else -> null
        }
        val body = when {
            !compact -> "${date.monthValue}月${date.dayOfMonth}日"
            prefix == null -> "${date.monthValue}/${date.dayOfMonth}"
            date.monthValue == today.monthValue -> "${date.dayOfMonth}"
            else -> "${date.monthValue}/${date.dayOfMonth}"
        }
        return if (prefix == null) body else "$prefix $body"
    }
}
