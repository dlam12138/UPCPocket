package com.upcpocket.app.todo.data

/** Local-only widget read; no reminder or school state is inferred from the snapshot. */
sealed interface TodoWidgetSnapshotResult {
    /** The plugin has not completed its initial local database reconciliation yet. */
    data object NotRestored : TodoWidgetSnapshotResult

    /** The local read failed; callers must keep this distinct from a valid empty list. */
    data object ReadFailed : TodoWidgetSnapshotResult

    /** [tasks] is an ordered, bounded prefix of incomplete tasks; count covers every incomplete task. */
    data class Ready(
        val tasks: List<TodoTask>,
        val incompleteCount: Int,
    ) : TodoWidgetSnapshotResult
}
