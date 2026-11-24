package com.upcpocket.app.todo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "todo_task_order")
data class TodoTaskOrderEntity(
    @PrimaryKey val taskId: String,
    val importance: String,
    val manualRank: Int?,
)

fun TodoTaskOrderEntity.toDomain(): TodoTaskOrder = TodoTaskOrder(
    taskId = taskId,
    importance = TodoImportance.fromStorage(importance),
    manualRank = manualRank,
)

/** 单行排序设置；id 固定为 0。 */
@Entity(tableName = "todo_order_state")
data class TodoOrderStateEntity(
    @PrimaryKey val id: Int = 0,
    val mode: String,
    val nextManualRank: Int,
)
