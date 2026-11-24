package com.upcpocket.app.ui.todo

import com.upcpocket.app.todo.data.TodoImportance
import com.upcpocket.app.todo.data.TodoOrderingSnapshot
import com.upcpocket.app.todo.data.TodoSortMode
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.data.TodoTaskOrder
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
    fun `today matches completed and open tasks while keeping overdue and untimed open tasks`() {
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
            listOf(
                "overdue",
                "completed",
                "due-today",
                "reminder-today",
                "both-today",
                "untimed",
            ),
            visible.map { it.id },
        )
        assertEquals(1, visible.count { it.id == "both-today" })
        assertEquals(1, TodoSchedule.unscheduledOpenCount(listOf(untimed, future, completed)))
    }

    @Test
    fun `other days match due or reminder for completed and open tasks`() {
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
            listOf("cross-day", "reminder-tomorrow", "due-tomorrow", "completed"),
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

    @Test
    fun `completed overdue and undated tasks stay on their completion date only`() {
        val completedAt = at("2026-11-20", 23, 50)
        val overdue = task(
            "overdue",
            dueAt = at("2026-11-19", 9),
            completed = true,
            completedAt = completedAt,
        )
        val undated = task("undated", completed = true, completedAt = completedAt)
        val oldDue = task(
            "old-due",
            dueAt = at("2026-11-18", 9),
            completed = true,
            completedAt = null,
        )
        val oldReminder = task(
            "old-reminder",
            reminderAt = at("2026-11-17", 9),
            completed = true,
            completedAt = null,
        )
        val tasks = listOf(overdue, undated, oldDue, oldReminder)

        assertEquals(
            listOf("overdue", "undated"),
            TodoSchedule.tasksForDate(tasks, today, today, zone).map { it.id },
        )
        assertEquals(
            listOf("overdue"),
            TodoSchedule.tasksForDate(
                tasks,
                LocalDate.parse("2026-11-19"),
                today,
                zone,
            ).map { it.id },
        )
        assertEquals(
            emptyList<String>(),
            TodoSchedule.tasksForDate(
                tasks,
                LocalDate.parse("2026-11-21"),
                LocalDate.parse("2026-11-21"),
                zone,
            ).map { it.id },
        )
        // 缺少 completedAt 的旧记录仍只按明确的截止或提醒日期展示。
        assertEquals(
            listOf("old-due"),
            TodoSchedule.tasksForDate(
                tasks,
                LocalDate.parse("2026-11-18"),
                today,
                zone,
            ).map { it.id },
        )
        assertEquals(
            listOf("old-reminder"),
            TodoSchedule.tasksForDate(
                tasks,
                LocalDate.parse("2026-11-17"),
                today,
                zone,
            ).map { it.id },
        )
    }

    @Test
    fun `completion state does not reorder the same day`() {
        val open = listOf(
            task("later-due", dueAt = at("2026-11-19", 15)),
            task("undated"),
            task("earlier-due", dueAt = at("2026-11-18", 9)),
        )
        val completed = open.map {
            it.copy(
                completed = true,
                completedAt = at("2026-11-20", 12),
            )
        }

        val expected = listOf("earlier-due", "later-due", "undated")
        assertEquals(
            expected,
            TodoSchedule.tasksForDate(open, today, today, zone).map { it.id },
        )
        assertEquals(
            expected,
            TodoSchedule.tasksForDate(completed, today, today, zone).map { it.id },
        )
    }

    @Test
    fun `selected mode applies after filtering to the chosen day`() {
        val early = task("early", dueAt = at("2026-11-20", 8))
        val late = task("late", dueAt = at("2026-11-20", 18))
        val otherDay = task("other-day", dueAt = at("2026-11-21", 9))
        val ordering = TodoOrderingSnapshot(
            mode = TodoSortMode.IMPORTANCE,
            orders = mapOf(
                "late" to TodoTaskOrder("late", TodoImportance.HIGH),
                "other-day" to TodoTaskOrder("other-day", TodoImportance.HIGH, manualRank = 1),
                "early" to TodoTaskOrder("early", TodoImportance.LOW, manualRank = 2),
            ),
        )

        assertEquals(
            listOf("late", "early"),
            TodoSchedule.tasksForDate(
                listOf(early, otherDay, late),
                date = today,
                today = today,
                zone = zone,
                mode = TodoSortMode.IMPORTANCE,
                ordering = ordering,
            ).map { it.id },
        )
        assertEquals(
            listOf("early", "late"),
            TodoSchedule.tasksForDate(
                listOf(early, otherDay, late),
                date = today,
                today = today,
                zone = zone,
                mode = TodoSortMode.MANUAL,
                ordering = ordering.copy(mode = TodoSortMode.MANUAL),
            ).map { it.id },
        )
        // 日期模式即使带有排序快照也不改变裁剪后的当天顺序。
        assertEquals(
            listOf("early", "late"),
            TodoSchedule.tasksForDate(
                listOf(early, otherDay, late),
                date = today,
                today = today,
                zone = zone,
                ordering = ordering,
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
        completedAt: Long? = if (completed) 0 else null,
    ) = TodoTask(
        id = id.ifEmpty { UUID.randomUUID().toString() },
        title = id,
        completed = completed,
        createdAt = 0,
        updatedAt = 0,
        completedAt = completedAt,
        reminderAt = reminderAt,
        reminderFiredAt = null,
        revision = 1,
        dueAt = dueAt,
    )
}
