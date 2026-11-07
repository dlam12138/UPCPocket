package com.upcpocket.app.todo.reminder

import com.upcpocket.app.todo.data.TodoTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoReminderPlannerTest {

    private val now = 1_000_000L

    @Test
    fun `completed no reminder and fired tasks need no plan`() {
        assertPlan(TodoReminderPlan.Nothing, task(completed = true))
        assertPlan(TodoReminderPlan.Nothing, task(reminder = null))
        assertPlan(TodoReminderPlan.Nothing, task(reminder = now + 10, fired = now - 1))
    }

    @Test
    fun `future reminder is exact only when special access is available`() {
        val reminder = now + 60_000
        assertEquals(
            TodoReminderPlan.Schedule(reminder, exact = true),
            TodoReminderPlanner.plan(task(reminder = reminder), now, access(exact = true)),
        )
        assertEquals(
            TodoReminderPlan.Schedule(reminder, exact = false),
            TodoReminderPlanner.plan(task(reminder = reminder), now, access(exact = false)),
        )
    }

    @Test
    fun `past reminder notifies once only when notifications are enabled`() {
        assertEquals(
            TodoReminderPlan.NotifyNow(now - 60_000),
            TodoReminderPlanner.plan(task(reminder = now - 60_000), now, access(notifications = true)),
        )
        assertEquals(
            TodoReminderPlan.Blocked(TodoReminderBlockReason.NOTIFICATIONS_DISABLED),
            TodoReminderPlanner.plan(task(reminder = now - 60_000), now, access(notifications = false)),
        )
    }

    @Test
    fun `rebuild keeps one plan per task and ignores stale revisions`() {
        val future = task(id = "future", reminder = now + 5_000)
        val done = task(id = "done", reminder = now + 5_000, completed = true)
        val plans = TodoReminderPlanner.rebuild(
            listOf(future, done),
            now,
            access(exact = true),
        )
        assertEquals(2, plans.size)
        assertTrue(plans.single { it.first.id == "future" }.second is TodoReminderPlan.Schedule)
        assertEquals(TodoReminderPlan.Nothing, plans.single { it.first.id == "done" }.second)

        assertTrue(TodoReminderPlanner.acceptsAlarm(future, future.revision))
        assertFalse(TodoReminderPlanner.acceptsAlarm(future.copy(revision = 2), future.revision))
        assertFalse(TodoReminderPlanner.acceptsAlarm(future.copy(reminderFiredAt = now), future.revision))
        assertTrue(TodoReminderPlanner.acceptsAction(future, future.revision))
        assertFalse(TodoReminderPlanner.acceptsAction(future.copy(completed = true), future.revision))
    }

    private fun assertPlan(expected: TodoReminderPlan, task: TodoTask) {
        assertEquals(expected, TodoReminderPlanner.plan(task, now, access()))
    }

    private fun access(
        notifications: Boolean = true,
        exact: Boolean = true,
    ) = TodoReminderAccess(notificationsEnabled = notifications, exactAlarmsAllowed = exact)

    private fun task(
        id: String = "task",
        completed: Boolean = false,
        reminder: Long? = now,
        fired: Long? = null,
    ) = TodoTask(
        id = id,
        title = "合成待办",
        completed = completed,
        createdAt = 1,
        updatedAt = 1,
        completedAt = if (completed) 1 else null,
        reminderAt = reminder,
        reminderFiredAt = fired,
        revision = 1,
    )
}
