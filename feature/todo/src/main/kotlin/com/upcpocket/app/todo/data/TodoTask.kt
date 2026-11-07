package com.upcpocket.app.todo.data

/**
 * 本机独立待办。
 *
 * 只保存用户明确需要的最小字段；没有账号、标签、项目、子任务或云同步。
 * [revision] 是通知/Alarm 的代数，任何有效修改都会递增。
 */
data class TodoTask(
    val id: String,
    val title: String,
    val completed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val reminderAt: Long?,
    val reminderFiredAt: Long?,
    val revision: Int,
)
