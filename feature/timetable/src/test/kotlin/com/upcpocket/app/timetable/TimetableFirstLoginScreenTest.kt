package com.upcpocket.app.timetable

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.platform.crypto.ByteCrypto
import com.upcpocket.app.plugin.ui.LocalPluginPageActive
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class TimetableFirstLoginScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val directory = java.nio.file.Files.createTempDirectory("timetable-first-login")
        .toFile()
    private val account = FakeAccount()
    private fun service(
        transport: ProgrammedTransport,
    ): TimetableService = TimetableService(
        account = account,
        backend = TimetableApiTestBackendSource(transport, clock = { 1_700_000_000_000 }),
        sessionStore = TimetableSessionStore(directory, TestCrypto()),
        cacheStore = TimetableCacheStore(directory),
        clock = { 1_700_000_000_000 },
        todayProvider = { java.time.LocalDate.parse("2026-10-28") },
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private fun service(backend: TimetableBackendSource): TimetableService = TimetableService(
        account = account,
        backend = backend,
        sessionStore = TimetableSessionStore(directory, TestCrypto()),
        cacheStore = TimetableCacheStore(directory),
        clock = { 1_700_000_000_000 },
        todayProvider = { java.time.LocalDate.parse("2026-10-28") },
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun hostTodoSlotRendersWithTheTimetablePageContent() {
        val viewModel = TimetableViewModel(
            service(ProgrammedTransport { error("slot test must not query the school") }),
        )
        rule.setContent {
            CompositionLocalProvider(
                // 预加载/非活动页不启动课表查询；槽位仍由宿主装配并渲染。
                LocalPluginPageActive provides false,
                LocalTimetableTodoSlot provides { Text("合并日程槽位") },
            ) {
                MaterialTheme {
                    TimetableScreen(
                        viewModel = viewModel,
                        onOpenAccountSettings = {},
                    )
                }
            }
        }

        rule.onNodeWithText("课表").assertIsDisplayed()
        rule.onNodeWithText("合并日程槽位").assertIsDisplayed()
    }

    @Test
    fun timetableModulesCanBeReorderedByLongPressDrag() {
        val viewModel = TimetableViewModel(
            service(ProgrammedTransport { error("reorder test must not query the school") }),
        )
        var order by mutableStateOf(TimetableModuleId.DEFAULT_ORDER)
        rule.setContent {
            CompositionLocalProvider(
                LocalPluginPageActive provides false,
                LocalTimetableTodoSlot provides {
                    Box(Modifier.fillMaxWidth().height(90.dp)) {
                        Text("合并日程槽位")
                    }
                },
                LocalTimetableModuleLayout provides TimetableModuleLayout(
                    order = order,
                    reorderEnabled = true,
                    onOrderChange = { order = it },
                ),
            ) {
                MaterialTheme {
                    TimetableScreen(
                        viewModel = viewModel,
                        onOpenAccountSettings = {},
                    )
                }
            }
        }

        val initialTodoTop = rule.onNodeWithTag("timetable_module_todo_schedule")
            .fetchSemanticsNode().boundsInRoot.top
        val initialTimetableTop = rule.onNodeWithTag("timetable_module_timetable")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(initialTodoTop < initialTimetableTop)

        rule.onNodeWithTag("timetable_module_todo_schedule").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            moveBy(Offset(0f, height * 2.2f), delayMillis = 120L)
            up()
        }
        rule.waitForIdle()

        assertEquals(
            listOf(TimetableModuleId.TIMETABLE, TimetableModuleId.TODO_SCHEDULE),
            order,
        )
        val reorderedTodoTop = rule.onNodeWithTag("timetable_module_todo_schedule")
            .fetchSemanticsNode().boundsInRoot.top
        val reorderedTimetableTop = rule.onNodeWithTag("timetable_module_timetable")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("拖放后周课表模块应位于日程待办上方", reorderedTimetableTop < reorderedTodoTop)
    }

    @Test
    fun coursesOnlyHidesLocalAgendaAndKeepsSelectedWeekAndRequests() {
        var requests = 0
        val transport = ProgrammedTransport { request ->
            requests++
            when {
                request.method == "GET" ->
                    json(javaClass.getResource("/timetable_index.json")!!.readText())
                request.body.orEmpty().contains("week=9") ->
                    json(javaClass.getResource("/timetable_week_09_empty.json")!!.readText())
                else ->
                    json(javaClass.getResource("/timetable_week_08.json")!!.readText())
            }
        }
        val service = service(transport)
        val viewModel = TimetableViewModel(service)
        val today = LocalDate.now(TIMETABLE_ZONE)
        val agenda = FakeAgenda(
            listOf(
                agendaEntry("a-today", "今日本机日程", today, 9),
                agendaEntry("a-week", "周内本机日程", LocalDate.parse("2026-10-28"), 9),
            ),
        )
        var mode by mutableStateOf<TimetableDisplayMode?>(TimetableDisplayMode.ALL)
        val modeChanges = mutableListOf<TimetableDisplayMode>()
        rule.setContent {
            CompositionLocalProvider(
                LocalTimetableTodoSlot provides { Text("合并日程槽位") },
                LocalTimetablePersonalAgenda provides agenda,
                LocalTimetableDisplayPreference provides TimetableDisplayPreference(
                    mode = mode,
                    onModeChange = {
                        modeChanges += it
                        mode = it
                    },
                ),
            ) {
                MaterialTheme {
                    TimetableScreen(viewModel = viewModel, onOpenAccountSettings = {})
                }
            }
        }
        rule.waitUntil(5_000) {
            service.state.value.weekState == TimetableWeekState.READY
        }
        rule.waitForIdle()

        // 「全部」第 8 周：实际周网格、个人日程卡、待办槽位、日期标记与日期入口都可用。
        rule.onNode(
            hasContentDescription("课程详情 示例课程A", substring = true) and
                hasContentDescription("第6节、第7节", substring = true),
        ).assertExists()
        rule.onNodeWithTag("timetable_personal_agenda").assertExists()
        rule.onNodeWithText("合并日程槽位").assertIsDisplayed()
        rule.onNodeWithTag("timetable_day_agenda_2026-10-28", useUnmergedTree = true)
            .assertExists()
        rule.onNodeWithTag("timetable_day_header_2026-10-28")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        val allModeRequests = requests

        // 同一有课周切到「仅看课程」：待办、日程卡、日期标记和日期点击全部撤下，
        // 课程、日期表头与选周保留，且切换本身不产生学校请求。
        // 分段按钮在 Robolectric 下不接收注入触摸（独立最小用例可复现），这里直接
        // 触发按钮自身暴露的 OnClick 语义动作，回调与真实点按相同。
        rule.onNodeWithText("仅看课程").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(TimetableDisplayMode.COURSES_ONLY, modeChanges.last())
        rule.onNodeWithTag("timetable_personal_agenda").assertDoesNotExist()
        rule.onNodeWithText("合并日程槽位").assertDoesNotExist()
        rule.onNodeWithTag("timetable_day_agenda_2026-10-28", useUnmergedTree = true)
            .assertDoesNotExist()
        rule.onNode(
            hasContentDescription("课程详情 示例课程A", substring = true) and
                hasContentDescription("第6节、第7节", substring = true),
        ).assertExists()
        rule.onNodeWithText("第 8 周").assertExists()
        rule.onNodeWithTag("timetable_day_header_2026-10-28")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        assertEquals(allModeRequests, requests)

        // 切回「全部」：待办回到原位置，日程卡与标记恢复，仍停在第 8 周。
        rule.onNodeWithText("全部").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(TimetableDisplayMode.ALL, modeChanges.last())
        rule.onNodeWithText("合并日程槽位").assertExists()
        rule.onNodeWithTag("timetable_personal_agenda").assertExists()
        rule.onNodeWithTag("timetable_day_agenda_2026-10-28", useUnmergedTree = true)
            .assertExists()
        val todoTop = rule.onNodeWithTag("timetable_module_todo_schedule")
            .fetchSemanticsNode().boundsInRoot.top
        val timetableTop = rule.onNodeWithTag("timetable_module_timetable")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("切回全部后日程待办应回到周课表上方", todoTop < timetableTop)
        rule.onNodeWithText("第 8 周").assertExists()
        assertEquals(allModeRequests, requests)

        // 手选第 9 周后再切换：保留选周与课程状态，切换仍不额外请求。选周同样走
        // 语义点击，避免 Robolectric 的弹层触摸注入差异影响断言。
        rule.onNodeWithText("第 8 周").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("第 9 周")
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) {
            service.state.value.selectedWeek == 9 &&
                service.state.value.weekState == TimetableWeekState.EMPTY
        }
        rule.waitForIdle()
        val week9Requests = requests
        assertTrue(week9Requests > allModeRequests)
        rule.onNodeWithText("本周无课程").assertExists()

        rule.onNodeWithText("仅看课程").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("第 9 周").assertExists()
        rule.onNodeWithText("本周无课程").assertExists()
        assertEquals(week9Requests, requests)

        rule.onNodeWithText("全部").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("第 9 周").assertExists()
        assertEquals(week9Requests, requests)
    }

    private fun agendaEntry(
        id: String,
        title: String,
        date: LocalDate,
        startHour: Int,
    ): TimetableAgendaEntry = TimetableAgendaEntry(
        id = id,
        title = title,
        startAt = date.atTime(startHour, 0).atZone(TIMETABLE_ZONE).toInstant().toEpochMilli(),
        endAt = date.atTime(startHour + 1, 0).atZone(TIMETABLE_ZONE).toInstant().toEpochMilli(),
    )

    private class FakeAgenda(
        private val entries: List<TimetableAgendaEntry>,
    ) : TimetablePersonalAgenda {
        @Composable
        override fun windowState(window: TimetableAgendaWindow): TimetableAgendaState =
            TimetableAgendaState.Ready(
                entries.filter {
                    it.startAt < window.untilExclusive && it.endAt > window.fromInclusive
                },
            )

        override fun openCreate(date: LocalDate) = Unit

        override fun openEdit(id: String) = Unit
    }

    @Test
    fun backendChallengeOpensOfficialPageWithoutCreatingLocalSchoolSession() {
        val source = object : TimetableBackendSource {
            override suspend fun index(): TimetableBackendIndex =
                throw TimetableBackendFailure(TimetableBackendFailureCode.INTERACTIVE_CHALLENGE)

            override suspend fun week(
                year: String,
                term: String,
                week: Int,
            ): TimetableBackendWeek = error("challenge must stop before week query")
        }
        val service = service(source)
        val viewModel = TimetableViewModel(service)
        rule.setContent {
            MaterialTheme {
                TimetableScreen(
                    viewModel = viewModel,
                    onOpenAccountSettings = {},
                )
            }
        }
        rule.waitUntil(5_000) {
            service.state.value.connection == TimetableConnection.INTERACTION_REQUIRED
        }
        rule.onNodeWithText("打开学校官方页").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) {
            service.state.value.interactionUrl != null
        }
        rule.onNodeWithText("网页登录").assertIsDisplayed()

        rule.onNodeWithText("返回设置").performClick()
        assertEquals(TimetableConnection.INTERACTION_REQUIRED, service.state.value.connection)
        assertTrue(service.state.value.terms.isEmpty())
        assertEquals(
            null,
            TimetableSessionStore(directory, TestCrypto()).load("acct_synthetic_1", 1),
        )
    }

    @Test
    fun accountSwitchDropsOldDetailAndLoginDialog() {
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(javaClass.getResource("/timetable_index.json")!!.readText())
            } else {
                json(javaClass.getResource("/timetable_week_08.json")!!.readText())
            }
        }
        val service = service(transport)
        val viewModel = TimetableViewModel(service)
        rule.setContent {
            MaterialTheme {
                TimetableScreen(
                    viewModel = viewModel,
                    onOpenAccountSettings = {},
                )
            }
        }
        rule.waitUntil(5_000) {
            service.state.value.weekState == TimetableWeekState.READY &&
                service.state.value.connection == TimetableConnection.CONNECTED
        }
        rule.waitForIdle()
        rule.onNode(
            hasContentDescription("课程详情 示例课程A", substring = true) and
                hasContentDescription("第6节、第7节", substring = true),
        )
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("日期").assertExists()
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithContentDescription("课表说明").performClick()
        rule.onNodeWithText("网页登录").performClick()
        rule.onNodeWithText("网页登录").assertIsDisplayed()

        account.publishOther()
        rule.waitUntil(5_000) {
            service.state.value.accountEpoch > 0 && service.state.value.terms.isEmpty()
        }
        rule.onNodeWithText("日期").assertDoesNotExist()
        rule.onNodeWithText("打开学校官方页").assertDoesNotExist()
        rule.onNodeWithText("校方托管账号已绑定，点击右上角刷新读取课表。")
            .assertIsDisplayed()
        rule.onNodeWithContentDescription("刷新课表").performClick()
        rule.waitUntil(5_000) {
            service.state.value.connection == TimetableConnection.CONNECTED &&
                service.state.value.weekState == TimetableWeekState.READY
        }
        rule.onNodeWithText("校方托管账号已绑定，点击右上角刷新读取课表。")
            .assertDoesNotExist()
    }

    private fun json(body: String): TimetableHttpResponse = TimetableHttpResponse(
        200,
        mapOf("content-type" to listOf("application/json")),
        body.toByteArray(),
    )

    private inner class ProgrammedTransport(
        private val handler: suspend (TimetableHttpRequest) -> TimetableHttpResponse,
    ) : TimetableHttpTransport {
        override suspend fun execute(request: TimetableHttpRequest): TimetableHttpResponse {
            return handler(request)
        }
    }

    private class FakeAccount : SchoolAccountReferenceReader {
        private val _reference = MutableStateFlow(
            SchoolAccountReference(
                SchoolAccountId("acct_synthetic_1"),
                AccountGeneration(1),
            ),
        )
        private val _state = MutableStateFlow(
            SchoolAccountState(
                status = SchoolAccountStatus.MANAGED_ACTIVE,
                generation = AccountGeneration(1),
                maskedAccount = "20••••01",
                savedAtEpochMillis = 1,
            ),
        )
        override val state: StateFlow<SchoolAccountState> = _state
        override val accountReference: StateFlow<SchoolAccountReference?> = _reference

        fun publishOther() {
            _reference.value = SchoolAccountReference(
                SchoolAccountId("acct_synthetic_2"),
                AccountGeneration(2),
            )
            _state.value = SchoolAccountState(
                status = SchoolAccountStatus.MANAGED_ACTIVE,
                generation = AccountGeneration(2),
                maskedAccount = "20••••02",
                savedAtEpochMillis = 2,
            )
        }
    }

    private class TestCrypto : ByteCrypto {
        override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
        override fun decrypt(stored: ByteArray): ByteArray = stored.reversedArray()
    }
}
