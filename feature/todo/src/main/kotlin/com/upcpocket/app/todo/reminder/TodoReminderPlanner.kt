package com.upcpocket.app.todo.reminder

import com.upcpocket.app.todo.data.TodoTask

enum class TodoReminderBlockReason {
    NOTIFICATIONS_DISABLED,
}

sealed interface TodoReminderPlan {
    data class Schedule(val at: Long, val exact: Boolean) : TodoReminderPlan
    data class NotifyNow(val at: Long) : TodoReminderPlan
    data class Blocked(val reason: TodoReminderBlockReason) : TodoReminderPlan
    data object Nothing : TodoReminderPlan
}

/**
 * 纯调度决策，便于对拒权、完成、重复发送和重启重建做单测。
 */
object TodoReminderPlanner {

    fun plan(
        task: TodoTask,
        nowMillis: Long,
        access: TodoReminderAccess,
    ): TodoReminderPlan {
        if (task.completed) return TodoReminderPlan.Nothing
        val reminder = task.reminderAt ?: return TodoReminderPlan.Nothing
        if (task.reminderFiredAt != null) return TodoReminderPlan.Nothing
        if (reminder > nowMillis) {
            return TodoReminderPlan.Schedule(
                at = reminder,
                exact = access.exactAlarmsAllowed,
            )
        }
        return if (access.notificationsEnabled) {
            TodoReminderPlan.NotifyNow(reminder)
        } else {
            TodoReminderPlan.Blocked(TodoReminderBlockReason.NOTIFICATIONS_DISABLED)
        }
    }

    /** 重启/包更新/时间变化后的全量计划；过去未发送的只补一次当前通知。 */
    fun rebuild(
        tasks: List<TodoTask>,
        nowMillis: Long,
        access: TodoReminderAccess,
    ): List<Pair<TodoTask, TodoReminderPlan>> = tasks.map { task ->
        task to plan(task, nowMillis, access)
    }

    /** Alarm 到点时再次校验代数；旧 Alarm 不应影响当前任务。 */
    fun acceptsAlarm(task: TodoTask?, revision: Int): Boolean =
        task != null && !task.completed && task.revision == revision && task.reminderFiredAt == null

    /** 通知动作只接受当前 revision 的未完成任务。 */
    fun acceptsAction(task: TodoTask?, revision: Int): Boolean =
        task != null && !task.completed && task.revision == revision
}
