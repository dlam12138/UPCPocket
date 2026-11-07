package com.upcpocket.app.todo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoTaskDao {

    @Query("SELECT * FROM todo_tasks")
    fun observeAll(): Flow<List<TodoTaskEntity>>

    @Query("SELECT * FROM todo_tasks")
    suspend fun all(): List<TodoTaskEntity>

    @Query("SELECT * FROM todo_tasks WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TodoTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TodoTaskEntity)

    @Query("DELETE FROM todo_tasks WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query(
        """
        UPDATE todo_tasks
        SET title = :title,
            reminderAt = :reminderAt,
            reminderFiredAt = :firedAt,
            updatedAt = :now,
            revision = revision + 1
        WHERE id = :id
        """,
    )
    suspend fun updateContent(
        id: String,
        title: String,
        reminderAt: Long?,
        firedAt: Long?,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE todo_tasks
        SET completed = :completed,
            completedAt = :completedAt,
            updatedAt = :now,
            revision = revision + 1
        WHERE id = :id
        """,
    )
    suspend fun setCompleted(
        id: String,
        completed: Boolean,
        completedAt: Long?,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE todo_tasks
        SET completed = 1,
            completedAt = :now,
            updatedAt = :now,
            revision = revision + 1
        WHERE id = :id AND revision = :revision AND completed = 0
        """,
    )
    suspend fun completeIfRevision(id: String, revision: Int, now: Long): Int

    @Query(
        """
        UPDATE todo_tasks
        SET reminderAt = :until,
            reminderFiredAt = NULL,
            updatedAt = :now,
            revision = revision + 1
        WHERE id = :id AND revision = :revision AND completed = 0 AND reminderAt IS NOT NULL
        """,
    )
    suspend fun snoozeIfRevision(
        id: String,
        revision: Int,
        until: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE todo_tasks
        SET reminderFiredAt = :firedAt,
            updatedAt = :now,
            revision = revision + 1
        WHERE id = :id AND revision = :revision AND completed = 0
            AND reminderAt IS NOT NULL AND reminderFiredAt IS NULL
        """,
    )
    suspend fun markFiredIfRevision(
        id: String,
        revision: Int,
        firedAt: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE todo_tasks
        SET reminderFiredAt = NULL,
            updatedAt = :now,
            revision = revision + 1
        WHERE id = :id AND revision = :revision AND reminderFiredAt IS NOT NULL
        """,
    )
    suspend fun clearFiredIfRevision(id: String, revision: Int, now: Long): Int
}
