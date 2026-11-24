package com.upcpocket.app.todo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoOrderDao {

    @Query("SELECT * FROM todo_task_order")
    fun observeOrders(): Flow<List<TodoTaskOrderEntity>>

    @Query("SELECT * FROM todo_task_order")
    suspend fun orders(): List<TodoTaskOrderEntity>

    @Query("SELECT * FROM todo_task_order WHERE taskId = :taskId LIMIT 1")
    suspend fun orderFor(taskId: String): TodoTaskOrderEntity?

    @Query("SELECT * FROM todo_order_state WHERE id = 0 LIMIT 1")
    fun observeState(): Flow<TodoOrderStateEntity?>

    @Query("SELECT * FROM todo_order_state WHERE id = 0 LIMIT 1")
    suspend fun state(): TodoOrderStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: TodoTaskOrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: TodoOrderStateEntity)

    @Query("DELETE FROM todo_task_order WHERE taskId = :taskId")
    suspend fun deleteOrder(taskId: String): Int
}
