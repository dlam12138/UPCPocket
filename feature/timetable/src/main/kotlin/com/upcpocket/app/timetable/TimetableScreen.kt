package com.upcpocket.app.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.designsystem.DOCK_CLEARANCE
import com.upcpocket.app.ui.components.GlassCard
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    service: TimetableService,
    onBack: () -> Unit,
    onOpenAccountSettings: () -> Unit,
) {
    val viewModel: TimetableViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TimetableViewModel(service) as T
        },
    )
    TimetableScreen(
        viewModel = viewModel,
        onBack = onBack,
        onOpenAccountSettings = onOpenAccountSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableScreen(
    viewModel: TimetableViewModel,
    onBack: () -> Unit,
    onOpenAccountSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<TimetableMeeting?>(null) }
    var loginVisible by remember { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var loginHint by remember { mutableStateOf<String?>(null) }
    var loginEpoch by remember { mutableStateOf(0L) }
    val visibleDetail = detail?.takeIf { selected ->
        state.meetings.any { it.instanceId == selected.instanceId }
    }

    androidx.compose.runtime.LaunchedEffect(
        state.accountEpoch,
        state.accountBlocked,
        state.accountSavedUnverified,
    ) {
        if (state.accountBlocked || !state.accountSavedUnverified) {
            detail = null
            loginVisible = false
            loginHint = null
            viewModel.cancelInteractiveLogin()
        }
    }
    androidx.compose.runtime.LaunchedEffect(
        state.accountEpoch,
        state.selectedYear,
        state.selectedTerm,
        state.selectedWeek,
    ) {
        // 详情属于某一账号/某一周；换号或换周后不能继续展示旧课程。
        detail = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.isBusy && !state.accountBlocked,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新课表")
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
                .padding(horizontal = 14.dp)
                .padding(bottom = DOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            AccountHeader(state)
            if (state.accountBlocked) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.message ?: "学校账号暂不可用。", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onOpenAccountSettings) {
                            Text("前往学校账号")
                        }
                    }
                }
                return@Column
            }
            ConnectionBanner(
                state = state,
                onRetry = viewModel::refresh,
                onOpenAccountSettings = onOpenAccountSettings,
                onOpenLogin = {
                    viewModel.beginInteractiveLogin { url ->
                        if (url != null) {
                            loginUrl = url
                            loginEpoch = state.accountEpoch
                            loginVisible = true
                        }
                    }
                },
                onOpenWebLogin = {
                    viewModel.beginInteractiveLogin { url ->
                        if (url != null) {
                            loginUrl = url
                            loginEpoch = state.accountEpoch
                            loginVisible = true
                        }
                    }
                },
            )
            if (state.terms.isEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "尚未取得学期列表。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.isBusy) CircularProgressIndicator(Modifier.height(22.dp))
                    }
                }
                return@Column
            }

            TermAndWeekHeader(
                state = state,
                onSelectTerm = viewModel::selectTerm,
                onSelectWeek = viewModel::selectWeek,
            )
            WeekPanel(state, onCourseClick = { detail = it })
            TodayPanel(state, onCourseClick = { detail = it })
            Text(
                "课表为只读查询；学期、周次、节次与日期均按学校源数据展示，未提供项不推测。" +
                    "本页不会自动遍历整学期或周期轮询。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    visibleDetail?.let { meeting ->
        CourseDetailDialog(meeting = meeting, onDismiss = { detail = null })
    }
    if (loginVisible && loginUrl != null && loginEpoch == state.accountEpoch &&
        state.accountSavedUnverified && !state.accountBlocked
    ) {
        TimetableLoginDialog(
            url = loginUrl!!,
            hint = loginHint,
            onCookies = { header ->
                viewModel.adoptInteractiveCookies(header) { accepted ->
                    if (accepted) {
                        loginVisible = false
                        loginHint = null
                    } else {
                        loginHint = "尚未验证到有效课表会话；完成登录后点“验证当前登录”。"
                    }
                }
            },
            onClose = {
                viewModel.cancelInteractiveLogin()
                loginVisible = false
                loginHint = null
            },
        )
    }
}

