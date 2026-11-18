package com.upcpocket.app.todo.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoOrderingTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 6)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `deadlines sort before undated tasks while reminder times do not affect priority`() {
        val tasks = listOf(
            task("new", createdAt = 50, reminder = null),
            task(
                "future",
                createdAt = 60,
                reminder = today.minusDays(1).atTime(8, 0),
                due = today.plusDays(2).atTime(9, 0),
            ),
            task("today", createdAt = 10, reminder = null, due = today.atTime(15, 0)),
            task(
                "overdue",
                createdAt = 20,
                reminder = today.plusDays(2).atTime(8, 0),
                due = today.minusDays(1).atTime(8, 0),
            ),
            task("reminded", createdAt = 70, reminder = today.atTime(9, 0)),
            task("done", createdAt = 99, reminder = null, due = today.atTime(9, 0), completed = true),
        )

        val ordered = TodoOrdering.incomplete(tasks, now, zone).map { it.id }

        assertEquals(listOf("overdue", "today", "future", "reminded", "new"), ordered)
        assertEquals(listOf("overdue", "today"), TodoOrdering.homeSummary(tasks, now, zone).map { it.id })
        assertTrue(TodoOrdering.isOverdueOrToday(tasks.first { it.id == "today" }, now, zone))
        assertFalse(TodoOrdering.isOverdueOrToday(tasks.first { it.id == "reminded" }, now, zone))
    }

    @Test
    fun `same millisecond tasks use id as a stable tie break`() {
        val tasks = listOf(
            task("b", createdAt = 10, reminder = null),
            task("a", createdAt = 10, reminder = null),
            task("c", createdAt = 10, reminder = null),
        )
        assertEquals(listOf("a", "b", "c"), TodoOrdering.incomplete(tasks, now, zone).map { it.id })

        val deadline = today.plusDays(1).atTime(9, 0)
        val withSameDeadline = listOf(
            task("z", createdAt = 20, reminder = null, due = deadline),
            task("a", createdAt = 10, reminder = null, due = deadline),
        )
        assertEquals(listOf("a", "z"), TodoOrdering.incomplete(withSameDeadline, now, zone).map { it.id })
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
        due: java.time.LocalDateTime? = null,
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
        dueAt = due?.atZone(zone)?.toInstant()?.toEpochMilli(),
    )
}
