package com.upcpocket.app.todo.reminder

import android.app.AlarmManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TodoIntentsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    // 两条合法 UUID，Java String.hashCode 相同；旧实现会共用 requestCode/通知 id。
    private val collisionA = "47ef2254-ebd1-4c07-9a5e-022143e6ab61"
    private val collisionB = "5235b5d5-d2ef-4431-829c-b670e3e11dfb"

    @Test
    fun `old action token keeps its revision after new revision token is built`() {
        val old = TodoIntents.action(context, TodoIntents.ACTION_COMPLETE, collisionA, 1)
        val fresh = TodoIntents.action(context, TodoIntents.ACTION_COMPLETE, collisionA, 2)

        assertNotSame(old, fresh)
        assertEquals(1, shadowOf(old).savedIntent.getIntExtra(TodoIntents.EXTRA_REVISION, -1))
        assertEquals(2, shadowOf(fresh).savedIntent.getIntExtra(TodoIntents.EXTRA_REVISION, -1))
        assertNotEquals(
            shadowOf(old).savedIntent.data,
            shadowOf(fresh).savedIntent.data,
        )
    }

    @Test
    fun `colliding uuid hashes keep action identities separate`() {
        assertEquals(collisionA.hashCode(), collisionB.hashCode())
        val a = TodoIntents.action(context, TodoIntents.ACTION_SNOOZE, collisionA, 1)
        val b = TodoIntents.action(context, TodoIntents.ACTION_SNOOZE, collisionB, 1)

        assertNotSame(a, b)
        assertNotEquals(shadowOf(a).savedIntent.data, shadowOf(b).savedIntent.data)
        assertEquals(
            collisionB,
            shadowOf(b).savedIntent.getStringExtra(TodoIntents.EXTRA_TASK_ID),
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `colliding uuid hashes keep scheduled alarms and cancel isolated`() {
        assertEquals(collisionA.hashCode(), collisionB.hashCode())
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            10_000,
            TodoIntents.alarm(context, collisionA, 1),
        )
        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            20_000,
            TodoIntents.alarm(context, collisionB, 1),
        )
        assertEquals(2, shadowOf(manager).scheduledAlarms.size)

        TodoIntents.cancelAlarm(context, collisionA)

        val remaining = shadowOf(manager).scheduledAlarms.single()
        assertEquals(
            collisionB,
            shadowOf(remaining.operation).savedIntent
                .getStringExtra(TodoIntents.EXTRA_TASK_ID),
        )
    }
}
