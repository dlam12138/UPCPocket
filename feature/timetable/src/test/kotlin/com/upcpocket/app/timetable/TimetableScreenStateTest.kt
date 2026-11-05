package com.upcpocket.app.timetable

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
        todayLoaded: Boolean = false,
        todayMeetings: List<TimetableMeeting> = emptyList(),
    ): TimetableUiState = TimetableUiState(
        accountStatusLabel = "20••••01 · 已保存，尚未验证",
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
        today = LocalDate.now(ZoneId.of("Asia/Shanghai")),
        todayMeetings = todayMeetings,
        todayLoaded = todayLoaded,
        message = message,
        canRetry = true,
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
        rule.onNodeWithText("该教学周尚未查询；切换周会按需查询。").assertIsDisplayed()
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
        rule.onNodeWithText("离线缓存").assertExists()
    }

    @Test
    fun todayNotLoadedIsNotShownAsNoCourses() {
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state(TimetableWeekState.READY, todayLoaded = false),
                    onCourseClick = {},
                )
            }
        }
        rule.onNodeWithText("今日课程尚未查询。").assertIsDisplayed()
    }

    @Test
    fun todayEmptyAfterLoadIsShownAsNoCourses() {
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state(TimetableWeekState.READY, todayLoaded = true),
                    onCourseClick = {},
                )
            }
        }
        rule.onNodeWithText("今天没有排课").assertIsDisplayed()
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
                        message = "学校要求验证码或二次验证。",
                    ),
                    onRetry = {},
                    onOpenAccountSettings = {},
                    onOpenLogin = { opened = true },
                    onOpenWebLogin = {},
                )
            }
        }
        rule.onNodeWithText("打开学校登录页").performClick()
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
            MaterialTheme { CourseDetailDialog(meeting = split, onDismiss = {}) }
        }
        rule.onNodeWithText("第1节、第3节").assertIsDisplayed()
        rule.onNodeWithText("教师").assertExists()
        rule.onNodeWithText("地点").assertExists()
        rule.onAllNodesWithText("未提供").assertCountEquals(6)
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
