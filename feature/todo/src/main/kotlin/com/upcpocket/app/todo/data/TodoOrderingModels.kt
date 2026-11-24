package com.upcpocket.app.todo.data

/** 用户可见的重要程度；同级任务继续沿用各视图原有的日期顺序。 */
enum class TodoImportance(val sortWeight: Int) {
    HIGH(0),
    NORMAL(1),
    LOW(2);

    companion object {
        fun fromStorage(value: String?): TodoImportance =
            entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

enum class TodoSortMode {
    DATE,
    IMPORTANCE,
    MANUAL;

    companion object {
        fun fromStorage(value: String?): TodoSortMode =
            entries.firstOrNull { it.name == value } ?: DATE
    }
}

enum class TodoMoveDirection { UP, DOWN }

/** 按 taskId 保存的重要程度与可空手动位次；普通/无位次是缺省值。 */
data class TodoTaskOrder(
    val taskId: String,
    val importance: TodoImportance = TodoImportance.NORMAL,
    val manualRank: Int? = null,
)

/** 完整列表与日程区共用的排序快照；缺失记录按普通/无位次处理。 */
data class TodoOrderingSnapshot(
    val mode: TodoSortMode = TodoSortMode.DATE,
    val orders: Map<String, TodoTaskOrder> = emptyMap(),
) {
    fun importanceOf(taskId: String): TodoImportance =
        orders[taskId]?.importance ?: TodoImportance.NORMAL

    fun manualRankOf(taskId: String): Int? = orders[taskId]?.manualRank

    companion object {
        val Default = TodoOrderingSnapshot()
    }
}

/** 同一次 Room 读事务产生的任务与排序快照，UI 不再组合两条可能半新半旧的 Flow。 */
data class TodoSnapshot(
    val tasks: List<TodoTask> = emptyList(),
    val ordering: TodoOrderingSnapshot = TodoOrderingSnapshot.Default,
)

/** 删除时连同排序记录一起取快照，撤销时由同一事务还原。 */
data class TodoDeletedTask(
    val task: TodoTask,
    val importance: TodoImportance = TodoImportance.NORMAL,
    val manualRank: Int? = null,
)
