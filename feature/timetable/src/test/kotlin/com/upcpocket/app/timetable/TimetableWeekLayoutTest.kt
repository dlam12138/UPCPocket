package com.upcpocket.app.timetable

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class TimetableWeekLayoutTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `portrait week panel keeps monday through sunday on screen without horizontal drag`() {
        val term = TimetableTerm(
            year = "2026-2027",
            term = "1",
            startDay = LocalDate.parse("2026-09-06"),
            totalWeeks = 19,
            label = "2026-2027 秋季",
        )
        val dates = (0..6).map { LocalDate.parse("2026-11-01").plusDays(it.toLong()) }
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
            today = LocalDate.parse("2026-11-04"),
        )

        rule.setContent {
            MaterialTheme {
                WeekPanel(state = state, onCourseClick = {})
            }
        }

        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEach { label ->
            rule.onNodeWithText(label).assertIsDisplayed()
        }
        rule.onNodeWithText("1").assertExists()
        rule.onNodeWithText("14").assertExists()
    }
}
