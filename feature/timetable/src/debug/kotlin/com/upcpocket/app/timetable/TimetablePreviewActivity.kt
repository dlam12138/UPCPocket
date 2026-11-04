package com.upcpocket.app.timetable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.ui.theme.UpcPocketTheme
import java.time.LocalDate

/**
 * Debug-only visual acceptance screen for the M5 timetable layout.
 *
 * It renders the real WeekPanel/TodayPanel composables with an explicit synthetic
 * state banner. It has no account, session, cache, network or production data path.
 */
class TimetablePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UpcPocketTheme(darkMode = DarkMode.LIGHT, preset = ThemePreset.BLUE) {
                PreviewContent()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewContent() {
    var selected by remember { mutableStateOf<TimetableMeeting?>(null) }
    val term = TimetableTerm(
        year = "2026-2027",
        term = "1",
        startDay = LocalDate.parse("2026-09-06"),
        totalWeeks = 19,
        label = "2026-2027 秋季",
    )
    val dates = (0..6).map { LocalDate.parse("2026-11-01").plusDays(it.toLong()) }
    val today = LocalDate.parse("2026-11-04")
    val meetings = previewMeetings(dates)
    val state = TimetableUiState(
        accountStatusLabel = "视觉预览",
        maskedAccount = null,
        accountSavedUnverified = true,
        connection = TimetableConnection.CONNECTED,
        terms = listOf(term),
        selectedYear = term.year,
        selectedTerm = term.term,
        selectedWeek = 10,
        weekDates = dates,
        meetings = meetings,
        weekState = TimetableWeekState.READY,
        weekFetchedAtEpochMillis = 1_760_000_000_000,
        today = today,
        todayMeetings = meetings.filter { it.date == today },
        todayLoaded = true,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("课表")
                        Text(
                            "视觉预览 · 不是真实学期",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "本地预览 · 以下课程、教师与地点均为合成内容，仅用于 M5 视觉验收；" +
                    "不读取账号、不写入缓存、不发起网络请求。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp),
            )
            TermAndWeekHeader(state = state, onSelectTerm = {}, onSelectWeek = {})
            TodayPanel(state = state, onCourseClick = { selected = it })
            WeekPanel(state = state, onCourseClick = { selected = it })
        }
    }
    selected?.let { meeting ->
        CourseDetailDialog(meeting = meeting, onDismiss = { selected = null })
    }
}

private fun previewMeetings(dates: List<LocalDate>): List<TimetableMeeting> = listOf(
    previewMeeting("高等数学 A", 1, 1, 2, dates[1], "李老师", "南教 101", "08:00–09:35"),
    previewMeeting("大学物理", 1, 3, 4, dates[1], "王老师", "南教 203", "09:55–11:30"),
    previewMeeting("程序设计基础", 3, 6, 7, dates[3], "陈老师", "实验楼 302", "14:00–15:35"),
    previewMeeting("学术英语", 3, 9, 10, dates[3], "刘老师", "南教 405", "16:00–17:35"),
    previewMeeting("体育", 4, 3, 4, dates[4], "赵老师", "田径场", "09:55–11:30"),
    previewMeeting("数据结构", 5, 5, 6, dates[5], "孙老师", "南教 206", "14:00–15:35"),
)

private fun previewMeeting(
    name: String,
    weekday: Int,
    start: Int,
    end: Int,
    date: LocalDate,
    teacher: String,
    location: String,
    time: String,
): TimetableMeeting = TimetableMeeting(
    courseName = name,
    weekday = weekday,
    startPeriod = start,
    endPeriod = end,
    periods = (start..end).toList(),
    sourcePeriods = (start..end).toList(),
    weeks = listOf(10),
    weekLabel = "10",
    teacher = teacher,
    location = location,
    sectionsSource = "preview",
    courseTime = time,
    date = date,
)
