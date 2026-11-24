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
import androidx.compose.ui.test.assertTextContains
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
import com.upcpocket.app.todo.PersonalSchedule
import com.upcpocket.app.todo.PersonalScheduleStore
import com.upcpocket.app.todo.TODO_ROUTE
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.PersonalScheduleTime
import com.upcpocket.app.todo.data.TodoDeletedTask
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoImportance
import com.upcpocket.app.todo.data.TodoMoveDirection
import com.upcpocket.app.todo.data.TodoOrdering
import com.upcpocket.app.todo.data.TodoOrderingSnapshot
import com.upcpocket.app.todo.data.TodoSnapshot
import com.upcpocket.app.todo.data.TodoSortMode
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.data.TodoTaskOrder
import com.upcpocket.app.todo.reminder.TodoReminderAccess
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    fun `legacy store does not present sort or importance controls`() {
        val store = FakeTodoStore(listOf(task("旧任务", createdAt = 10)))
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithText("旧任务").assertIsDisplayed()
        rule.onNodeWithTag("todo_sort_modes").assertDoesNotExist()
        rule.onNodeWithText("旧任务").performClick()
        rule.onNodeWithText("编辑待办").assertIsDisplayed()
        rule.onNodeWithTag("todo_importance").assertDoesNotExist()
    }

    @Test
    fun `ordered editor loads importance and saves an importance only change`() {
        val original = task("分级任务", createdAt = 10)
        val store = FakeTodoStore(
            listOf(original),
            orderingEnabled = true,
            initialOrdering = TodoOrderingSnapshot(
                mode = TodoSortMode.DATE,
                orders = mapOf(
                    original.id to TodoTaskOrder(original.id, TodoImportance.LOW),
                ),
            ),
        )
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithTag("todo_sort_modes").assertIsDisplayed()
        rule.onNodeWithText("分级任务").performClick()
        waitForTag("todo_importance")
        rule.onNodeWithText("高").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        performClickSemantics("todo_save")

        rule.waitUntil(5_000) { store.lastSetImportance == TodoImportance.HIGH }
        assertEquals(original.revision, store.current.single().revision)
        assertEquals(0, store.contentEdits)
        assertEquals(TodoImportance.HIGH, store.orderingSnapshot.importanceOf(original.id))
    }

    @Test
    fun `stale editor saves only importance after an external reminder update`() {
        val reminderAt = System.currentTimeMillis() + 86_400_000
        val original = task("旧表单", createdAt = 10, reminderAt = reminderAt)
        val store = FakeTodoStore(
            listOf(original),
            orderingEnabled = true,
            initialOrdering = TodoOrderingSnapshot(
                orders = mapOf(
                    original.id to TodoTaskOrder(original.id, TodoImportance.NORMAL),
                ),
            ),
        )
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithText("旧表单").performClick()
        waitForTag("todo_importance")
        // 通知里的“稍后提醒”在表单打开后写入新提醒与 revision。
        val snoozedAt = reminderAt + 3_600_000
        store.applyExternalReminder(original.id, snoozedAt)
        rule.waitForIdle()

        rule.onNodeWithText("高").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        performClickSemantics("todo_save")

        rule.waitUntil(5_000) { store.lastSetImportance == TodoImportance.HIGH }
        assertEquals(0, store.contentEdits)
        val saved = store.current.single()
        assertEquals(snoozedAt, saved.reminderAt)
        assertEquals(2, saved.revision)
        assertEquals(TodoImportance.HIGH, store.orderingSnapshot.importanceOf(original.id))
    }

    @Test
    fun `delete keeps undo delivery when the task leaves the list before returning`() {
        val original = task("删除后撤销", createdAt = 10)
        val store = FakeTodoStore(
            listOf(original),
            orderingEnabled = true,
            initialOrdering = TodoOrderingSnapshot(
                mode = TodoSortMode.MANUAL,
                orders = mapOf(
                    original.id to TodoTaskOrder(
                        original.id,
                        TodoImportance.HIGH,
                        manualRank = 3,
                    ),
                ),
            ),
        )
        val gate = CompletableDeferred<Unit>()
        store.deleteReturnGate = gate
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithText("删除后撤销").performClick()
        rule.onNodeWithText("编辑待办").assertIsDisplayed()
        rule.onNodeWithTag("todo_editor_delete").performScrollTo().assertIsDisplayed()
        performClickSemantics("todo_editor_delete")

        // Room 回流先移除任务，编辑器已经离场；删除快照此时还在服务中。
        rule.waitUntil(5_000) { store.current.isEmpty() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithText("编辑待办").assertDoesNotExist()
        assertNull(store.lastDeleted)

        gate.complete(Unit)
        rule.waitUntil(5_000) { store.lastDeleted != null }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("已删除").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("撤销").performClick()

        rule.waitUntil(5_000) { store.current.size == 1 }
        assertEquals(original.id, store.lastRestored?.task?.id)
        assertEquals(TodoImportance.HIGH, store.lastRestored?.importance)
        assertEquals(3, store.lastRestored?.manualRank)
        assertEquals(2, store.current.single().revision)
        assertEquals(TodoImportance.HIGH, store.orderingSnapshot.importanceOf(original.id))
        assertEquals(3, store.orderingSnapshot.manualRankOf(original.id))
    }

    @Test
    fun `late delete delivery does not close a newly opened editor`() {
        val original = task("先删除", createdAt = 10)
        val store = FakeTodoStore(listOf(original))
        val gate = CompletableDeferred<Unit>()
        store.deleteReturnGate = gate
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithText("先删除").performClick()
        rule.onNodeWithTag("todo_editor_delete").performScrollTo().assertIsDisplayed()
        performClickSemantics("todo_editor_delete")
        rule.waitUntil(5_000) { store.current.isEmpty() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNodeWithText("编辑待办").assertDoesNotExist()

        // 删除快照尚未回传时，用户已经开始新增下一条。
        rule.onNodeWithTag("todo_add").performClick()
        rule.onNodeWithText("新增待办").assertIsDisplayed()

        gate.complete(Unit)
        rule.waitUntil(5_000) { store.lastDeleted != null }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("已删除").fetchSemanticsNodes().isNotEmpty()
        }
        // 迟到的删除收尾不得清掉新面板。
        rule.onNodeWithText("新增待办").assertIsDisplayed()
    }

    @Test
    fun `manual controls move the displayed neighbor and disable at group bounds`() {
        val first = task("甲", createdAt = 10)
        val second = task("乙", createdAt = 20)
        val store = FakeTodoStore(
            listOf(first, second),
            orderingEnabled = true,
            initialOrdering = TodoOrderingSnapshot(
                mode = TodoSortMode.MANUAL,
                orders = mapOf(
                    first.id to TodoTaskOrder(first.id, TodoImportance.NORMAL, 1),
                    second.id to TodoTaskOrder(second.id, TodoImportance.NORMAL, 2),
                ),
            ),
        )
        rule.setContent { MaterialTheme { TodoScreen(store = store) } }

        rule.onNodeWithTag("todo_move_up_${first.id}").assertIsNotEnabled()
        rule.onNodeWithTag("todo_move_down_${second.id}").assertIsNotEnabled()
        rule.onNodeWithTag("todo_move_down_${first.id}").performClick()

        rule.waitUntil(5_000) {
            store.orderingSnapshot.manualRankOf(first.id) == 2 &&
                store.orderingSnapshot.manualRankOf(second.id) == 1
        }
        assertEquals(Triple(first.id, TodoMoveDirection.DOWN, second.id), store.lastMove)
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("todo_move_up_${first.id}").fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        }
    }

    @Test
    fun `schedule add shares the type chooser and prefills the selected date`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.parse("2026-11-20")
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val todoStore = FakeTodoStore(emptyList())
        val scheduleStore = FakePersonalScheduleStore()

        rule.setContent {
            MaterialTheme {
                TodoScheduleSection(
                    store = todoStore,
                    onOpenAll = {},
                    zone = zone,
                    clock = { now },
                    personalScheduleStore = scheduleStore,
                )
            }
        }

        rule.onNodeWithTag("todo_schedule_day_2026-11-21").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("todo_schedule_add").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("新建").assertIsDisplayed()

        performClickSemantics("agenda_type_todo")
        rule.waitForIdle()
        rule.onNodeWithText("新增待办").assertIsDisplayed()
        val expectedDue = LocalDate.parse("2026-11-21")
            .atTime(9, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        rule.onNodeWithTag("todo_due_value")
            .assertTextEquals(TodoFormat.editorTime(expectedDue, zone))
        performClickSemantics("todo_editor_cancel")
        rule.waitForIdle()

        rule.onNodeWithTag("todo_schedule_add").performClick()
        rule.waitForIdle()
        performClickSemantics("agenda_type_schedule")
        rule.waitForIdle()
        rule.onNodeWithText("新增日程").assertIsDisplayed()
        assertEquals(
            2,
            rule.onAllNodesWithText("2026年11月21日").fetchSemanticsNodes().size,
        )
        rule.onNodeWithText("09:00").assertIsDisplayed()
        rule.onNodeWithText("10:00").assertIsDisplayed()

        rule.onNodeWithTag("schedule_title_input").performTextInput("组会")
        performClickSemantics("schedule_save")
        rule.waitForIdle()

        val added = requireNotNull(scheduleStore.lastAdded)
        assertEquals("组会", added.title)
        assertEquals(shanghaiMillis("2026-11-21T09:00"), added.startAt)
        assertEquals(shanghaiMillis("2026-11-21T10:00"), added.endAt)
        rule.onNodeWithTag("todo_schedule_item_${added.id}").assertIsDisplayed()
        rule.onNodeWithText("组会").assertIsDisplayed()
        rule.onNodeWithText("09:00–10:00").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("设备时间").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `schedule row shows the device day fragment and editor restores shanghai time`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val now = LocalDate.parse("2026-11-25")
            .atTime(12, 0)
            .atZone(tokyo)
            .toInstant()
            .toEpochMilli()
        val schedule = PersonalSchedule(
            id = "cross-day",
            title = "跨日日程",
            startAt = shanghaiMillis("2026-11-24T23:30"),
            endAt = shanghaiMillis("2026-11-24T23:50"),
            location = "图书馆",
            note = null,
            revision = 1,
        )
        val scheduleStore = FakePersonalScheduleStore(listOf(schedule))

        rule.setContent {
            MaterialTheme {
                TodoScheduleSection(
                    store = FakeTodoStore(emptyList()),
                    onOpenAll = {},
                    zone = tokyo,
                    clock = { now },
                    personalScheduleStore = scheduleStore,
                )
            }
        }

        rule.onNodeWithText("跨日日程").assertIsDisplayed()
        rule.onNodeWithText("00:30–00:50").assertIsDisplayed()
        rule.onNodeWithText("设备时间").assertIsDisplayed()

        rule.onNodeWithTag("todo_schedule_item_cross-day").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("编辑日程").assertIsDisplayed()
        rule.onNodeWithTag("schedule_title_input").assertTextContains("跨日日程")
        rule.onNodeWithText("23:30").assertIsDisplayed()
        rule.onNodeWithText("23:50").assertIsDisplayed()
        assertEquals(
            2,
            rule.onAllNodesWithText("2026年11月24日").fetchSemanticsNodes().size,
        )
        performClickSemantics("schedule_cancel")
        rule.waitForIdle()
        rule.onNodeWithText("编辑日程").assertDoesNotExist()
    }

    @Test
    fun `schedule editor restores draft and selected date after recreation`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.parse("2026-11-20")
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                TodoScheduleSection(
                    store = FakeTodoStore(emptyList()),
                    onOpenAll = {},
                    zone = zone,
                    clock = { now },
                    personalScheduleStore = FakePersonalScheduleStore(),
                )
            }
        }

        rule.onNodeWithTag("todo_schedule_day_2026-11-21").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("todo_schedule_add").performClick()
        rule.waitForIdle()
        performClickSemantics("agenda_type_schedule")
        rule.waitForIdle()
        rule.onNodeWithTag("schedule_title_input").performTextInput("草稿日程")

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        rule.onNodeWithText("11月21日 周六").assertIsDisplayed()
        rule.onNodeWithTag("schedule_title_input").assertTextContains("草稿日程")
        assertEquals(
            2,
            rule.onAllNodesWithText("2026年11月21日").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `schedule list distinguishes read failure from a true empty day`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.parse("2026-11-20")
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val scheduleStore = FakePersonalScheduleStore(failObserve = true)

        rule.setContent {
            MaterialTheme {
                TodoScheduleSection(
                    store = FakeTodoStore(emptyList()),
                    onOpenAll = {},
                    zone = zone,
                    clock = { now },
                    personalScheduleStore = scheduleStore,
                )
            }
        }

        rule.onNodeWithText("日程读取失败。").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("这天没有日程。").fetchSemanticsNodes().isEmpty(),
        )

        scheduleStore.failObserve = false
        rule.onNodeWithTag("todo_schedule_schedule_retry").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("这天没有日程。").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("日程读取失败。").fetchSemanticsNodes().isEmpty(),
        )
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
        // 流先移除任务、服务后返回快照时，日程宿主仍应收到撤销数据。
        val gate = CompletableDeferred<Unit>()
        store.deleteReturnGate = gate
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
        assertNull(store.lastDeleted)
        gate.complete(Unit)
        rule.waitUntil(5_000) { store.lastDeleted != null }
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

    private fun shanghaiMillis(text: String): Long =
        requireNotNull(PersonalScheduleTime.resolve(LocalDateTime.parse(text)))

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
        private val orderingEnabled: Boolean = false,
        initialOrdering: TodoOrderingSnapshot = TodoOrderingSnapshot.Default,
    ) : TodoStore {
        private val state = MutableStateFlow(initial)
        private val ordering = MutableStateFlow(initialOrdering)
        override val tasks: Flow<List<TodoTask>> = taskFlow ?: state
        override val supportsOrdering: Boolean = orderingEnabled
        override val snapshot: Flow<TodoSnapshot> = if (orderingEnabled) {
            combine(tasks, ordering) { list, order -> TodoSnapshot(tasks = list, ordering = order) }
        } else {
            tasks.map { TodoSnapshot(tasks = it) }
        }
        override val pendingOpenTask = MutableStateFlow<String?>(null)
        override val pendingCreateRequest = MutableStateFlow<String?>(null)
        val current: List<TodoTask> get() = state.value
        val orderingSnapshot: TodoOrderingSnapshot get() = ordering.value

        /** 模拟通知“稍后提醒”等编辑器之外的任务更新。 */
        fun applyExternalReminder(id: String, reminderAt: Long) {
            state.value = state.value.map {
                if (it.id == id) {
                    it.copy(
                        reminderAt = reminderAt,
                        reminderFiredAt = null,
                        revision = it.revision + 1,
                    )
                } else {
                    it
                }
            }
        }
        var lastEditImportance: TodoImportance? = null
        var lastAddedImportance: TodoImportance? = null
        var lastSetImportance: TodoImportance? = null
        var lastMove: Triple<String, TodoMoveDirection, String>? = null
        var lastDeleted: TodoDeletedTask? = null
        var lastRestored: TodoDeletedTask? = null
        var contentEdits = 0
        var deleteReturnGate: CompletableDeferred<Unit>? = null
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

        override suspend fun add(
            title: String,
            reminderAt: Long?,
            dueAt: Long?,
            importance: TodoImportance,
        ): TodoTask {
            lastAddedImportance = importance
            val task = add(title, reminderAt, dueAt)
            if (orderingEnabled) {
                ordering.value = ordering.value.copy(
                    orders = ordering.value.orders + (
                        task.id to TodoTaskOrder(task.id, importance)
                    ),
                )
            }
            return task
        }

        override suspend fun edit(id: String, title: String, reminderAt: Long?): Boolean {
            val task = state.value.firstOrNull { it.id == id } ?: return false
            return edit(id, title, reminderAt, task.dueAt)
        }

        override suspend fun edit(id: String, title: String, reminderAt: Long?, dueAt: Long?): Boolean {
            val task = state.value.firstOrNull { it.id == id } ?: return false
            contentEdits++
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

        override suspend fun edit(
            id: String,
            title: String,
            reminderAt: Long?,
            dueAt: Long?,
            importance: TodoImportance,
        ): Boolean {
            lastEditImportance = importance
            val task = state.value.firstOrNull { it.id == id } ?: return false
            if (orderingEnabled) {
                ordering.value = ordering.value.copy(
                    orders = ordering.value.orders + (
                        id to TodoTaskOrder(id, importance, ordering.value.orders[id]?.manualRank)
                    ),
                )
            }
            if (task.title == title && task.reminderAt == reminderAt && task.dueAt == dueAt) {
                return true
            }
            return edit(id, title, reminderAt, dueAt)
        }

        override suspend fun setImportance(id: String, importance: TodoImportance): Boolean {
            if (!orderingEnabled || state.value.none { it.id == id }) return false
            lastSetImportance = importance
            ordering.value = ordering.value.copy(
                orders = ordering.value.orders + (
                    id to TodoTaskOrder(id, importance, ordering.value.orders[id]?.manualRank)
                ),
            )
            return true
        }

        override suspend fun setSortMode(mode: TodoSortMode): Boolean {
            if (!orderingEnabled) return false
            val current = ordering.value
            if (mode == TodoSortMode.MANUAL) {
                val orders = current.orders.toMutableMap()
                var next = (orders.values.mapNotNull { it.manualRank }.maxOrNull() ?: 0) + 1
                (TodoOrdering.incomplete(state.value, 0) + TodoOrdering.completed(state.value))
                    .forEach { task ->
                        if (orders[task.id]?.manualRank == null) {
                            orders[task.id] = (orders[task.id] ?: TodoTaskOrder(task.id))
                                .copy(manualRank = next)
                            next++
                        }
                    }
                ordering.value = TodoOrderingSnapshot(TodoSortMode.MANUAL, orders)
            } else {
                ordering.value = current.copy(mode = mode)
            }
            return true
        }

        override suspend fun moveTask(
            id: String,
            direction: TodoMoveDirection,
            expectedNeighborId: String,
        ): Boolean {
            if (!orderingEnabled || ordering.value.mode != TodoSortMode.MANUAL) return false
            val target = state.value.firstOrNull { it.id == id } ?: return false
            val neighbor = state.value.firstOrNull { it.id == expectedNeighborId } ?: return false
            if (target.completed != neighbor.completed) return false
            val orders = ordering.value.orders
            val targetRank = orders[id]?.manualRank ?: return false
            val neighborRank = orders[expectedNeighborId]?.manualRank ?: return false
            if (targetRank == neighborRank) return false
            val group = state.value
                .filter { it.completed == target.completed }
                .sortedBy { orders[it.id]?.manualRank ?: Int.MAX_VALUE }
                .map { it.id }
            val index = group.indexOf(id)
            val neighborIndex = group.indexOf(expectedNeighborId)
            val expectedIndex = if (direction == TodoMoveDirection.UP) index - 1 else index + 1
            if (index < 0 || neighborIndex != expectedIndex) return false
            ordering.value = ordering.value.copy(
                orders = orders + (
                    id to orders.getValue(id).copy(manualRank = neighborRank)
                ) + (
                    expectedNeighborId to orders.getValue(expectedNeighborId)
                        .copy(manualRank = targetRank)
                ),
            )
            lastMove = Triple(id, direction, expectedNeighborId)
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

        override suspend fun deleteWithOrder(id: String): TodoDeletedTask? {
            val task = state.value.firstOrNull { it.id == id } ?: return null
            val order = ordering.value.orders[id]
            // 与 Room 回流一致：任务与排序先随 Flow 消失，删除快照随后才返回。
            state.value = state.value.filterNot { it.id == id }
            ordering.value = ordering.value.copy(orders = ordering.value.orders - id)
            deleteReturnGate?.also { deleteReturnGate = null }?.await()
            return TodoDeletedTask(
                task = task,
                importance = if (orderingEnabled) {
                    order?.importance ?: TodoImportance.NORMAL
                } else {
                    TodoImportance.NORMAL
                },
                manualRank = if (orderingEnabled) order?.manualRank else null,
            ).also { lastDeleted = it }
        }

        override suspend fun restoreWithOrder(deleted: TodoDeletedTask): Boolean {
            lastRestored = deleted
            val restored = deleted.task.copy(revision = deleted.task.revision + 1)
            state.value = state.value + restored
            if (orderingEnabled) {
                ordering.value = ordering.value.copy(
                    orders = ordering.value.orders + (
                        restored.id to TodoTaskOrder(
                            restored.id,
                            deleted.importance,
                            deleted.manualRank,
                        )
                    ),
                )
            }
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

    private class FakePersonalScheduleStore(
        initial: List<PersonalSchedule> = emptyList(),
        var failObserve: Boolean = false,
    ) : PersonalScheduleStore {
        private val state = MutableStateFlow(initial)
        var lastAdded: PersonalSchedule? = null
        var rejectNextUpdate = false

        override fun observeWindow(
            fromInclusive: Long,
            untilExclusive: Long,
        ): Flow<List<PersonalSchedule>> =
            if (failObserve) {
                flow<List<PersonalSchedule>> {
                    throw IllegalStateException("synthetic schedule read failure")
                }
            } else {
                state.map { schedules ->
                    schedules
                        .filter { it.startAt < untilExclusive && it.endAt > fromInclusive }
                        .sortedWith(compareBy({ it.startAt }, { it.endAt }, { it.id }))
                }
            }

        override suspend fun byId(id: String): PersonalSchedule? =
            state.value.firstOrNull { it.id == id }

        override suspend fun add(
            title: String,
            startAt: Long,
            endAt: Long,
            location: String?,
            note: String?,
        ): PersonalSchedule {
            if (title.isBlank() || endAt <= startAt) throw IllegalArgumentException()
            val schedule = PersonalSchedule(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                startAt = startAt,
                endAt = endAt,
                location = location?.trim()?.takeIf(String::isNotEmpty),
                note = note?.trim()?.takeIf(String::isNotEmpty),
                revision = 1,
            )
            state.value = state.value + schedule
            lastAdded = schedule
            return schedule
        }

        override suspend fun update(
            id: String,
            expectedRevision: Int,
            title: String,
            startAt: Long,
            endAt: Long,
            location: String?,
            note: String?,
        ): PersonalSchedule? {
            if (rejectNextUpdate) {
                rejectNextUpdate = false
                return null
            }
            val current = byId(id) ?: return null
            if (current.revision != expectedRevision) return null
            val updated = current.copy(
                title = title.trim(),
                startAt = startAt,
                endAt = endAt,
                location = location?.trim()?.takeIf(String::isNotEmpty),
                note = note?.trim()?.takeIf(String::isNotEmpty),
                revision = current.revision + 1,
            )
            state.value = state.value.map { if (it.id == id) updated else it }
            return updated
        }

        override suspend fun delete(id: String, expectedRevision: Int): PersonalSchedule? {
            val current = byId(id) ?: return null
            if (current.revision != expectedRevision) return null
            state.value = state.value.filterNot { it.id == id }
            return current
        }
    }
}
