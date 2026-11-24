package com.upcpocket.app.todo.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页与待办页共用的稳定排序。
 *
 * 未完成：置顶任务始终排在未置顶任务之前；各分组内若用户已手动排序，按 sortOrder 升序，
 * 新增或刚切换置顶状态的未排位任务置于分组顶部（置顶按 pinnedAt 倒序，未置顶沿用有截止按
 * dueAt 升序、无截止按创建时间倒序）。所有分支均以 id 作最终 tie-break，避免顺序漂移。
 * 提醒时间不参与任务优先级。
 */
object TodoOrdering {

    @Suppress("UNUSED_PARAMETER") // Kept for call-site compatibility; absolute deadlines need no clock conversion.
    fun incomplete(
        tasks: List<TodoTask>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TodoTask> {
        val (pinned, unpinned) = tasks
            .asSequence()
            .filterNot { it.completed }
            .partition { it.pinned }
        return sortPinned(pinned) + sortUnpinned(unpinned)
    }

    private fun sortPinned(tasks: List<TodoTask>): List<TodoTask> {
        val (unranked, ranked) = tasks.partition { it.sortOrder == null }
        return unranked.sortedWith(
            compareByDescending<TodoTask> { it.pinnedAt ?: 0L }
                .thenBy { it.id },
        ) + ranked.sortedWith(
            compareBy<TodoTask> { it.sortOrder!! }
                .thenByDescending { it.pinnedAt ?: 0L }
                .thenBy { it.id },
        )
    }

    private fun sortUnpinned(tasks: List<TodoTask>): List<TodoTask> {
        val (unranked, ranked) = tasks.partition { it.sortOrder == null }
        val (withDeadline, withoutDeadline) = unranked.partition { it.dueAt != null }
        val sortedUnranked = withDeadline.sortedWith(
            compareBy<TodoTask> { it.dueAt!! }.thenBy { it.id },
        ) + withoutDeadline.sortedWith(
            compareByDescending<TodoTask> { it.createdAt }.thenBy { it.id },
        )
        val sortedRanked = ranked.sortedWith(
            compareBy<TodoTask> { it.sortOrder!! }.thenBy { it.id },
        )
        return sortedUnranked + sortedRanked
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
        val deadline = task.dueAt ?: return false
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(deadline).atZone(zone).toLocalDate()
        return !day.isAfter(today)
    }

    fun localDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
