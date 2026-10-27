package com.upcpocket.app.ui.widget

import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.data.repo.TodayTimetable
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

/**
 * 小组件一次刷新要用到的全部课表数据。
 *
 * 打包成一个结构而不是让两个组件各查一遍库：它们看的是同一批数据，
 * 一次读到内存里，判断口径也不可能出现两边不一致。
 */
data class WidgetScheduleBundle(
    /** 到底有没有课表内容（区分"今天没课"和"还没有任何内容"） */
    val hasSchedule: Boolean,
    /** 今天属于第几教学周；假期/学期外为 null */
    val week: Int?,
    /** 今天 / 明天（用日期拼「今天 9.29 周二」这种列头） */
    val todayDate: LocalDate,
    val tomorrowDate: LocalDate,
    /** 今天的课 */
    val today: List<WidgetCourse>,
    /** 明天的课 */
    val tomorrow: List<WidgetCourse>,
    /** 现在几点（当天第几分钟） */
    val nowMin: Int,
    /** 下一个上下课时间点（毫秒时间戳），用来定"该变的时候变一次"的闹钟 */
    val nextBoundaryMillis: Long?,
) {
    /** 4×2 课表：左列今天、右列明天 */
    fun twoDayPlan(maxRows: Int): WidgetTwoDayPlan = WidgetSchedule.twoDayPlan(
        hasSchedule = hasSchedule,
        week = week,
        todayDate = todayDate,
        tomorrowDate = tomorrowDate,
        today = today,
        tomorrow = tomorrow,
        nowMin = nowMin,
        maxRows = maxRows,
    )

    /** 2×2：一节课大字（今天没了就顶明天的第一节） */
    fun nextClassPlan(previewRows: Int): WidgetNextClassPlan = WidgetSchedule.nextClassPlan(
        hasSchedule = hasSchedule,
        week = week,
        today = today,
        tomorrow = tomorrow,
        nowMin = nowMin,
        previewRows = previewRows,
    )
}

/**
 * 小组件的取数。
 *
 * 三条铁律（沿用上一版组件的口径，别"顺手优化"掉）：
 *
 *  1. **只读本地数据**。本应用根本没有网络权限，组件也不可能联网 ——
 *     它读的是 Room 里的自定义日程，外加（演示模式下）内存中即时生成的演示课。
 *  2. 判"第几周、今天有哪些课"全部走 [TodayTimetable]，也就是和 App 今天页同一套口径。
 *  3. **演示数据要标出来**：演示课的 [WidgetCourse.demo] 为 true，表头会带「演示」，
 *     桌面上不会出现一张看不出真假的课表。
 */
object WidgetData {

    /** 读一次界面偏好（主题、深浅色、课程配色、演示开关） */
    suspend fun prefs(): UiPrefs = runCatching { AppGraph.uiPrefsStore.flow.first() }
        .getOrDefault(UiPrefs())

    suspend fun loadSchedule(): WidgetScheduleBundle {
        val repo = AppGraph.scheduleRepository
        val prefsStore = AppGraph.uiPrefsStore
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val nowMillis = System.currentTimeMillis()

        val hasSchedule = TodayTimetable.hasAnySchedule(repo, prefsStore)
        val periods = runCatching { repo.periods() }.getOrDefault(emptyList())
        val week = TodayTimetable.currentWeekAt(repo, prefsStore, today)

        val todayCourses = TodayTimetable.sessionsOn(repo, prefsStore, today)
            .mapNotNull { WidgetSchedule.toCourse(it, periods) }
            .sortedBy { it.startMin }

        val tomorrowCourses = TodayTimetable.sessionsOn(repo, prefsStore, tomorrow)
            .mapNotNull { WidgetSchedule.toCourse(it, periods) }
            .sortedBy { it.startMin }

        return WidgetScheduleBundle(
            hasSchedule = hasSchedule,
            week = week,
            todayDate = today,
            tomorrowDate = tomorrow,
            today = todayCourses,
            tomorrow = tomorrowCourses,
            nowMin = nowMin,
            nextBoundaryMillis = WidgetSchedule.nextBoundaryMillis(todayCourses, nowMin, nowMillis),
        )
    }
}
