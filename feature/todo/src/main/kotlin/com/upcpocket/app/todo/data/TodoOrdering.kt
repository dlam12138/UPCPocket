package com.upcpocket.app.todo.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页与待办页共用的稳定排序。
 *
 * 未完成：有截止时间的任务按 dueAt 升序；无截止时间的任务按创建时间倒序。
 * 两组都用 id 作最终 tie-break，避免顺序漂移。提醒时间不参与任务优先级。
 */
object TodoOrdering {

    @Suppress("UNUSED_PARAMETER") // Kept for call-site compatibility; absolute deadlines need no clock conversion.
    fun incomplete(
        tasks: List<TodoTask>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TodoTask> {
        val (withDeadline, withoutDeadline) = tasks
            .asSequence()
            .filterNot { it.completed }
            .partition { it.dueAt != null }
        return withDeadline.sortedWith(
            compareBy<TodoTask> { it.dueAt!! }.thenBy { it.id },
        ) + withoutDeadline.sortedWith(
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

    /**
     * 在调用方已有的日期顺序上应用重要程度或手动模式。
     *
     * 使用稳定排序：同级任务、无手动位次的任务继续保留传入顺序，不会打乱日期规则。
     */
    fun sortByMode(
        tasks: List<TodoTask>,
        mode: TodoSortMode,
        ordering: TodoOrderingSnapshot,
    ): List<TodoTask> = when (mode) {
        TodoSortMode.DATE -> tasks
        TodoSortMode.IMPORTANCE -> tasks.sortedWith(
            compareBy<TodoTask> { ordering.importanceOf(it.id).sortWeight },
        )
        TodoSortMode.MANUAL -> tasks.sortedWith(
            compareBy<TodoTask> { ordering.manualRankOf(it.id) ?: Int.MAX_VALUE },
        )
    }

    fun isOverdueOrToday(
        task: TodoTask,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val deadline = task.dueAt ?: return false
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(deadline).atZone(zone).toLocalDate()
        return !day.isAfter(today)
    }

    fun localDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
