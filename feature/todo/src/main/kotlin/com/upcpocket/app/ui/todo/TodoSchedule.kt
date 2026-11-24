package com.upcpocket.app.ui.todo

import com.upcpocket.app.todo.data.TodoOrdering
import com.upcpocket.app.todo.data.TodoOrderingSnapshot
import com.upcpocket.app.todo.data.TodoSortMode
import com.upcpocket.app.todo.data.TodoTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 合页日程区的纯日期规则，便于离线回归。
 *
 * 周条固定周日至周六；命中只看设备本地时区的 dueAt / reminderAt。今天额外纳入
 * 未完成的已过期截止与完全未定时任务，避免“今天无待办”掩盖真实未完成事项。
 * 已完成任务仍按明确日期展示；这类今天额外纳入的任务完成后改按 completedAt
 * 的设备本地日期展示，次日不再带入。
 */
internal object TodoSchedule {

    fun weekStart(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value % 7).toLong())

    fun weekDates(date: LocalDate): List<LocalDate> {
        val start = weekStart(date)
        return (0L..6L).map(start::plusDays)
    }

    fun tasksForDate(
        tasks: List<TodoTask>,
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        mode: TodoSortMode = TodoSortMode.DATE,
        ordering: TodoOrderingSnapshot = TodoOrderingSnapshot.Default,
    ): List<TodoTask> = TodoOrdering.sortByMode(
        tasks.asSequence()
            .filter { matches(it, date, today, zone) }
            .sortedWith(
                compareBy<TodoTask> { anchorMillis(it, date, today, zone) ?: Long.MAX_VALUE }
                    .thenBy { it.title }
                    .thenBy { it.id },
            )
            .toList(),
        mode,
        ordering,
    )

    fun unscheduledOpenCount(tasks: List<TodoTask>): Int =
        tasks.count { !it.completed && it.dueAt == null && it.reminderAt == null }

    fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    private fun matches(
        task: TodoTask,
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
    ): Boolean {
        val due = task.dueAt?.let { localDate(it, zone) }
        val reminder = task.reminderAt?.let { localDate(it, zone) }
        // 明确的截止或提醒日期对完成与未完成一视同仁，同一任务一天只出现一次。
        if (due == date || reminder == date) return true
        if (!task.completed) {
            return date == today && when {
                due != null -> due.isBefore(today)
                else -> reminder == null
            }
        }
        // 逾期或无日期任务原本只在“今天”额外出现，完成后改锚定实际完成日。
        // 缺少 completedAt 的旧记录没有这条额外展示，只保留明确的截止/提醒日期。
        val completedDate = task.completedAt?.let { localDate(it, zone) } ?: return false
        if (completedDate != date) return false
        return when {
            due != null -> due.isBefore(completedDate)
            else -> reminder == null
        }
    }

    /**
     * 当天行内排序：命中截止、命中提醒、逾期截止，最后按标题/ID。
     *
     * 完成状态本身不参与排序；回看逾期项的完成日仍使用原截止锚点。
     */
    private fun anchorMillis(
        task: TodoTask,
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
    ): Long? {
        val dueDate = task.dueAt?.let { localDate(it, zone) }
        val reminderDate = task.reminderAt?.let { localDate(it, zone) }
        val completedDate = task.completedAt?.let { localDate(it, zone) }
        return when {
            dueDate == date -> task.dueAt
            reminderDate == date -> task.reminderAt
            !task.completed && date == today && dueDate != null && dueDate.isBefore(today) -> {
                task.dueAt
            }
            task.completed && completedDate == date &&
                dueDate != null && dueDate.isBefore(completedDate) -> task.dueAt
            else -> null
        }
    }
}
