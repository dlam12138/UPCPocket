package com.upcpocket.app.todo.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room 之上的唯一任务读写入口。
 *
 * 列表 Flow 是事实来源；通知、Alarm 和 UI 都从这里读取，不各自维护副本。
 */
class TodoRepository(
    private val dao: TodoTaskDao,
) {
    val tasks: Flow<List<TodoTask>> = dao.observeAll().map { entities ->
        entities.map(TodoTaskEntity::toDomain)
    }

    suspend fun all(): List<TodoTask> = dao.all().map(TodoTaskEntity::toDomain)

    suspend fun byId(id: String): TodoTask? = dao.byId(id)?.toDomain()

    suspend fun insert(task: TodoTask) = dao.upsert(task.toEntity())

    suspend fun updateContent(
        id: String,
        title: String,
        reminderAt: Long?,
        firedAt: Long?,
        now: Long,
    ): TodoTask? {
        if (dao.updateContent(id, title, reminderAt, firedAt, now) == 0) return null
        return byId(id)
    }

    suspend fun setCompleted(id: String, completed: Boolean, now: Long): TodoTask? {
        val completedAt = now.takeIf { completed }
        if (dao.setCompleted(id, completed, completedAt, now) == 0) return null
        return byId(id)
    }

    suspend fun completeIfRevision(id: String, revision: Int, now: Long): TodoTask? {
        if (dao.completeIfRevision(id, revision, now) == 0) return null
        return byId(id)
    }

    suspend fun snoozeIfRevision(
        id: String,
        revision: Int,
        until: Long,
        now: Long,
    ): TodoTask? {
        if (dao.snoozeIfRevision(id, revision, until, now) == 0) return null
        return byId(id)
    }

    suspend fun markFiredIfRevision(
        id: String,
        revision: Int,
        firedAt: Long,
        now: Long,
    ): TodoTask? {
        if (dao.markFiredIfRevision(id, revision, firedAt, now) == 0) return null
        return byId(id)
    }

    suspend fun clearFiredIfRevision(id: String, revision: Int, now: Long): TodoTask? {
        if (dao.clearFiredIfRevision(id, revision, now) == 0) return null
        return byId(id)
    }

    suspend fun delete(id: String): TodoTask? {
        val task = byId(id) ?: return null
        dao.delete(id)
        return task
    }
}