@Composable
internal fun AccountHeader(state: TimetableUiState) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("学校账号", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    state.accountStatusLabel.ifBlank { "账号状态未知" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "本机保存状态与课表连接状态分开显示；保存成功不代表账号已通过学校认证。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
internal fun TermAndWeekHeader(
    state: TimetableUiState,
    onSelectTerm: (TimetableTerm) -> Unit,
    onSelectWeek: (Int) -> Unit,
) {
    val term = state.selectedTermOrNull ?: return
    var menuOpen by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(term.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        state.terms.forEach { item ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        item.label,
                                        fontWeight = if (item == term) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onSelectTerm(item)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "第 ${state.selectedWeek ?: "–"} 周 / 共 ${term.totalWeeks} 周",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(term.totalWeeks) { index ->
                    val week = index + 1
                    FilterChip(
                        selected = state.selectedWeek == week,
                        onClick = { onSelectWeek(week) },
                        label = { Text("$week", fontSize = 12.sp) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConnectionBanner(
    state: TimetableUiState,
    onRetry: () -> Unit,
    onOpenAccountSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenWebLogin: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "课表连接",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = connectionColor(state.connection).copy(alpha = 0.16f),
                ) {
                    Text(
                        connectionLabel(state.connection),
                        style = MaterialTheme.typography.labelSmall,
                        color = connectionColor(state.connection),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            if (state.message != null) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            state.weekFetchedAtEpochMillis?.let { fetched ->
                Text(
                    "数据时间：${formatFetchedAt(fetched)}" +
                        if (state.weekState == TimetableWeekState.CACHED) " · 历史缓存" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (state.isBusy) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.connection == TimetableConnection.INTERACTION_REQUIRED) {
                    Button(onClick = onOpenLogin) { Text("打开学校登录页") }
                }
                if (state.canRetry && !state.isBusy) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                if (state.connection == TimetableConnection.NOT_CONFIGURED ||
                    state.connection == TimetableConnection.AUTH_FAILED ||
                    state.accountSavedUnverified
                ) {
                    TextButton(onClick = onOpenAccountSettings) { Text("学校账号") }
                }
                if (state.connection != TimetableConnection.INTERACTION_REQUIRED &&
                    state.connection != TimetableConnection.NOT_CONFIGURED &&
                    !state.accountBlocked
                ) {
                    TextButton(onClick = onOpenWebLogin) { Text("使用学校网页登录") }
                }
            }
        }
    }
}

@Composable
internal fun WeekPanel(
    state: TimetableUiState,
    onCourseClick: (TimetableMeeting) -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("教学周", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                if (state.weekState == TimetableWeekState.READY) {
                    Text(
                        "在线成功",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (state.weekState == TimetableWeekState.CACHED) {
                    Text(
                        "离线缓存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when (state.weekState) {
                TimetableWeekState.NOT_QUERIED -> EmptyWeekHint("该教学周尚未查询；切换周会按需查询。")
                TimetableWeekState.ERROR -> EmptyWeekHint(
                    state.message ?: "该教学周查询失败；未缓存时不能离线查看。",
                )
                TimetableWeekState.LOADING -> if (state.meetings.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    WeekGrid(state, onCourseClick)
                }
                TimetableWeekState.EMPTY -> EmptyWeekHint("本周无课程")
                TimetableWeekState.READY,
                TimetableWeekState.CACHED,
                -> WeekGrid(state, onCourseClick)
            }
        }
    }
}

@Composable
private fun EmptyWeekHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

private val SLOT_HEIGHT = 44.dp
private val DAY_WIDTH = 58.dp
private const val PERIODS = 14

@Composable
private fun WeekGrid(
    state: TimetableUiState,
    onCourseClick: (TimetableMeeting) -> Unit,
) {
    val dates = state.weekDates.sortedBy { it.dayOfWeek.value }
    if (dates.size != 7) return
    val byDate = state.meetings.groupBy { it.date }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(Modifier.width(26.dp)) {
            Spacer(Modifier.height(38.dp))
            repeat(PERIODS) { index ->
                Box(
                    Modifier
                        .height(SLOT_HEIGHT)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        dates.forEach { date ->
            DayColumn(
                date = date,
                meetings = byDate[date].orEmpty(),
                onCourseClick = onCourseClick,
            )
        }
    }
}

@Composable
private fun DayColumn(
    date: LocalDate,
    meetings: List<TimetableMeeting>,
    onCourseClick: (TimetableMeeting) -> Unit,
) {
    val laneInfo = remember(meetings) { assignLanes(meetings) }
    Column(
        Modifier
            .width(DAY_WIDTH)
            .padding(horizontal = 1.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                weekdayLabel(date.dayOfWeek.value),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${date.monthValue}/${date.dayOfMonth}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
            )
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(SLOT_HEIGHT * PERIODS)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                    RoundedCornerShape(4.dp),
                ),
        ) {
            val lanes = laneInfo.second.coerceAtLeast(1)
            val laneWidth = maxWidth / lanes
            meetings.forEachIndexed { index, meeting ->
                val lane = laneInfo.first.getOrElse(index) { 0 }
                val top = SLOT_HEIGHT * (meeting.startPeriod - 1)
                val height = SLOT_HEIGHT * (meeting.endPeriod - meeting.startPeriod + 1) - 2.dp
                val color = courseColor(index + date.dayOfWeek.value)
                Box(
                    Modifier
                        .offset(x = laneWidth * lane, y = top)
                        .width((laneWidth - 2.dp).coerceAtLeast(12.dp))
                        .height(height)
                        .padding(top = 1.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(color.copy(alpha = 0.78f))
                        .border(0.5.dp, color, RoundedCornerShape(5.dp))
                        .clickable { onCourseClick(meeting) }
                        .semantics {
                            contentDescription =
                                "课程详情 ${meeting.courseName} ${meeting.sourcePeriodsLabel()}"
                        }
                        .padding(2.dp),
                ) {
                    Column {
                        Text(
                            meeting.courseName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (height > 38.dp) {
                            Text(
                                periodLabel(meeting),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TodayPanel(
    state: TimetableUiState,
    onCourseClick: (TimetableMeeting) -> Unit,
) {
    val today = state.today
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今日课程", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                if (today != null) {
                    Text(
                        "${today.monthValue}月${today.dayOfMonth}日 ${weekdayLabel(today.dayOfWeek.value)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                !state.todayLoaded -> Text(
                    "今日课程尚未查询。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                today != null && !state.weekDates.contains(today) -> Text(
                    "今日不在当前查看的教学周；请切换到本周查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.todayMeetings.isEmpty() -> Text(
                    "今日无课程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> state.todayMeetings.forEach { meeting ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCourseClick(meeting) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                meeting.courseName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                listOfNotNull(
                                    periodLabel(meeting),
                                    meeting.location ?: "地点未提供",
                                    meeting.teacher ?: "教师未提供",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CourseDetailDialog(
    meeting: TimetableMeeting,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(meeting.courseName) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                DetailLine("日期", "${meeting.date} ${weekdayLabel(meeting.weekday)}")
                DetailLine("节次", meeting.sourcePeriodsLabel())
                DetailLine("周次", meeting.weekLabel)
                DetailLine("教师", meeting.teacher ?: "未提供")
                DetailLine("地点", meeting.location ?: "未提供")
                DetailLine("上课时间", meeting.courseTime ?: "未提供")
                DetailLine("单双周源信息", meeting.weekType ?: "未提供")
                DetailLine("课程类型", meeting.courseType ?: "未提供")
                DetailLine("课程编号", meeting.courseId ?: "未提供")
                if (meeting.sourcePeriods.size != meeting.periods.size) {
                    Text(
                        "源节次非连续，网格按源协议拆成连续片段展示；详情保留完整原始节次。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "以上字段均来自学校课表响应；未提供的钟点、校区或教室不做推测。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(82.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun assignLanes(meetings: List<TimetableMeeting>): Pair<List<Int>, Int> {
    val ends = mutableListOf<Int>()
    val lanes = IntArray(meetings.size)
    meetings.forEachIndexed { index, meeting ->
        var lane = ends.indexOfFirst { it < meeting.startPeriod }
        if (lane < 0) {
            lane = ends.size
            ends += meeting.endPeriod
        } else {
            ends[lane] = meeting.endPeriod
        }
        lanes[index] = lane
    }
    return lanes.toList() to ends.size.coerceAtLeast(1)
}

private fun periodLabel(meeting: TimetableMeeting): String =
    if (meeting.startPeriod == meeting.endPeriod) {
        "第${meeting.startPeriod}节"
    } else {
        "第${meeting.startPeriod}–${meeting.endPeriod}节"
    }

private fun weekdayLabel(iso: Int): String = when (iso) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "未知"
}

private fun connectionLabel(connection: TimetableConnection): String = when (connection) {
    TimetableConnection.LOADING -> "账号恢复中"
    TimetableConnection.NOT_CONFIGURED -> "未设置账号"
    TimetableConnection.BLOCKED_BY_CLEANUP -> "清理中"
    TimetableConnection.IDLE -> "未连接"
    TimetableConnection.CONNECTING -> "连接中"
    TimetableConnection.CONNECTED -> "已连接"
    TimetableConnection.INTERACTION_REQUIRED -> "需交互登录"
    TimetableConnection.AUTH_FAILED -> "认证失败"
    TimetableConnection.NETWORK_UNAVAILABLE -> "网络不可用"
    TimetableConnection.PROTOCOL_ERROR -> "接口异常"
    TimetableConnection.SESSION_EXPIRED -> "会话失效"
    TimetableConnection.DEGRADED_CACHE -> "离线缓存"
}

private fun connectionColor(connection: TimetableConnection): Color = when (connection) {
    TimetableConnection.CONNECTED -> Color(0xFF186B3B)
    TimetableConnection.CONNECTING, TimetableConnection.LOADING -> Color(0xFF6A5B00)
    TimetableConnection.AUTH_FAILED, TimetableConnection.SESSION_EXPIRED,
    TimetableConnection.PROTOCOL_ERROR,
    -> Color(0xFF9B2C2C)
    TimetableConnection.NETWORK_UNAVAILABLE, TimetableConnection.DEGRADED_CACHE ->
        Color(0xFF8A5A00)
    else -> Color(0xFF5D5D5D)
}

private fun courseColor(seed: Int): Color {
    val palette = listOf(
        Color(0xFFD7E7FF),
        Color(0xFFDCEEE1),
        Color(0xFFFFE3C2),
        Color(0xFFEADDFF),
        Color(0xFFFFD9D9),
        Color(0xFFD7F0F5),
    )
    return palette[Math.floorMod(seed, palette.size)]
}

private fun formatFetchedAt(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(TIMETABLE_ZONE)
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
