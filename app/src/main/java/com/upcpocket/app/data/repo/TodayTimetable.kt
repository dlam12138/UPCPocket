package com.upcpocket.app.data.repo

import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.domain.Session
import java.time.LocalDate

/**
 * 「某一天有哪些课」的**唯一口径**。
 *
 * 为什么必须只有一个入口：今天页和桌面组件都在问同一个问题。以前它们各自写一套
 * （都只看当前学期），一旦口径分叉就会出现"今天页没课、组件有课"这类互相矛盾的画面。
 *
 * M1 的取数只有一条链路：当前学期 → [ScheduleRepository.sessionsOn]（自定义日程 + 演示课）。
 * 没有研究生那套合成学期，也没有什么"本科优先"的兜底。
 */
object TodayTimetable {

    /**
     * 某天的全部课程，按节次排序。
     *
     * 没有学期、或这一天在学期范围外（假期）时返回空表 —— 调用方据此区分
     * "没课" 与 "还没有课表数据"，不要混成一句话。
     */
    suspend fun sessionsOn(
        repo: ScheduleRepository,
        prefs: UiPrefsStore,
        date: LocalDate,
    ): List<Session> {
        val semester = runCatching { repo.currentSemester() }.getOrNull() ?: return emptyList()
        val week = runCatching { repo.resolveCurrentWeek(semester, date) }.getOrNull() ?: return emptyList()
        return runCatching { repo.sessionsOn(semester.id, date, week) }.getOrDefault(emptyList())
    }

    /**
     * [date] 属于第几教学周；学期外（假期）返回 null。
     *
     * ⚠️ 这个值不只是"显示第几周"：今天页与组件用它判断"是不是在学期内"，
     * `null` 会被当成**假期**。
     */
    suspend fun currentWeekAt(
        repo: ScheduleRepository,
        prefs: UiPrefsStore,
        date: LocalDate,
    ): Int? {
        val semester = runCatching { repo.currentSemester() }.getOrNull() ?: return null
        return runCatching { repo.resolveCurrentWeek(semester, date) }.getOrNull()
    }

    /**
     * 有没有"任何"可显示的课表内容（用户日程或演示课）。
     *
     * 组件用它区分「还没有课表数据」和「今天没课」——这两种情况对用户意思完全不同。
     * 演示模式开着就算有数据：那时课表确实有内容可画。
     */
    suspend fun hasAnySchedule(repo: ScheduleRepository, prefs: UiPrefsStore): Boolean {
        val semester = runCatching { repo.currentSemester() }.getOrNull() ?: return false
        return runCatching { repo.hasCourseData(semester.id) }.getOrDefault(false)
    }
}
