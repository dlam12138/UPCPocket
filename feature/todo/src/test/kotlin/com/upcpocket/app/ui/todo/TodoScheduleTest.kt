package com.upcpocket.app.ui.todo

import com.upcpocket.app.todo.data.TodoTask
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoScheduleTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.parse("2026-11-20")

    @Test
    fun `week strip runs Sunday through Saturday across month boundaries`() {
        val dates = TodoSchedule.weekDates(today)

        assertEquals(7, dates.size)
        assertEquals(LocalDate.parse("2026-11-15"), dates.first())
        assertEquals(LocalDate.parse("2026-11-21"), dates.last())
        assertEquals(today, dates[5])
    }

    @Test
    fun `today keeps overdue and untimed tasks and lists each task once`() {
        val overdue = task("overdue", dueAt = at("2026-11-19", 9))
        val dueToday = task("due-today", dueAt = at("2026-11-20", 9))
        val reminderToday = task("reminder-today", reminderAt = at("2026-11-20", 10))
        val bothToday = task(
            "both-today",
            dueAt = at("2026-11-20", 18),
            reminderAt = at("2026-11-20", 20),
        )
        val untimed = task("untimed")
        val future = task("future", dueAt = at("2026-11-22", 9))
        val completed = task(
            "completed",
            dueAt = at("2026-11-20", 8),
            completed = true,
        )

        val visible = TodoSchedule.tasksForDate(
            listOf(
                future,
                untimed,
                bothToday,
                completed,
                reminderToday,
                dueToday,
                overdue,
            ),
            date = today,
            today = today,
            zone = zone,
        )

        assertEquals(
            listOf("overdue", "due-today", "reminder-today", "both-today", "untimed"),
            visible.map { it.id },
        )
        assertEquals(1, visible.count { it.id == "both-today" })
        assertEquals(1, TodoSchedule.unscheduledOpenCount(listOf(untimed, future, completed)))
    }

    @Test
    fun `other days match due or reminder dates but never hide untimed tasks`() {
        val dueTomorrow = task("due-tomorrow", dueAt = at("2026-11-21", 9))
        val reminderTomorrow = task("reminder-tomorrow", reminderAt = at("2026-11-21", 8))
        val crossDay = task(
            "cross-day",
            dueAt = at("2026-11-22", 9),
            reminderAt = at("2026-11-21", 7),
        )
        val untimed = task("untimed")
        val completed = task(
            "completed",
            dueAt = at("2026-11-21", 10),
            completed = true,
        )

        val visible = TodoSchedule.tasksForDate(
            listOf(dueTomorrow, reminderTomorrow, crossDay, untimed, completed),
            date = LocalDate.parse("2026-11-21"),
            today = today,
            zone = zone,
        )

        assertEquals(
            listOf("cross-day", "reminder-tomorrow", "due-tomorrow"),
            visible.map { it.id },
        )
        assertEquals(1, TodoSchedule.unscheduledOpenCount(listOf(dueTomorrow, reminderTomorrow, crossDay, untimed)))
    }

    @Test
    fun `earlier date only matches its own schedule and today pulls overdue forward`() {
        val overdue = task("overdue", dueAt = at("2026-11-19", 9))
        val dueToday = task("due-today", dueAt = at("2026-11-20", 9))

        assertEquals(
            emptyList<String>(),
            TodoSchedule.tasksForDate(
                listOf(overdue, dueToday),
                date = LocalDate.parse("2026-11-18"),
                today = today,
                zone = zone,
            ).map { it.id },
        )
        assertEquals(
            listOf("overdue"),
            TodoSchedule.tasksForDate(
                listOf(overdue, dueToday),
                date = LocalDate.parse("2026-11-19"),
                today = today,
                zone = zone,
            ).map { it.id },
        )
        assertEquals(
            listOf("overdue", "due-today"),
            TodoSchedule.tasksForDate(
                listOf(overdue, dueToday),
                date = today,
                today = today,
                zone = zone,
            ).map { it.id },
        )
    }

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun task(
        id: String,
        dueAt: Long? = null,
        reminderAt: Long? = null,
        completed: Boolean = false,
    ) = TodoTask(
        id = id.ifEmpty { UUID.randomUUID().toString() },
        title = id,
        completed = completed,
        createdAt = 0,
        updatedAt = 0,
        completedAt = if (completed) 0 else null,
        reminderAt = reminderAt,
        reminderFiredAt = null,
        revision = 1,
        dueAt = dueAt,
    )
}
