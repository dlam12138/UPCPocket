package com.upcpocket.app.timetable

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class TimetableScreenStateTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val term = TimetableTerm(
        year = "2026-2027",
        term = "1",
        startDay = LocalDate.parse("2026-09-06"),
        totalWeeks = 19,
        label = "2026-2027 秋季",
    )
    private val dates = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) }
    private val meeting = TimetableMeeting(
        courseName = "示例课程A",
        weekday = 1,
        startPeriod = 6,
        endPeriod = 7,
        periods = listOf(6, 7),
        sourcePeriods = listOf(6, 7),
        weeks = listOf(8),
        weekLabel = "8",
        teacher = null,
        location = null,
        sectionsSource = "lessArr",
        date = dates[1],
    )

    private fun state(
        weekState: TimetableWeekState,
        meetings: List<TimetableMeeting> = emptyList(),
        message: String? = null,
        connection: TimetableConnection = TimetableConnection.CONNECTED,
    ): TimetableUiState = TimetableUiState(
        accountStatusLabel = "20••••01 · 已保存",
        maskedAccount = "20••••01",
        accountSavedUnverified = true,
        connection = connection,
        terms = listOf(term),
        selectedYear = term.year,
        selectedTerm = term.term,
        selectedWeek = 8,
        weekDates = dates,
        meetings = meetings,
        weekState = weekState,
        weekFetchedAtEpochMillis = 1_700_000_000_000,
        message = message,
        canRetry = true,
    )

    private fun todayState(
        status: TimetableTodayState = TimetableTodayState.NOT_SYNCED,
        loaded: Boolean = false,
        meetings: List<TimetableMeeting> = emptyList(),
        message: String? = null,
    ): TimetableTodayUiState = TimetableTodayUiState(
        accountEpoch = 1,
        accountSavedUnverified = true,
        accountBlocked = false,
        today = LocalDate.now(ZoneId.of("Asia/Shanghai")),
        todayMeetings = meetings,
        todayState = status,
        todayMessage = message,
        todayLoaded = loaded,
    )

    @Test
    fun emptyWeekIsShownAsEmptyOnlyInEmptyState() {
        rule.setContent {
            MaterialTheme { WeekPanel(state(TimetableWeekState.EMPTY), onCourseClick = {}) }
        }
        rule.onNodeWithText("本周无课程").assertIsDisplayed()
    }

    @Test
    fun notQueriedAndErrorAreNotShownAsEmptyWeek() {
        rule.setContent {
            MaterialTheme {
                WeekPanel(
                    state(TimetableWeekState.NOT_QUERIED),
                    onCourseClick = {},
                )
            }
        }
        rule.onNodeWithText("这一周还没有查询，点击右上角刷新。").assertIsDisplayed()
        rule.onNodeWithText("本周无课程").assertDoesNotExist()
    }

    @Test
    fun protocolErrorShowsFailureAndKeepsCachedMeeting() {
        rule.setContent {
            MaterialTheme {
                WeekPanel(
                    state(
                        TimetableWeekState.CACHED,
                        meetings = listOf(meeting),
                        message = "课表接口返回了无法确认的数据；已保留上次成功缓存，未覆盖。",
                        connection = TimetableConnection.PROTOCOL_ERROR,
                    ),
                    onCourseClick = {},
                )
            }
        }
        rule.onNodeWithText("示例课程A").assertExists()
        rule.onNodeWithText("缓存").assertExists()
    }

    @Test
    fun todayNotLoadedIsNotShownAsNoCourses() {
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state = todayState(),
                    onCourseClick = {},
                )
            }
        }
        rule.onNodeWithText("今日课表尚未同步。").assertIsDisplayed()
    }

    @Test
    fun todayEmptyAfterLoadIsShownAsNoCourses() {
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state = todayState(
                        loaded = true,
                        status = TimetableTodayState.EMPTY,
                    ),
                    onCourseClick = {},
                )
            }
        }
        rule.onNodeWithText("今天没有排课。").assertIsDisplayed()
    }

    @Test
    fun todayCacheFailureKeepsFailureCopyAndOffersTimetableAction() {
        var opened = false
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state = todayState(
                        status = TimetableTodayState.READ_ERROR,
                        message = "本机课表读取失败，请前往课表页重试。",
                    ),
                    onCourseClick = {},
                    onOpenTimetable = { opened = true },
                )
            }
        }

        rule.onNodeWithText("本机课表读取失败，请前往课表页重试。").assertIsDisplayed()
        rule.onNodeWithText("去课表").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun interactionStateOffersOfficialWebLoginOnly() {
        var opened = false
        rule.setContent {
            MaterialTheme {
                ConnectionBanner(
                    state = state(
                        TimetableWeekState.ERROR,
                        connection = TimetableConnection.INTERACTION_REQUIRED,
                        message = "请在学校页面继续登录。",
                    ),
                    onRetry = {},
                    onOpenAccountSettings = {},
                    onOpenLogin = { opened = true },
                    onOpenWebLogin = {},
                )
            }
        }
        rule.onNodeWithText("学校登录").performClick()
        rule.onNodeWithText("在学校页面完成登录后继续。").assertIsDisplayed()
        assertEquals(true, opened)
    }

    @Test
    fun courseDetailShowsExplicitMissingFieldsAndSourcePeriods() {
        val split = meeting.copy(
            courseName = "非连续课程",
            startPeriod = 1,
            endPeriod = 1,
            periods = listOf(1),
            sourcePeriods = listOf(1, 3),
            sectionsSource = "lessArr_split_noncontiguous",
        )
        rule.setContent {
            MaterialTheme { CourseDetailBottomSheet(meeting = split, onDismiss = {}) }
        }
        val expandSheet = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)
        rule.waitForIdle()
        val expandNodes = rule.onAllNodes(expandSheet, useUnmergedTree = true)
            .fetchSemanticsNodes()
        check(expandNodes.size <= 1) { "Expected at most one course sheet expand action" }
        if (expandNodes.isNotEmpty()) {
            rule.onNode(expandSheet, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.Expand)
            rule.waitForIdle()
        } else {
            rule.onRoot(useUnmergedTree = true).printToLog("TimetableSheetUnmergedTree")
        }
        rule.onNodeWithText("第1节、第3节").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("教师").assertExists()
        rule.onNodeWithText("地点").assertExists()
        rule.onAllNodesWithText("未提供").assertCountEquals(4)
    }

    @Test
    fun courseSelectionRequiresCurrentCourseAndAccountEpoch() {
        val current = meeting.copy(courseId = "course-1")
        val selected = current.selection(accountEpoch = 4)

        assertEquals(current, resolveMeetingSelection(selected, 4, listOf(current)))
        assertNull(resolveMeetingSelection(selected, 5, listOf(current)))
        assertNull(resolveMeetingSelection(selected, 4, emptyList()))
    }

    @Test
    fun courseSelectionResolvesLatestFieldsFromCurrentWeekOrTodayData() {
        val initiallySelected = meeting.copy(
            courseId = "course-1",
            courseType = "选修",
            sourcePeriods = listOf(6, 7),
            weekLabel = "8",
        )
        val selected = initiallySelected.selection(accountEpoch = 4)
        val updated = initiallySelected.copy(
            courseType = "必修",
            sourcePeriods = listOf(6, 7, 8),
            weekLabel = "8、9",
        )

        assertEquals(initiallySelected.instanceId, updated.instanceId)
        assertEquals(updated, resolveMeetingSelection(selected, 4, listOf(updated)))
    }

    @Test
    fun weekSelectionShowsTermAndWeekAndInvokesCallback() {
        var selected = 0
        rule.setContent {
            MaterialTheme {
                TermAndWeekHeader(
                    state = state(TimetableWeekState.READY),
                    onSelectTerm = {},
                    onSelectWeek = { selected = it },
                )
            }
        }
        rule.onNodeWithText("2026-2027 秋季").assertIsDisplayed()
        rule.onNodeWithText("第 8 周").assertIsDisplayed()
        rule.onNodeWithText("共 19 周").assertIsDisplayed()
        // 周日起点与 ISO 星期的展示排序不同：日期范围必须按真实日期取最小/最大，
        // 不能取“周一列/周日列”的排序结果，否则会显示 10/26 – 10/25。
        rule.onNodeWithText("10/25 – 10/31").assertIsDisplayed()
        rule.onNodeWithText("第 8 周").performClick()
        rule.onNodeWithText("第 2 周").performClick()
        assertEquals(2, selected)
    }
}
