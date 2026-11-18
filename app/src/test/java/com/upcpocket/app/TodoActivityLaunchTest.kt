package com.upcpocket.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.upcpocket.app.todo.TodoLaunchContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class TodoActivityLaunchTest {

    private val canonicalUuid = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"

    @Test
    fun fullUppercaseUuidIsAcceptedAndNormalizedForOpenAndCreate() {
        val uppercaseUuid = canonicalUuid.uppercase()
        val openIntent = Intent(TodoLaunchContract.ACTION_OPEN)
            .putExtra(TodoLaunchContract.EXTRA_TASK_ID, uppercaseUuid)
        val createIntent = Intent(TodoLaunchContract.ACTION_CREATE)
            .putExtra(TodoLaunchContract.EXTRA_CREATE_REQUEST_ID, uppercaseUuid)

        assertEquals(
            TodoActivityLaunchRequest.OpenTask(canonicalUuid),
            TodoActivityLaunch.fromIntent(openIntent),
        )
        assertEquals(
            TodoActivityLaunchRequest.CreateTask(canonicalUuid),
            TodoActivityLaunch.fromIntent(createIntent),
        )
    }

    @Test
    fun shortUuidIsRejectedForOpenAndCreate() {
        val openIntent = Intent(TodoLaunchContract.ACTION_OPEN)
            .putExtra(TodoLaunchContract.EXTRA_TASK_ID, "1-1-1-1-1")
        val createIntent = Intent(TodoLaunchContract.ACTION_CREATE)
            .putExtra(TodoLaunchContract.EXTRA_CREATE_REQUEST_ID, "1-1-1-1-1")

        assertNull(TodoActivityLaunch.fromIntent(openIntent))
        assertNull(TodoActivityLaunch.fromIntent(createIntent))
    }

    @Test
    fun savedInstanceStatePreventsReplayingTheInitialIntent() {
        assertTrue(TodoActivityLaunch.shouldConsumeOnCreate(null))
        assertFalse(TodoActivityLaunch.shouldConsumeOnCreate(Bundle()))
    }

    @Test
    fun repeatedCreateIntentRemainsConsumableOnEachDelivery() {
        val intent = Intent(TodoLaunchContract.ACTION_CREATE)
            .putExtra(TodoLaunchContract.EXTRA_CREATE_REQUEST_ID, canonicalUuid)
        val expected = TodoActivityLaunchRequest.CreateTask(canonicalUuid)

        assertEquals(expected, TodoActivityLaunch.fromIntent(intent))
        assertEquals(expected, TodoActivityLaunch.fromIntent(intent))
    }
}
