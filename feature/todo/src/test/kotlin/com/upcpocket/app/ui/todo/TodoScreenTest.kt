package com.upcpocket.app.ui.todo

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.todo.TODO_ROUTE
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.reminder.TodoReminderAccess
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class TodoScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `home summary shows two open tasks and toggles through the same store`() {
        val store = FakeTodoStore(
            listOf(
                task("A", createdAt = 10),
                task("B", createdAt = 20),
                task("C", createdAt = 30),
                task("Done", createdAt = 40, completed = true),
            ),
        )
        val host = FakeHost()
        rule.setContent {
            MaterialTheme { TodoHomeSection(store = store, host = host) }
        }

        rule.onNodeWithText("3 项未完成").assertIsDisplayed()
        rule.onNodeWithText("C").assertIsDisplayed()
        rule.onNodeWithText("B").assertIsDisplayed()
        rule.onNodeWithText("A").assertDoesNotExist()
        rule.onNodeWithText("Done").assertDoesNotExist()

        rule.onNodeWithContentDescription("完成：C").performClick()
        rule.waitForIdle()
        assertTrue(store.current.first { it.title == "C" }.completed)

        rule.onNodeWithText("B").performClick()
        rule.waitForIdle()
        assertEquals(TODO_ROUTE, host.opened)
    }

    @Test
    fun `completed section folds and expands by count`() {
        val store = FakeTodoStore(
            listOf(
                task("Open", createdAt = 10),
                task("Done1", createdAt = 20, completed = true),
                task("Done2", createdAt = 30, completed = true),
            ),
        )
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithText("已完成 2").assertIsDisplayed()
        rule.onNodeWithText("Done1").assertDoesNotExist()
        rule.onNodeWithText("已完成 2").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Done1").assertIsDisplayed()
        rule.onNodeWithText("Done2").assertIsDisplayed()
    }

    @Test
    fun `blank cannot save and ime keeps the add sheet for continuous entry`() {
        val store = FakeTodoStore(emptyList())
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithTag("todo_add").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("新增待办").assertIsDisplayed()
        rule.onNodeWithTag("todo_save").assertIsNotEnabled()

        rule.onNodeWithTag("todo_title_input").performTextInput("第一条")
        rule.onNodeWithTag("todo_title_input").performImeAction()
        rule.waitUntil(5_000) { store.current.size == 1 }
        rule.onNodeWithText("新增待办").assertIsDisplayed()
        assertEquals("第一条", store.current.single().title)

        rule.onNodeWithTag("todo_title_input").performTextInput("第二条")
        rule.onNodeWithTag("todo_title_input").performImeAction()
        rule.waitUntil(5_000) { store.current.size == 2 }
        assertEquals(listOf("第一条", "第二条"), store.current.map { it.title })
    }

    private fun task(
        title: String,
        createdAt: Long,
        completed: Boolean = false,
    ) = TodoTask(
        id = UUID.randomUUID().toString(),
        title = title,
        completed = completed,
        createdAt = createdAt,
        updatedAt = createdAt,
        completedAt = if (completed) createdAt else null,
        reminderAt = null,
        reminderFiredAt = null,
        revision = 1,
    )

    private class FakeHost : PluginSectionHost {
        var opened: PluginRouteId? = null

        override fun openSchoolAccountSettings() = Unit

        override fun open(route: PluginRouteId) {
            opened = route
        }
    }

    private class FakeTodoStore(initial: List<TodoTask>) : TodoStore {
        private val state = MutableStateFlow(initial)
        override val tasks: StateFlow<List<TodoTask>> = state
        override val pendingOpenTask = MutableStateFlow<String?>(null)
        val current: List<TodoTask> get() = state.value
        private var access = TodoReminderAccess(
            notificationsEnabled = true,
            exactAlarmsAllowed = true,
        )

        override fun reminderAccess(): TodoReminderAccess = access

        override suspend fun add(title: String, reminderAt: Long?): TodoTask {
            val now = 1_000L + state.value.size
            val task = TodoTask(
                id = UUID.randomUUID().toString(),
                title = title,
                completed = false,
                createdAt = now,
                updatedAt = now,
                completedAt = null,
                reminderAt = reminderAt,
                reminderFiredAt = null,
                revision = 1,
            )
            state.value = state.value + task
            return task
        }

        override suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean {
            state.value = state.value.map {
                if (it.id == id) it.copy(title = title, reminderAt = reminderAt, revision = it.revision + 1) else it
            }
            return true
        }

        override suspend fun setCompleted(id: String, completed: Boolean): Boolean {
            state.value = state.value.map {
                if (it.id == id) {
                    it.copy(
                        completed = completed,
                        completedAt = if (completed) 2_000 else null,
                        revision = it.revision + 1,
                    )
                } else {
                    it
                }
            }
            return true
        }

        override suspend fun delete(id: String): TodoTask? {
            val task = state.value.firstOrNull { it.id == id } ?: return null
            state.value = state.value.filterNot { it.id == id }
            return task
        }

        override suspend fun restore(task: TodoTask): Boolean {
            state.value = state.value + task.copy(revision = task.revision + 1)
            return true
        }

        override fun openTask(taskId: String) {
            pendingOpenTask.value = taskId
        }

        override fun consumeOpenTask() {
            pendingOpenTask.value = null
        }

        override fun ensureNotificationChannel() = Unit

        override suspend fun reconcileAll() = Unit
    }
}
