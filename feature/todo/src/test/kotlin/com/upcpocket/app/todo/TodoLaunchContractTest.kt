package com.upcpocket.app.todo

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TodoLaunchContractTest {

    @Test
    fun `create and existing task open requests parse independently`() {
        val id = "11111111-1111-4111-8111-111111111111"
        val openIntent = Intent(TodoLaunchContract.ACTION_OPEN)
            .putExtra(TodoLaunchContract.EXTRA_TASK_ID, id)
        val createIntent = TodoLaunchContract.createRequestIntent(id)

        assertEquals(id, TodoLaunchContract.taskId(openIntent))
        assertNull(TodoLaunchContract.createRequestId(openIntent))
        assertNull(TodoLaunchContract.taskId(createIntent))
        assertEquals(id, TodoLaunchContract.createRequestId(createIntent))
        assertNull(
            TodoLaunchContract.createRequestId(
                Intent(TodoLaunchContract.ACTION_CREATE)
                    .putExtra(TodoLaunchContract.EXTRA_CREATE_REQUEST_ID, "invalid"),
            ),
        )
    }

    @Test
    fun `uuid boundaries require full canonical groups and accept uppercase`() {
        val uppercase = "11111111-1111-4111-8111-11111111ABCD"
        val canonical = uppercase.lowercase()
        val openIntent = Intent(TodoLaunchContract.ACTION_OPEN)
            .putExtra(TodoLaunchContract.EXTRA_TASK_ID, uppercase)
        val createIntent = Intent(TodoLaunchContract.ACTION_CREATE)
            .putExtra(TodoLaunchContract.EXTRA_CREATE_REQUEST_ID, uppercase)
        val abbreviated = "1-1-1-1-1"

        assertEquals(canonical, TodoLaunchContract.taskId(openIntent))
        assertEquals(canonical, TodoLaunchContract.createRequestId(createIntent))
        assertNull(
            TodoLaunchContract.taskId(
                Intent(TodoLaunchContract.ACTION_OPEN).putExtra(TodoLaunchContract.EXTRA_TASK_ID, abbreviated),
            ),
        )
        assertNull(
            TodoLaunchContract.createRequestId(
                Intent(TodoLaunchContract.ACTION_CREATE)
                    .putExtra(TodoLaunchContract.EXTRA_CREATE_REQUEST_ID, abbreviated),
            ),
        )
        assertEquals(
            canonical,
            TodoLaunchContract.createRequestIntent(uppercase)
                .getStringExtra(TodoLaunchContract.EXTRA_CREATE_REQUEST_ID),
        )
        assertTrue(runCatching { TodoLaunchContract.createRequestIntent(abbreviated) }.isFailure)
    }
}
