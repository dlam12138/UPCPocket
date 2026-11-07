package com.upcpocket.app.todo.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoOrderingTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 6)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `overdue and today reminders come first then other incomplete by newest`() {
        val tasks = listOf(
            task("new", createdAt = 50, reminder = null),
            task("future", createdAt = 60, reminder = today.plusDays(2).atTime(9, 0)),
            task("today", createdAt = 10, reminder = today.atTime(15, 0)),
            task("overdue", createdAt = 20, reminder = today.minusDays(1).atTime(8, 0)),
            task("done", createdAt = 99, reminder = today.atTime(9, 0), completed = true),
        )

        val ordered = TodoOrdering.incomplete(tasks, now, zone).map { it.id }

        assertEquals(listOf("overdue", "today", "future", "new"), ordered)
        assertEquals(listOf("overdue", "today"), TodoOrdering.homeSummary(tasks, now, zone).map { it.id })
    }

    @Test
    fun `same millisecond tasks use id as a stable tie break`() {
        val tasks = listOf(
            task("b", createdAt = 10, reminder = null),
            task("a", createdAt = 10, reminder = null),
            task("c", createdAt = 10, reminder = null),
        )
        assertEquals(listOf("a", "b", "c"), TodoOrdering.incomplete(tasks, now, zone).map { it.id })
    }

    @Test
    fun `completed tasks never enter home summary`() {
        val tasks = listOf(
            task("done", createdAt = 1, reminder = null, completed = true),
            task("open", createdAt = 2, reminder = null),
        )
        assertEquals(listOf("open"), TodoOrdering.homeSummary(tasks, now, zone).map { it.id })
        assertEquals(listOf("done"), TodoOrdering.completed(tasks).map { it.id })
    }

    private fun task(
        id: String,
        createdAt: Long,
        reminder: java.time.LocalDateTime?,
        completed: Boolean = false,
    ) = TodoTask(
        id = id,
        title = id,
        completed = completed,
        createdAt = createdAt,
        updatedAt = createdAt,
        completedAt = if (completed) createdAt else null,
        reminderAt = reminder?.atZone(zone)?.toInstant()?.toEpochMilli(),
        reminderFiredAt = null,
        revision = 1,
    )
}
