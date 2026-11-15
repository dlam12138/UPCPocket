package com.upcpocket.app.ui.todo

import com.upcpocket.app.todo.data.TodoTask

/** Temporary checkbox state kept until the Room Flow reflects the compare-and-set write. */
internal data class TodoCompletionOverride(
    val revision: Int,
    val completed: Boolean,
)

internal fun reconcileTodoCompletionOverrides(
    tasks: List<TodoTask>,
    overrides: Map<String, TodoCompletionOverride>,
): Map<String, TodoCompletionOverride> {
    val byId = tasks.associateBy(TodoTask::id)
    return overrides.filter { (id, override) ->
        val task = byId[id] ?: return@filter false
        task.revision < override.revision
    }
}
