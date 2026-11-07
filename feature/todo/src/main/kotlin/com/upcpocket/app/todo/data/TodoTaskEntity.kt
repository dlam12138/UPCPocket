package com.upcpocket.app.todo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "todo_tasks")
data class TodoTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val completed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val reminderAt: Long?,
    val reminderFiredAt: Long?,
    val revision: Int,
)

fun TodoTaskEntity.toDomain(): TodoTask = TodoTask(
    id = id,
    title = title,
    completed = completed,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    reminderAt = reminderAt,
    reminderFiredAt = reminderFiredAt,
    revision = revision,
)

fun TodoTask.toEntity(): TodoTaskEntity = TodoTaskEntity(
    id = id,
    title = title,
    completed = completed,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    reminderAt = reminderAt,
    reminderFiredAt = reminderFiredAt,
    revision = revision,
)
