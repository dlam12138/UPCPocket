package com.upcpocket.app.timetable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        val dark = intent.getBooleanExtra("dark", false)
        val homeToday = intent.getBooleanExtra("homeToday", false)
        val promo = intent.getBooleanExtra("promo", false)
        if (promo) enableEdgeToEdge()
        setContent {
            UpcPocketTheme(
                darkMode = if (dark) DarkMode.DARK else DarkMode.LIGHT,
                preset = ThemePreset.BLUE,
            ) {
                PreviewContent(homeToday, promo)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewContent(homeToday: Boolean, promo: Boolean) {
    var selected by remember { mutableStateOf<TimetableMeeting?>(null) }
    val term = TimetableTerm(
        year = "2026-2027",
        term = "1",
        startDay = LocalDate.parse("2026-09-06"),
        totalWeeks = 19,
        label = "2026-2027 秋季",
    )
    val sunday = if (promo) LocalDate.now(TIMETABLE_ZONE)
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY))
        else LocalDate.parse("2026-11-01")
    val dates = (0..6).map { sunday.plusDays(it.toLong()) }
    val meetings = previewMeetings(dates).let { items ->
        if (promo) items.filterNot { it.courseName == "概率论与数理统计" }.map { meeting ->
            val (start, end) = when (meeting.startPeriod to meeting.endPeriod) {
                5 to 6 -> 6 to 7
                7 to 8, 9 to 10 -> 8 to 9
                11 to 12 -> 10 to 11
                else -> meeting.startPeriod to meeting.endPeriod
            }
            val normalized = meeting.copy(
                courseName = if (meeting.courseName.startsWith("大学英语视听说")) "大学英语" else meeting.courseName,
                startPeriod = start, endPeriod = end, periods = (start..end).toList(),
                sourcePeriods = (start..end).toList(), courseTime = null,
            )
            normalized.copy(courseTime = UpcTimetableSchedule.label(normalized))
        } else items
    }
    val colorSlots = remember(meetings) {
        TimetableColorStore.inMemory().assign(
            accountId = "preview-01",
            termKey = "2026-2027|1",
            identities = meetings.map(::courseColorKey),
        )
    }
    val state = TimetableUiState(
        accountStatusLabel = "视觉预览",
        maskedAccount = null,
        accountManagedActive = true,
        connection = TimetableConnection.CONNECTED,
        terms = listOf(term),
        selectedYear = term.year,
        selectedTerm = term.term,
        selectedWeek = if (promo) (java.time.temporal.ChronoUnit.WEEKS.between(term.startDay, sunday) + 1).toInt() else 10,
        weekDates = dates,
        meetings = meetings,
        courseColorSlots = colorSlots,
        weekState = TimetableWeekState.READY,
        weekFetchedAtEpochMillis = 1_760_000_000_000,
    )
    val homeDate = LocalDate.now(TIMETABLE_ZONE)
    val homeMeetings = meetings.take(3).mapIndexed { index, meeting ->
        val start = index * 2 + 1
        meeting.copy(
            weekday = homeDate.dayOfWeek.value,
            startPeriod = start,
            endPeriod = start + 1,
            periods = (start..start + 1).toList(),
            sourcePeriods = (start..start + 1).toList(),
            date = homeDate,
        )
    }
    val homeState = TimetableTodayUiState(
        accountEpoch = 1,
        accountManagedActive = true,
        accountBlocked = false,
        today = homeDate,
        todayMeetings = homeMeetings,
        todayCourseColorSlots = colorSlots,
        todayState = TimetableTodayState.READY,
        todayLoaded = true,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("课表")
                        if (!promo) Text(
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
            if (!promo) Text(
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
            if (homeToday) {
                TodayPanel(
                    state = homeState,
                    showDate = false,
                    onCourseClick = { selected = it },
                )
            } else {
                TermAndWeekHeader(state = state, onSelectTerm = {}, onSelectWeek = {})
                WeekPanel(state = state, onCourseClick = { selected = it })
            }
        }
    }
    selected?.let { meeting ->
        CourseDetailBottomSheet(meeting = meeting, onDismiss = { selected = null })
    }
}

/** Debug bridge for the real HomeScreen; the two meetings are synthetic and never cached. */
@Composable
fun PromotionTodaySection() {
    val today = LocalDate.now(TIMETABLE_ZONE)
    val meetings = remember(today) { listOf(
        previewMeeting("高等数学 A", today.dayOfWeek.value, 1, 2, today, "李老师", "南教 101", "08:00–09:35"),
        previewMeeting("程序设计基础", today.dayOfWeek.value, 6, 7, today, "陈老师", "实验楼 302", "14:00–15:35"),
    ) }
    val colors = remember(meetings) { TimetableColorStore.inMemory().assign(
        "promotion", "2026-2027|1", meetings.map(::courseColorKey),
    ) }
    TodayPanel(state = TimetableTodayUiState(
        accountEpoch = 1, accountManagedActive = true, accountBlocked = false,
        today = today, todayMeetings = meetings, todayCourseColorSlots = colors,
        todayState = TimetableTodayState.READY, todayLoaded = true,
    ), onCourseClick = {}, showDate = false)
}

private fun previewMeetings(dates: List<LocalDate>): List<TimetableMeeting> = listOf(
    previewMeeting("高等数学 A", 1, 1, 2, dates[1], "李老师", "南教 101", "08:00–09:35"),
    previewMeeting("概率论与数理统计", 1, 1, 2, dates[1], "周老师", "南教 105", "08:00–09:35"),
    previewMeeting("大学物理", 1, 3, 4, dates[1], "王老师", "南教 203", "09:55–11:30"),
    previewMeeting("程序设计基础", 3, 6, 7, dates[3], "陈老师", "实验楼 302", "14:00–15:35"),
    previewMeeting("学术英语", 3, 9, 10, dates[3], "刘老师", "南教 405", "16:00–17:35"),
    previewMeeting("体育", 4, 3, 4, dates[4], "赵老师", "田径场", "09:55–11:30"),
    previewMeeting("数据结构", 5, 5, 6, dates[5], "孙老师", "南教 206", "14:00–15:35"),
    previewMeeting(
        "大学英语视听说（学术交流与跨文化沟通）",
        2,
        7,
        8,
        dates[2],
        "杨老师",
        "南教 512",
        "14:00–15:35",
    ),
    previewMeeting("金工实习", 5, 11, 12, dates[5], "何老师", "工程训练中心", "19:00–20:35"),
    previewMeeting("创新创业基础", 6, 3, 4, dates[6], "郑老师", "南教 301", "09:55–11:30"),
    previewMeeting("电路分析", 7, 11, 12, dates[0], "冯老师", "南教 207", "19:00–20:35"),
    previewMeeting("材料科学导论", 2, 1, 2, dates[2], "许老师", "南教 118", "08:00–09:35"),
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
