package com.upcpocket.app.todo.reminder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.todo.TodoActionReceiver
import com.upcpocket.app.todo.data.TodoTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TodoNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = TodoNotifier(context) { taskId ->
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TodoActionReceiver::class.java).setAction(taskId),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Test
    fun `channel importance none is unavailable and does not post`() {
        shadowOf(manager).setNotificationsEnabled(true)
        notifier.ensureChannel()
        val channel = manager.getNotificationChannel(TODO_CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)

        assertFalse(notifier.post(task("47ef2254-ebd1-4c07-9a5e-022143e6ab61"), 1, 1_000))
        assertTrue(shadowOf(manager).activeNotifications.isEmpty())
    }

    @Test
    fun `hash colliding task notifications use separate tags and cancel independently`() {
        shadowOf(manager).setNotificationsEnabled(true)
        notifier.ensureChannel()
        val idA = "47ef2254-ebd1-4c07-9a5e-022143e6ab61"
        val idB = "5235b5d5-d2ef-4431-829c-b670e3e11dfb"

        assertTrue(notifier.post(task(idA), 1, 1_000))
        assertTrue(notifier.post(task(idB), 1, 1_000))
        val active = shadowOf(manager).activeNotifications
        assertEquals(2, active.size)
        assertEquals(setOf(notificationTag(idA), notificationTag(idB)), active.map { it.tag }.toSet())

        notifier.cancel(idA)
        assertEquals(1, shadowOf(manager).activeNotifications.size)
        assertEquals(notificationTag(idB), shadowOf(manager).activeNotifications.single().tag)
    }

    private fun task(id: String) = TodoTask(
        id = id,
        title = "合成待办",
        completed = false,
        createdAt = 1_000,
        updatedAt = 1_000,
        completedAt = null,
        reminderAt = 2_000,
        reminderFiredAt = null,
        revision = 1,
    )
}
