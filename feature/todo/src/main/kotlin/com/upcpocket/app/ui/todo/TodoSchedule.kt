package com.upcpocket.app.ui.todo

import com.upcpocket.app.todo.data.TodoTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 合页日程区的纯日期规则，便于离线回归。
 *
 * 周条固定周日至周六；命中只看设备本地时区的 dueAt / reminderAt。今天额外纳入
 * 已过期截止与完全未定时的任务，避免“今天无待办”掩盖真实未完成事项。
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
    ): List<TodoTask> = tasks.asSequence()
        .filter { !it.completed && matches(it, date, today, zone) }
        .sortedWith(
            compareBy<TodoTask> { anchorMillis(it, date, today, zone) ?: Long.MAX_VALUE }
                .thenBy { it.title }
                .thenBy { it.id },
        )
        .toList()

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
        return if (date == today) {
            val overdue = due != null && due.isBefore(today)
            overdue || due == today || reminder == today ||
                (due == null && reminder == null)
        } else {
            due == date || reminder == date
        }
    }

    /** 当天行内排序：先过期/当天截止，再当天提醒，最后未定时。 */
    private fun anchorMillis(
        task: TodoTask,
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
    ): Long? {
        val dueDate = task.dueAt?.let { localDate(it, zone) }
        val reminderDate = task.reminderAt?.let { localDate(it, zone) }
        return when {
            dueDate == date -> task.dueAt
            reminderDate == date -> task.reminderAt
            date == today && dueDate != null && dueDate.isBefore(today) -> task.dueAt
            else -> null
        }
    }
}
