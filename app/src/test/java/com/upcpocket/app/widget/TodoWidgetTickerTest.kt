package com.upcpocket.app.widget

import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.data.TodoWidgetSnapshotResult
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoWidgetTickerTest {

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 17)
    private val now = today.atTime(8, 0).atZone(zone)

    @Test
    fun `next visible deadline wins before midnight`() {
        val dueAt = today.atTime(10, 15).atZone(zone).toInstant().toEpochMilli()
        val task = task(dueAt)
        val snapshot = TodoWidgetSnapshotResult.Ready(listOf(task), incompleteCount = 1)

        assertEquals(
            dueAt,
            TodoWidgetTicker.nextBoundary(snapshot, now).toInstant().toEpochMilli(),
        )
    }

    @Test
    fun `without a future deadline widget redraws at local midnight`() {
        val dueAt = today.atTime(7, 30).atZone(zone).toInstant().toEpochMilli()
        val snapshot = TodoWidgetSnapshotResult.Ready(listOf(task(dueAt)), incompleteCount = 1)
        val expected = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(expected, TodoWidgetTicker.nextBoundary(snapshot, now).toInstant().toEpochMilli())
    }

    private fun task(dueAt: Long) = TodoTask(
        id = "d2719e0c-54b0-4eab-9836-5e35e0281234",
        title = "本机待办",
        completed = false,
        createdAt = now.toInstant().toEpochMilli(),
        updatedAt = now.toInstant().toEpochMilli(),
        completedAt = null,
        reminderAt = null,
        reminderFiredAt = null,
        revision = 1,
        dueAt = dueAt,
    )
}
