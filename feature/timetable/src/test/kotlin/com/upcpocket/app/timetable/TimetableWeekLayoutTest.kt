package com.upcpocket.app.timetable

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.plugin.ui.LocalDockClearance
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class TimetableWeekLayoutTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `portrait week panel keeps monday through sunday on screen without horizontal drag`() {
        val today = LocalDate.now(TIMETABLE_ZONE)
        val weekStart = today.minusDays((today.dayOfWeek.value % 7).toLong())
        val term = TimetableTerm(
            year = "2026-2027",
            term = "1",
            startDay = weekStart.minusWeeks(9),
            totalWeeks = 19,
            label = "2026-2027 秋季",
        )
        val dates = (0..6).map { weekStart.plusDays(it.toLong()) }
        val state = TimetableUiState(
            accountSavedUnverified = true,
            connection = TimetableConnection.CONNECTED,
            terms = listOf(term),
            selectedYear = term.year,
            selectedTerm = term.term,
            selectedWeek = 10,
            weekDates = dates,
            meetings = emptyList(),
            weekState = TimetableWeekState.READY,
        )

        rule.setContent {
            MaterialTheme {
                Column(
                    Modifier
                        .width(360.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    TermAndWeekHeader(state, onSelectTerm = {}, onSelectWeek = {})
                    WeekPanel(state = state, onCourseClick = {})
                }
            }
        }

        listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六").forEach { label ->
            rule.onNodeWithText(label).assertIsDisplayed()
        }
        rule.onNodeWithText("${today.monthValue}/${today.dayOfMonth}").assertIsDisplayed()
        val sundayLeft = rule.onNodeWithText("周日").fetchSemanticsNode().boundsInRoot.left
        val mondayLeft = rule.onNodeWithText("周一").fetchSemanticsNode().boundsInRoot.left
        assertTrue("周日必须排在周一左侧", sundayLeft < mondayLeft)
        val saturdayRight = rule.onNodeWithText("周六").fetchSemanticsNode().boundsInRoot.right
        val displayDensity = rule.activity.resources.displayMetrics.density
        assertTrue(
            "360dp 默认字号时周六须在左右留白内显示，实际右侧 ${saturdayRight / displayDensity}dp",
            saturdayRight <= 344f * displayDensity + 1f,
        )
        val termBounds = rule.onNodeWithTag("timetable_term_label", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val weekBounds = rule.onNodeWithText("第 10 周", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "360dp 默认字号时学期与选周须处于同一行",
            termBounds.bottom > weekBounds.top && weekBounds.bottom > termBounds.top,
        )
        val slotGridHeight = rule.onNodeWithTag("timetable_day_slots_$weekStart")
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        val headerHeight = rule.onNodeWithTag("timetable_day_header_$weekStart")
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        assertTrue(
            "14 个常规节次应为 14 × 44dp，实际 ${slotGridHeight / displayDensity}dp",
            kotlin.math.abs(slotGridHeight / displayDensity - 14f * 44f) <= 1f,
        )
        assertTrue(
            "星期表头应为 40dp，实际 ${headerHeight / displayDensity}dp",
            kotlin.math.abs(headerHeight / displayDensity - 40f) <= 1f,
        )
        rule.onNodeWithText("1").assertExists()
        rule.onNodeWithText("14").assertExists()
        rule.onNodeWithText("08:00").assertExists()
        rule.onNodeWithText("08:45").assertExists()
        rule.onAllNodesWithText("待确认").assertCountEquals(2)
    }

    @Test
    fun `regular phone shows full term and readable course details through the last period`() {
        val weekStart = LocalDate.of(2026, 11, 15)
        val longCourseName = "计算机组成原理实验"
        val shortCourse = meeting(
            name = "物理",
            date = weekStart,
            startPeriod = 3,
            endPeriod = 4,
            location = "东环301",
        )
        val longCourse = meeting(
            name = longCourseName,
            date = weekStart.plusDays(1),
            startPeriod = 3,
            endPeriod = 4,
            location = "东环301",
        )
        val lastCourse = meeting(
            name = "末节课程",
            date = weekStart.plusDays(6),
            startPeriod = 14,
            endPeriod = 14,
            location = "东环301",
        )
        val state = weekState(weekStart, listOf(shortCourse, longCourse, lastCourse))
        var selected by mutableStateOf<TimetableMeeting?>(null)

        rule.setContent {
            CompositionLocalProvider(LocalDockClearance provides 64.dp) {
                MaterialTheme {
                    val dockClearance = LocalDockClearance.current
                    Column(
                        Modifier
                            .width(360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 6.dp,
                                bottom = 24.dp + dockClearance,
                            ),
                    ) {
                        TermAndWeekHeader(state, onSelectTerm = {}, onSelectWeek = {})
                        WeekPanel(state = state, onCourseClick = { selected = it })
                    }
                    selected?.let {
                        CourseDetailBottomSheet(meeting = it, onDismiss = { selected = null })
                    }
                }
            }
        }

        val term = assertNoVisualOverflow("timetable_term_label")
        assertTrue(term.lineCount <= 2)

        listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        val shortTitle = assertNoVisualOverflow(courseTag(shortCourse, "title"))
        assertTrue("短课程名应完整显示", shortTitle.layoutInput.text.text == "物理")
        val longTitle = textLayout(courseTag(longCourse, "title"))
        val titleDiagnostics = layoutDiagnostics(courseTag(longCourse, "title"), longTitle)
        assertTrue("长标题应按行数明确省略：$titleDiagnostics", longTitle.hasVisualOverflow)
        assertTrue(
            "省略必须由实际布局结果报告：$titleDiagnostics",
            longTitle.didOverflowWidth || longTitle.didOverflowHeight,
        )
        assertTrue(
            "最后一行必须标记为省略：$titleDiagnostics",
            longTitle.isLineEllipsized(longTitle.lineCount - 1),
        )
        assertNoVisualOverflow(courseTag(longCourse, "time"))
        assertNoVisualOverflow(courseTag(longCourse, "location"))
        rule.onNodeWithContentDescription(
            "课程详情 物理，第3节、第4节，上课时间 09:55–11:30，地点 东环301",
        ).assertExists()
        rule.onNodeWithContentDescription(
            "课程详情 $longCourseName，第3节、第4节，上课时间 09:55–11:30，地点 东环301",
        ).performClick()
        rule.onAllNodesWithText(longCourseName, useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("关闭").performClick()

        rule.onNodeWithContentDescription(
            "课程详情 末节课程，第14节，上课时间 时间待确认，地点 东环301",
        ).performScrollTo().performClick()
        rule.onAllNodesWithText("末节课程").assertCountEquals(2)
        rule.onNodeWithText("第14节").assertIsDisplayed()
        rule.onNodeWithText("时间待确认").assertIsDisplayed()
    }

    @Test
    fun `larger text keeps seven dates available through horizontal fallback`() {
        val weekStart = LocalDate.of(2026, 11, 15)
        val longCourseName = "超长课程标题用于验证窄屏摘要明确省略"
        val longCourse = meeting(
            name = longCourseName,
            date = weekStart,
            startPeriod = 3,
            endPeriod = 4,
            location = "文理楼A203",
        )
        val state = weekState(weekStart, listOf(longCourse))
        rule.setContent {
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(systemDensity.density, 1.5f)) {
                MaterialTheme {
                    Column(
                        Modifier
                            .width(360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                    ) {
                        TermAndWeekHeader(state, onSelectTerm = {}, onSelectWeek = {})
                        WeekPanel(state = state, onCourseClick = {})
                    }
                }
            }
        }

        assertNoVisualOverflow("timetable_term_label")
        val title = textLayout(courseTag(longCourse, "title"))
        val titleDiagnostics = layoutDiagnostics(courseTag(longCourse, "title"), title)
        assertTrue("较大字体下长标题摘要需明确显示省略号：$titleDiagnostics", title.hasVisualOverflow)
        assertTrue(
            "溢出必须由实际布局结果报告：$titleDiagnostics",
            title.didOverflowWidth || title.didOverflowHeight,
        )
        assertTrue(
            "最后一行必须标记为省略：$titleDiagnostics",
            title.isLineEllipsized(title.lineCount - 1),
        )
        assertNoVisualOverflow(courseTag(longCourse, "time"))
        assertNoVisualOverflow(courseTag(longCourse, "location"))
        rule.onNodeWithContentDescription(
            "课程详情 $longCourseName，第3节、第4节，上课时间 09:55–11:30，地点 文理楼A203",
        ).assertExists()
        rule.onNodeWithText("周六").assertIsNotDisplayed()
        rule.onNodeWithTag("timetable_week_grid").performTouchInput { swipeLeft() }
        rule.onNodeWithText("周六").assertIsDisplayed()
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getResult ->
                assertTrue("TextLayoutResult should be available for $tag", getResult(results))
            }
        return results.single()
    }

    private fun assertNoVisualOverflow(tag: String): TextLayoutResult {
        val result = textLayout(tag)
        val diagnostics = layoutDiagnostics(tag, result)
        assertFalse("$tag must not overflow its width: $diagnostics", result.didOverflowWidth)
        assertFalse("$tag must not overflow its height: $diagnostics", result.didOverflowHeight)
        assertFalse("$tag must not have clipped or ellipsized text: $diagnostics", result.hasVisualOverflow)
        return result
    }

    private fun layoutDiagnostics(tag: String, result: TextLayoutResult): String {
        val bounds = rule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val sourceText = result.layoutInput.text.text
        val lines = (0 until result.lineCount).joinToString(prefix = "[", postfix = "]") { index ->
            val start = result.getLineStart(index).coerceIn(0, sourceText.length)
            val end = result.getLineEnd(index, visibleEnd = true)
                .coerceIn(start, sourceText.length)
            val lineText = sourceText.substring(start, end)
            val ellipsis = if (result.isLineEllipsized(index)) " ellipsized" else ""
            "$index:'$lineText' ${result.getLineRight(index) - result.getLineLeft(index)}px$ellipsis"
        }
        return "bounds=${bounds.width}x${bounds.height}px, layout=${result.size}, " +
            "overflowWidth=${result.didOverflowWidth}, overflowHeight=${result.didOverflowHeight}, " +
            "visualOverflow=${result.hasVisualOverflow}, lines=$lines"
    }

    private fun courseTag(meeting: TimetableMeeting, part: String): String =
        "timetable_course_${meeting.date}_${meeting.startPeriod}_0_$part"

    private fun meeting(
        name: String,
        date: LocalDate,
        startPeriod: Int,
        endPeriod: Int,
        location: String,
    ) = TimetableMeeting(
        courseName = name,
        weekday = date.dayOfWeek.value,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        periods = (startPeriod..endPeriod).toList(),
        sourcePeriods = (startPeriod..endPeriod).toList(),
        weeks = listOf(1, 2),
        weekLabel = "1–16",
        location = location,
        sectionsSource = "test",
        date = date,
    )

    private fun weekState(weekStart: LocalDate, meetings: List<TimetableMeeting>) =
        TimetableUiState(
            accountSavedUnverified = true,
            connection = TimetableConnection.CONNECTED,
            terms = listOf(
                TimetableTerm(
                    year = "2026-2027",
                    term = "2",
                    startDay = weekStart.minusWeeks(9),
                    totalWeeks = 19,
                    label = "2026-2027 第二学期",
                ),
            ),
            selectedYear = "2026-2027",
            selectedTerm = "2",
            selectedWeek = 10,
            weekDates = (0..6).map { weekStart.plusDays(it.toLong()) },
            meetings = meetings,
            weekState = TimetableWeekState.READY,
        )
}
