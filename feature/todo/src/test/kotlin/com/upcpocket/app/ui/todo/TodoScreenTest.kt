package com.upcpocket.app.ui.todo

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.plugin.ui.LocalPluginPageActive
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.todo.TODO_ROUTE
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.reminder.TodoReminderAccess
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], qualifiers = "w411dp-h891dp")
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
    fun `home completion undo is immediate and only reopens the same revision`() {
        val store = FakeTodoStore(listOf(task("Open", createdAt = 10)))
        rule.setContent {
            MaterialTheme { TodoHomeSection(store = store, host = FakeHost()) }
        }

        rule.onNodeWithContentDescription("完成：Open").performClick()
        rule.waitUntil(5_000) { store.current.single().completed }
        rule.onNodeWithTag("todo_home_undo").assertIsDisplayed()
        assertEquals(1, store.revisionUpdates)

        rule.onNodeWithText("撤销").performClick()
        rule.waitUntil(5_000) { !store.current.single().completed }
        assertEquals(3, store.current.single().revision)
        assertEquals(2, store.revisionUpdates)
    }

    @Test
    fun `full list completion offers revision guarded undo`() {
        val store = FakeTodoStore(listOf(task("Open", createdAt = 10)))
        val completionGate = CompletableDeferred<Unit>()
        store.completionGate = completionGate
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithContentDescription("标记完成：Open").performClick()
        rule.onNodeWithContentDescription("标记未完成：Open").assertIsDisplayed()
        assertTrue(!store.current.single().completed)
        completionGate.complete(Unit)
        rule.waitUntil(5_000) { store.current.single().completed }
        rule.onNodeWithText("撤销").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { !store.current.single().completed }

        assertEquals(3, store.current.single().revision)
        assertEquals(2, store.revisionUpdates)
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

    @Test
    fun `editing deadline and clearing reminder saves them independently`() {
        val original = task(
            title = "实验报告",
            createdAt = 10,
            reminderAt = System.currentTimeMillis() + 86_400_000,
        )
        val store = FakeTodoStore(listOf(original))
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithText("实验报告").performClick()
        rule.onNodeWithTag("todo_due_date").performScrollTo().assertIsDisplayed()
        performClickSemantics("todo_due_date")
        waitForTag("todo_due_date_confirm")
        rule.onNodeWithTag("todo_due_date_confirm").assertIsDisplayed()
        performClickSemantics("todo_due_date_confirm")
        rule.onNodeWithTag("todo_due_time").performScrollTo().assertIsDisplayed()
        performClickSemantics("todo_due_time")
        rule.onNodeWithText("选择截止时间").assertIsDisplayed()
        waitForTag("todo_due_time_confirm")
        performClickSemantics("todo_due_time_confirm")
        rule.onNodeWithTag("todo_reminder_switch").performScrollTo().assertIsDisplayed()
        performClickSemantics("todo_reminder_switch")
        performClickSemantics("todo_save")

        rule.waitUntil(5_000) { store.current.single().revision == original.revision + 1 }
        assertTrue(store.current.single().dueAt != null)
        assertNull(store.current.single().reminderAt)
    }

    @Test
    fun `editor drafts restore independently without turning an edit into a create`() {
        val zone = ZoneId.systemDefault()
        val dueAt = LocalDate.of(2035, 6, 7)
            .atTime(16, 30)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val reminderAt = LocalDate.now(zone).plusYears(5)
            .atTime(9, 45)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val original = task(
            title = "原始标题",
            createdAt = 10,
            reminderAt = reminderAt,
            dueAt = dueAt,
        )
        val store = FakeTodoStore(listOf(original))
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithText("原始标题").performClick()
        rule.onNodeWithTag("todo_title_input").performTextReplacement("恢复后的草稿")
        rule.onNodeWithTag("todo_clear_due").performScrollTo().assertIsDisplayed()
        performClickSemantics("todo_clear_due")
        rule.onNodeWithTag("todo_due_value").assertTextEquals("不设截止")

        rule.onNodeWithTag("todo_reminder_switch").performScrollTo().assertIsDisplayed()
        performClickSemantics("todo_reminder_switch")
        rule.waitForIdle()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("todo_reminder_date").fetchSemanticsNodes().isEmpty()
        }
        performClickSemantics("todo_reminder_switch")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("todo_reminder_date").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()

        val reminderDateDraft = textForTag("todo_reminder_date")
        val reminderTimeDraft = textForTag("todo_reminder_time")
        assertTrue(
            reminderDateDraft != TodoFormat.date(reminderAt, zone) ||
                reminderTimeDraft != TodoFormat.clock(reminderAt, zone),
        )
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        assertEquals("恢复后的草稿", editableTextForTag("todo_title_input"))
        rule.onNodeWithTag("todo_due_value").assertTextEquals("不设截止")
        rule.onNodeWithTag("todo_reminder_date").assertTextEquals(reminderDateDraft)
        rule.onNodeWithTag("todo_reminder_time").assertTextEquals(reminderTimeDraft)
        performClickSemantics("todo_save")

        rule.waitUntil(5_000) { store.current.single().revision == original.revision + 1 }
        val saved = store.current.single()
        assertEquals(original.id, saved.id)
        assertEquals("恢复后的草稿", saved.title)
        assertNull(saved.dueAt)
        val savedReminderAt = requireNotNull(saved.reminderAt)
        assertTrue(savedReminderAt != reminderAt)
        assertEquals(reminderDateDraft, TodoFormat.date(savedReminderAt, zone))
        assertEquals(reminderTimeDraft, TodoFormat.clock(savedReminderAt, zone))
        assertEquals(0, store.addCalls)
    }

    @Test
    fun `active page consumes one create request and closing it does not reopen`() {
        val store = FakeTodoStore(emptyList())
        val requestId = "11111111-1111-4111-8111-111111111111"
        store.openCreate(requestId)
        val active = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalPluginPageActive provides active.value) {
                MaterialTheme { TodoScreen(store = store) }
            }
        }

        rule.waitForIdle()
        assertEquals(0, store.createRequestConsumptions)
        assertEquals(requestId, store.pendingCreateRequest.value)
        rule.onNodeWithText("新增待办").assertDoesNotExist()

        rule.runOnIdle { active.value = true }
        rule.waitForIdle()

        rule.waitUntil(5_000) { store.createRequestConsumptions == 1 }
        rule.onNodeWithText("新增待办").assertIsDisplayed()
        rule.onNodeWithTag("todo_editor_cancel").performScrollTo().assertIsDisplayed()
        performClickSemantics("todo_editor_cancel")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        rule.onNodeWithText("新增待办").assertDoesNotExist()
        assertEquals(1, store.createRequestConsumptions)
    }

    @Test
    fun `pending open for a deleted task clears without showing stale editor`() {
        val store = FakeTodoStore(emptyList())
        store.openTask(UUID.randomUUID().toString())
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.waitUntil(5_000) { store.pendingOpenTask.value == null }
        rule.onNodeWithText("编辑待办").assertDoesNotExist()
    }

    @Test
    fun `schedule section defaults to today and keeps an explicit date across restore`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.parse("2026-11-20")
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        fun at(date: String, hour: Int): Long = LocalDate.parse(date)
            .atTime(hour, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val store = FakeTodoStore(
            listOf(
                task("逾期", createdAt = 10, dueAt = at("2026-11-19", 9)),
                task("今天截止", createdAt = 20, dueAt = at("2026-11-20", 18)),
                task("明天", createdAt = 30, dueAt = at("2026-11-21", 9)),
                task("未定时", createdAt = 40),
            ),
        )
        var openedAll = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                TodoScheduleSection(
                    store = store,
                    onOpenAll = { openedAll++ },
                    zone = zone,
                    clock = { now },
                )
            }
        }

        rule.onNodeWithText("今天 · 11月20日").assertIsDisplayed()
        rule.onNodeWithText("3 项未完成").assertIsDisplayed()
        rule.onNodeWithText("逾期").assertIsDisplayed()
        rule.onNodeWithText("今天截止").assertIsDisplayed()
        rule.onNodeWithText("未定时").assertIsDisplayed()
        rule.onNodeWithText("明天").assertDoesNotExist()

        rule.onNodeWithTag("todo_schedule_day_2026-11-21").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("11月21日 周六").assertIsDisplayed()
        rule.onNodeWithText("明天").assertIsDisplayed()
        rule.onNodeWithText("逾期").assertDoesNotExist()

        rule.onNodeWithTag("todo_schedule_day_2026-11-17").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("11月17日 周二").assertIsDisplayed()
        rule.onNodeWithText("这天没有待办。").assertIsDisplayed()
        rule.onNodeWithText("另有 1 项未设置时间。").assertIsDisplayed()

        rule.onNodeWithTag("todo_schedule_next_week").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("11月24日 周二").assertIsDisplayed()
        rule.onNodeWithTag("todo_schedule_today").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("今天 · 11月20日").assertIsDisplayed()

        rule.onNodeWithTag("todo_schedule_day_2026-11-21").performClick()
        rule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithText("11月21日 周六").assertIsDisplayed()
        rule.onNodeWithText("明天").assertIsDisplayed()
        rule.onNodeWithText("回到今天").assertIsDisplayed()

        rule.onNodeWithTag("todo_schedule_all").performClick()
        assertEquals(1, openedAll)
    }

    @Test
    fun `schedule completion stays inline and each row undoes independently`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.parse("2026-11-20")
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val store = FakeTodoStore(
            listOf(
                task("任务甲", createdAt = 10),
                task("任务乙", createdAt = 20),
            ),
            completionAt = now,
        )
        val gate = CompletableDeferred<Unit>()
        store.completionReturnGate = gate
        rule.setContent {
            MaterialTheme {
                TodoScheduleSection(
                    store = store,
                    onOpenAll = {},
                    zone = zone,
                    clock = { now },
                )
            }
        }

        rule.onNodeWithText("2 项未完成").assertIsDisplayed()
        val first = store.current.first { it.title == "任务甲" }
        val second = store.current.first { it.title == "任务乙" }
        val firstTop = scheduleRowTop(first.id)
        val secondTop = scheduleRowTop(second.id)
        assertTrue(firstTop != secondTop)

        // 甲已写入并回流，但服务尚未完成提醒协调时仍保持锁定；乙独立完成。
        rule.onNodeWithContentDescription("标记完成：任务甲").performClick()
        rule.waitUntil(5_000) { store.current.first { it.id == first.id }.completed }
        rule.onNodeWithContentDescription("标记未完成：任务甲")
            .assertIsDisplayed()
            .assertIsNotEnabled()

        rule.onNodeWithContentDescription("标记完成：任务乙").performClick()
        rule.waitUntil(5_000) { store.current.first { it.id == second.id }.completed }
        waitForEnabledContentDescription("标记未完成：任务乙")
        rule.onNodeWithContentDescription("标记未完成：任务乙")
            .assertIsDisplayed()
            .assertIsEnabled()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("0 项未完成").fetchSemanticsNodes().isNotEmpty()
        }

        gate.complete(Unit)
        waitForEnabledContentDescription("标记未完成：任务甲")
        rule.onNodeWithContentDescription("标记未完成：任务乙").assertIsDisplayed()
        assertEquals(firstTop, scheduleRowTop(first.id), 0.5f)
        assertEquals(secondTop, scheduleRowTop(second.id), 0.5f)
        assertTrue(rule.onAllNodesWithTag("todo_schedule_undo").fetchSemanticsNodes().isEmpty())

        rule.onNodeWithContentDescription("标记未完成：任务甲").performClick()
        rule.waitUntil(5_000) { !store.current.first { it.id == first.id }.completed }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("1 项未完成").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("标记完成：任务甲").assertIsDisplayed()
        rule.onNodeWithContentDescription("标记未完成：任务乙").assertIsDisplayed()

        rule.onNodeWithContentDescription("标记未完成：任务乙").performClick()
        rule.waitUntil(5_000) { !store.current.first { it.id == second.id }.completed }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("2 项未完成").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("标记完成：任务乙").assertIsDisplayed()
        assertEquals(firstTop, scheduleRowTop(first.id), 0.5f)
        assertEquals(secondTop, scheduleRowTop(second.id), 0.5f)
        assertEquals(3, store.current.first { it.id == first.id }.revision)
        assertEquals(3, store.current.first { it.id == second.id }.revision)
        assertEquals(4, store.revisionUpdates)
    }

    @Test
    fun `schedule rejected completion restores the row and reports`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.parse("2026-11-20")
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val store = FakeTodoStore(
            listOf(task("未写入", createdAt = 10)),
            completionAt = now,
        )
        store.rejectNextCompletion = true
        rule.setContent {
            MaterialTheme {
                TodoScheduleSection(
                    store = store,
                    onOpenAll = {},
                    zone = zone,
                    clock = { now },
                )
            }
        }

        rule.onNodeWithContentDescription("标记完成：未写入").performClick()
        waitForEnabledContentDescription("标记完成：未写入")
        rule.onNodeWithText("待办已变化，未能更新").assertIsDisplayed()
        assertFalse(store.current.single().completed)
        assertEquals(0, store.revisionUpdates)
    }

    @Test
    fun `schedule completion write exception restores the row and asks to retry`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.parse("2026-11-20")
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val store = FakeTodoStore(
            listOf(task("写入异常", createdAt = 10)),
            completionAt = now,
        )
        store.throwNextCompletion = true
        rule.setContent {
            MaterialTheme {
                TodoScheduleSection(
                    store = store,
                    onOpenAll = {},
                    zone = zone,
                    clock = { now },
                )
            }
        }

        rule.onNodeWithContentDescription("标记完成：写入异常").performClick()
        waitForEnabledContentDescription("标记完成：写入异常")
        rule.onNodeWithText("待办未能更新，请重试").assertIsDisplayed()
        assertFalse(store.current.single().completed)
        assertEquals(0, store.revisionUpdates)
    }

    @Test
    fun `schedule add and row open the existing editor`() {
        val store = FakeTodoStore(listOf(task("可编辑", createdAt = 10)))
        rule.setContent {
            MaterialTheme { TodoScheduleSection(store = store, onOpenAll = {}) }
        }

        rule.onNodeWithTag("todo_schedule_add").performClick()
        rule.onNodeWithText("新增待办").assertIsDisplayed()
        rule.onNodeWithTag("todo_editor_cancel").performScrollTo().assertIsDisplayed()
        performClickSemantics("todo_editor_cancel")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithText("新增待办").assertDoesNotExist()

        val taskId = store.current.single().id
        rule.onNodeWithTag("todo_schedule_task_$taskId").performClick()
        rule.onNodeWithText("编辑待办").assertIsDisplayed()
    }

    @Test
    fun `schedule editor delete offers inline restore`() {
        val store = FakeTodoStore(listOf(task("可删除", createdAt = 10)))
        rule.setContent {
            MaterialTheme { TodoScheduleSection(store = store, onOpenAll = {}) }
        }

        val taskId = store.current.single().id
        rule.onNodeWithTag("todo_schedule_task_$taskId").performClick()
        rule.onNodeWithText("编辑待办").assertIsDisplayed()
        rule.onNodeWithTag("todo_editor_delete").performScrollTo()
        performClickSemantics("todo_editor_delete")
        rule.waitUntil(5_000) { store.current.isEmpty() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithText("编辑待办").assertDoesNotExist()
        rule.onNodeWithTag("todo_schedule_undo").assertIsDisplayed()
        rule.onNodeWithText("已删除").assertIsDisplayed()

        rule.onNodeWithText("撤销").performClick()
        rule.waitUntil(5_000) { store.current.size == 1 }
        assertEquals("可删除", store.current.single().title)
        assertEquals(2, store.current.single().revision)
    }

    @Test
    fun `schedule loading is not shown as an empty store`() {
        val store = FakeTodoStore(emptyList(), taskFlow = MutableSharedFlow())
        rule.setContent {
            MaterialTheme { TodoScheduleSection(store = store, onOpenAll = {}) }
        }

        rule.onNodeWithText("正在读取待办").assertIsDisplayed()
        rule.onNodeWithText("暂无待办").assertDoesNotExist()
    }

    @Test
    fun `schedule read failure is not shown as an empty store`() {
        val store = FakeTodoStore(
            emptyList(),
            taskFlow = flow { throw IllegalStateException("synthetic read failure") },
        )
        rule.setContent {
            MaterialTheme { TodoScheduleSection(store = store, onOpenAll = {}) }
        }

        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("待办读取失败。").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("待办读取失败。").assertIsDisplayed()
        rule.onNodeWithText("暂无待办").assertDoesNotExist()
    }

    @Test
    fun `schedule valid empty store is explicit`() {
        val store = FakeTodoStore(emptyList())
        rule.setContent {
            MaterialTheme { TodoScheduleSection(store = store, onOpenAll = {}) }
        }

        rule.onNodeWithText("暂无待办").assertIsDisplayed()
        rule.onNodeWithText("添加一条").assertIsDisplayed()
        rule.onNodeWithText("待办读取失败。").assertDoesNotExist()
    }

    private fun task(
        title: String,
        createdAt: Long,
        completed: Boolean = false,
        reminderAt: Long? = null,
        dueAt: Long? = null,
    ) = TodoTask(
        id = UUID.randomUUID().toString(),
        title = title,
        completed = completed,
        createdAt = createdAt,
        updatedAt = createdAt,
        completedAt = if (completed) createdAt else null,
        reminderAt = reminderAt,
        reminderFiredAt = null,
        revision = 1,
        dueAt = dueAt,
    )

    private fun scheduleRowTop(taskId: String): Float =
        rule.onNodeWithTag("todo_schedule_task_$taskId").fetchSemanticsNode().boundsInRoot.top

    private fun waitForEnabledContentDescription(description: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        }
    }

    private fun textForTag(tag: String): String = rule.onNodeWithTag(tag)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.Text)
        .orEmpty()
        .joinToString(separator = "") { it.text }

    private fun editableTextForTag(tag: String): String = rule.onNodeWithTag(tag)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.EditableText)
        ?.text
        .orEmpty()

    private fun performClickSemantics(tag: String) {
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FakeHost : PluginSectionHost {
        var opened: PluginRouteId? = null

        override fun openSchoolAccountSettings() = Unit

        override fun open(route: PluginRouteId) {
            opened = route
        }
    }

    private class FakeTodoStore(
        initial: List<TodoTask>,
        taskFlow: Flow<List<TodoTask>>? = null,
        private val completionAt: Long = 2_000L,
    ) : TodoStore {
        private val state = MutableStateFlow(initial)
        override val tasks: Flow<List<TodoTask>> = taskFlow ?: state
        override val pendingOpenTask = MutableStateFlow<String?>(null)
        override val pendingCreateRequest = MutableStateFlow<String?>(null)
        val current: List<TodoTask> get() = state.value
        private var access = TodoReminderAccess(
            notificationsEnabled = true,
            exactAlarmsAllowed = true,
        )
        var revisionUpdates = 0
        var completionGate: CompletableDeferred<Unit>? = null
        var completionReturnGate: CompletableDeferred<Unit>? = null
        var rejectNextCompletion = false
        var throwNextCompletion = false
        var createRequestConsumptions = 0
        var addCalls = 0

        override fun reminderAccess(): TodoReminderAccess = access

        override suspend fun add(title: String, reminderAt: Long?): TodoTask =
            add(title, reminderAt, dueAt = null)

        override suspend fun add(title: String, reminderAt: Long?, dueAt: Long?): TodoTask {
            addCalls++
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
                dueAt = dueAt,
            )
            state.value = state.value + task
            return task
        }

        override suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean {
            val task = state.value.firstOrNull { it.id == id } ?: return false
            return edit(id, title, reminderAt, task.dueAt)
        }

        override suspend fun edit(id: String, title: String, reminderAt: Long?, dueAt: Long?): Boolean {
            val task = state.value.firstOrNull { it.id == id } ?: return false
            state.value = state.value.map {
                if (it.id == id) {
                    it.copy(
                        title = title,
                        reminderAt = reminderAt,
                        reminderFiredAt = if (task.reminderAt == reminderAt) {
                            task.reminderFiredAt
                        } else {
                            null
                        },
                        dueAt = dueAt,
                        revision = it.revision + 1,
                    )
                } else {
                    it
                }
            }
            return true
        }

        override suspend fun setCompleted(id: String, completed: Boolean): Boolean {
            state.value = state.value.map {
                if (it.id == id) {
                    it.copy(
                        completed = completed,
                        completedAt = if (completed) completionAt else null,
                        updatedAt = completionAt,
                        revision = it.revision + 1,
                    )
                } else {
                    it
                }
            }
            return true
        }

        override suspend fun setCompletedIfRevision(
            id: String,
            revision: Int,
            completed: Boolean,
        ): TodoTask? {
            completionGate?.also { completionGate = null }?.await()
            if (rejectNextCompletion) {
                rejectNextCompletion = false
                return null
            }
            if (throwNextCompletion) {
                throwNextCompletion = false
                throw IllegalStateException("synthetic write failure")
            }
            val currentTask = state.value.firstOrNull { it.id == id } ?: return null
            if (currentTask.revision != revision || currentTask.completed == completed) return null
            val updated = currentTask.copy(
                completed = completed,
                completedAt = if (completed) completionAt else null,
                updatedAt = completionAt,
                revision = currentTask.revision + 1,
            )
            revisionUpdates++
            state.value = state.value.map { if (it.id == id) updated else it }
            completionReturnGate?.also { completionReturnGate = null }?.await()
            return updated
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

        override fun openCreate(): String {
            val requestId = UUID.randomUUID().toString()
            pendingCreateRequest.value = requestId
            return requestId
        }

        override fun openCreate(requestId: String): Boolean {
            pendingCreateRequest.value = requestId
            return true
        }

        override fun consumeCreateRequest() {
            createRequestConsumptions++
            pendingCreateRequest.value = null
        }

        override fun ensureNotificationChannel() = Unit

        override suspend fun reconcileAll() = Unit
    }
}
