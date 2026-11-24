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
    /** Optional deadline instant; independent from the reminder schedule. */
    val dueAt: Long? = null,
    /** Epoch millis when pinned; non-null means the task is pinned to the top. */
    val pinnedAt: Long? = null,
    /** Optional manual display order within the task's pin group (ascending). */
    val sortOrder: Long? = null,
) {
    val pinned: Boolean get() = pinnedAt != null
}
