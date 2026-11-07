package com.upcpocket.app.todo.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页与待办页共用的稳定排序。
 *
 * 未完成：提醒时间早于明天 0 点的“逾期/今天”项优先，按提醒时刻升序；其余按创建
 * 时间倒序。所有分组最终用 id 做 tie-break，避免同一毫秒创建时顺序漂移。
 */
object TodoOrdering {

    fun incomplete(
        tasks: List<TodoTask>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TodoTask> {
        val tomorrowStart = Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val (dueOrToday, rest) = tasks
            .asSequence()
            .filterNot { it.completed }
            .partition { task ->
                val reminder = task.reminderAt
                reminder != null && reminder < tomorrowStart
            }
        return dueOrToday.sortedWith(
            compareBy<TodoTask>(
                { it.reminderAt ?: Long.MAX_VALUE },
                { it.createdAt },
                { it.id },
            ),
        ) + rest.sortedWith(
            compareByDescending<TodoTask> { it.createdAt }.thenBy { it.id },
        )
    }

    fun completed(tasks: List<TodoTask>): List<TodoTask> = tasks
        .asSequence()
        .filter { it.completed }
        .sortedWith(
            compareByDescending<TodoTask> { it.completedAt ?: it.updatedAt }
                .thenBy { it.id },
        )
        .toList()

    fun homeSummary(
        tasks: List<TodoTask>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = 2,
    ): List<TodoTask> = incomplete(tasks, nowMillis, zone).take(limit)

    fun isOverdueOrToday(
        task: TodoTask,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val reminder = task.reminderAt ?: return false
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(reminder).atZone(zone).toLocalDate()
        return !day.isAfter(today)
    }

    fun localDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
