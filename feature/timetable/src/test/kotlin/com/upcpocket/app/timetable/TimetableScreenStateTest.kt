package com.upcpocket.app.timetable

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertTrue
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

    private fun agendaEntry(
        id: String,
        title: String,
        date: LocalDate,
        startHour: Int,
        endHour: Int,
    ): TimetableAgendaEntry = TimetableAgendaEntry(
        id = id,
        title = title,
        startAt = date.atTime(startHour, 0)
            .atZone(TIMETABLE_ZONE)
            .toInstant()
            .toEpochMilli(),
        endAt = date.atTime(endHour, 0)
            .atZone(TIMETABLE_ZONE)
            .toInstant()
            .toEpochMilli(),
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
            MaterialTheme {
                CourseDetailBottomSheet(meeting = split, onDismiss = {}, onOpenCampusLocation = {})
            }
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
        rule.onNodeWithText("在校园地图查看").assertDoesNotExist()
    }

    @Test
    fun courseDetailHandsOnlySelectedLocationToCampusMap() {
        val selections = mutableListOf<String>()
        var dismissed = 0
        rule.setContent {
            MaterialTheme {
                CourseDetailBottomSheet(
                    meeting = meeting.copy(location = "  南教楼 201  "),
                    onDismiss = { dismissed++ },
                    onOpenCampusLocation = { selections += it },
                )
            }
        }
        val expandSheet = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)
        if (rule.onAllNodes(expandSheet, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(expandSheet, useUnmergedTree = true).performSemanticsAction(SemanticsActions.Expand)
        }
        rule.onNodeWithText("在校园地图查看")
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.runOnIdle {
            assertEquals(listOf("南教楼 201"), selections)
            assertEquals(1, dismissed)
        }
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

    @Test
    fun todayMergedAgendaMarksSourcesAndKeepsUnknownCourseTime() {
        val today = LocalDate.now(TIMETABLE_ZONE)
        val known = meeting.copy(courseName = "示例课程A")
        val unknown = meeting.copy(
            courseName = "时间未知课程",
            startPeriod = 13,
            endPeriod = 13,
            periods = listOf(13),
            sourcePeriods = listOf(13),
        )
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state = todayState(
                        status = TimetableTodayState.READY,
                        loaded = true,
                        meetings = listOf(known, unknown),
                    ),
                    onCourseClick = {},
                    agenda = TimetableAgendaState.Ready(
                        listOf(agendaEntry("a1", "个人安排一", today, 13, 14)),
                    ),
                    onAgendaClick = {},
                    onAgendaCreate = {},
                )
            }
        }

        rule.onNodeWithText("今日安排").assertIsDisplayed()
        rule.onNodeWithText("示例课程A").assertIsDisplayed()
        rule.onNodeWithText("时间未知课程").assertIsDisplayed()
        rule.onNodeWithText("个人安排一").assertIsDisplayed()
        rule.onAllNodesWithText("课程").assertCountEquals(2)
        rule.onNodeWithText("日程").assertIsDisplayed()
        rule.onNodeWithText("时间待确认 · 地点未提供 · 第13节").assertExists()
        val agendaTop = rule.onNodeWithText("个人安排一").fetchSemanticsNode().boundsInRoot.top
        val courseTop = rule.onNodeWithText("示例课程A").fetchSemanticsNode().boundsInRoot.top
        assertTrue("13:00 的本机日程应排在 14:00 的课程之前", agendaTop < courseTop)
    }

    @Test
    fun todayMergedAgendaNeverShowsCoursesFromAnOldDate() {
        val today = LocalDate.now(TIMETABLE_ZONE)
        val stale = todayState(
            status = TimetableTodayState.READY,
            loaded = true,
            meetings = listOf(meeting.copy(courseName = "旧日期课程")),
        ).copy(today = today.minusDays(1))
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state = stale,
                    onCourseClick = {},
                    agenda = TimetableAgendaState.Ready(
                        listOf(agendaEntry("a2", "当天日程", today, 9, 10)),
                    ),
                    onAgendaClick = {},
                    onAgendaCreate = {},
                )
            }
        }

        rule.onNodeWithText("今日课表尚未同步。").assertIsDisplayed()
        rule.onNodeWithText("当天日程").assertIsDisplayed()
        rule.onNodeWithText("旧日期课程").assertDoesNotExist()
    }

    @Test
    fun todayAgendaShowsLocalListWhenSchoolIsNotSynced() {
        val today = LocalDate.now(TIMETABLE_ZONE)
        var created = 0
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state = todayState(),
                    onCourseClick = {},
                    agenda = TimetableAgendaState.Ready(
                        listOf(agendaEntry("a3", "本机安排", today, 9, 10)),
                    ),
                    onAgendaClick = {},
                    onAgendaCreate = { created++ },
                )
            }
        }

        rule.onNodeWithText("今日课表尚未同步。").assertIsDisplayed()
        rule.onNodeWithText("本机安排").assertIsDisplayed()
        rule.onNodeWithText("新增日程").performClick()
        assertEquals(1, created)
    }

    @Test
    fun todayAgendaFailureIsNotShownAsEmpty() {
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state = todayState(status = TimetableTodayState.READY, loaded = true),
                    onCourseClick = {},
                    agenda = TimetableAgendaState.Failed,
                )
            }
        }

        rule.onNodeWithText("本机日程读取失败。").assertIsDisplayed()
        rule.onNodeWithText("今天没有安排。").assertDoesNotExist()
        rule.onNodeWithText("今天没有个人日程。").assertDoesNotExist()
    }

    @Test
    fun todayMergedEmptyIsShownOnlyAfterBothSourcesLoad() {
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state = todayState(status = TimetableTodayState.EMPTY, loaded = true),
                    onCourseClick = {},
                    agenda = TimetableAgendaState.Ready(emptyList()),
                    onAgendaClick = {},
                    onAgendaCreate = {},
                )
            }
        }

        rule.onNodeWithText("今天没有安排。").assertIsDisplayed()
        rule.onNodeWithText("今天没有个人日程。").assertDoesNotExist()
        rule.onNodeWithText("今天没有排课。").assertDoesNotExist()
    }

    @Test
    fun todayAgendaEntryOpensEditorById() {
        val today = LocalDate.now(TIMETABLE_ZONE)
        var opened: String? = null
        rule.setContent {
            MaterialTheme {
                TodayPanel(
                    state = todayState(status = TimetableTodayState.EMPTY, loaded = true),
                    onCourseClick = {},
                    agenda = TimetableAgendaState.Ready(
                        listOf(agendaEntry("a4", "待编辑日程", today, 15, 16)),
                    ),
                    onAgendaClick = { opened = it },
                    onAgendaCreate = {},
                )
            }
        }

        rule.onNodeWithText("待编辑日程").performClick()
        assertEquals("a4", opened)
    }

    @Test
    fun weekHeaderMarksAgendaDateAndOpensDayEntry() {
        var opened: LocalDate? = null
        rule.setContent {
            MaterialTheme {
                WeekPanel(
                    state = state(TimetableWeekState.READY, meetings = listOf(meeting)),
                    onCourseClick = {},
                    daysWithAgenda = setOf(dates[2]),
                    onAgendaDateClick = { opened = it },
                )
            }
        }

        rule.onNodeWithTag(
            "timetable_day_agenda_${dates[2]}",
            useUnmergedTree = true,
        ).assertExists()
        rule.onNodeWithTag("timetable_day_header_${dates[2]}").performClick()
        assertEquals(dates[2], opened)
    }

    @Test
    fun personalAgendaCardNavigatesAndOpensEntry() {
        val day = LocalDate.parse("2026-10-26")
        var selected: LocalDate? = null
        var created: LocalDate? = null
        var edited: String? = null
        rule.setContent {
            MaterialTheme {
                TimetablePersonalAgendaCard(
                    state = TimetableAgendaState.Ready(
                        listOf(agendaEntry("a5", "卡片日程", day, 8, 9)),
                    ),
                    selectedDate = day,
                    nowMinute = null,
                    showShanghaiTime = false,
                    onSelectDate = { selected = it },
                    onCreate = { created = it },
                    onEdit = { edited = it },
                )
            }
        }

        rule.onNodeWithText("卡片日程").assertIsDisplayed()
        rule.onNodeWithText("08:00–09:00").performClick()
        rule.onNodeWithText("新增").performClick()
        rule.onNodeWithTag("timetable_agenda_previous_day").performClick()
        assertEquals("a5", edited)
        assertEquals(day, created)
        assertEquals(day.minusDays(1), selected)
    }
}
