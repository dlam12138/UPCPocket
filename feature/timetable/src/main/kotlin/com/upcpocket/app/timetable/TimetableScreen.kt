package com.upcpocket.app.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.StatusPill
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    service: TimetableService,
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
        onOpenAccountSettings = onOpenAccountSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableScreen(
    viewModel: TimetableViewModel,
    onOpenAccountSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<TimetableMeeting?>(null) }
    var loginVisible by remember { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var loginHint by remember { mutableStateOf<String?>(null) }
    var loginEpoch by remember { mutableStateOf(0L) }
    var infoVisible by remember { mutableStateOf(false) }
    val visibleDetail = detail?.takeIf { selected ->
        state.meetings.any { it.instanceId == selected.instanceId }
    }

    LaunchedEffect(
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
    LaunchedEffect(
        state.accountEpoch,
        state.selectedYear,
        state.selectedTerm,
        state.selectedWeek,
    ) {
        detail = null
    }

    fun beginWebLogin() {
        viewModel.beginInteractiveLogin { url ->
            if (url != null) {
                loginUrl = url
                loginEpoch = state.accountEpoch
                loginVisible = true
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            TopAppBar(
                title = { Text("课表") },
                actions = {
                    IconButton(onClick = { infoVisible = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "课表说明与作息来源")
                    }
                    IconButton(onClick = onOpenAccountSettings) {
                        Icon(
                            Icons.Outlined.AccountCircle,
                            contentDescription = "学校账号",
                        )
                    }
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
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.accountBlocked) {
                AccountBlockedCard(
                    message = state.message ?: "学校账号暂不可用。",
                    onOpenAccountSettings = onOpenAccountSettings,
                )
                return@Column
            }

            if (!state.accountSavedUnverified) {
                FirstRunCard(
                    isLoading = state.connection == TimetableConnection.LOADING,
                    onOpenAccountSettings = onOpenAccountSettings,
                )
                return@Column
            }

            if (state.connection != TimetableConnection.CONNECTED || state.message != null) {
                ConnectionBanner(
                    state = state,
                    onRetry = viewModel::refresh,
                    onOpenAccountSettings = onOpenAccountSettings,
                    onOpenLogin = ::beginWebLogin,
                    onOpenWebLogin = ::beginWebLogin,
                )
            }

            if (state.terms.isEmpty()) {
                if (state.isBusy) {
                    ReadingCard()
                } else {
                    NoTermsCard(onRetry = viewModel::refresh)
                }
            } else {
                // 固定页头 → 紧凑选择条 → 今日课程 → 周网格。大段声明收进说明按钮。
                TermAndWeekHeader(
                    state = state,
                    onSelectTerm = viewModel::selectTerm,
                    onSelectWeek = viewModel::selectWeek,
                )
                TodayPanel(state, onCourseClick = { detail = it })
                WeekPanel(state, onCourseClick = { detail = it })
            }
            Spacer(Modifier.height(2.dp))
        }
    }

    visibleDetail?.let { meeting ->
        CourseDetailDialog(meeting = meeting, onDismiss = { detail = null })
    }
    if (infoVisible) {
        TimetableInfoDialog(
            onDismiss = { infoVisible = false },
            onOpenWebLogin = {
                infoVisible = false
                beginWebLogin()
            },
        )
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
private fun AccountBlockedCard(message: String, onOpenAccountSettings: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                icon = Icons.Outlined.AccountCircle,
                contentDescription = null,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text("学校账号需要处理", style = MaterialTheme.typography.titleLarge)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenAccountSettings) { Text("前往学校账号") }
        }
    }
}

@Composable
private fun FirstRunCard(isLoading: Boolean, onOpenAccountSettings: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(
                icon = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                size = 48.dp,
            )
            Text("连接你的课表", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "课表使用当前学校账号，通过正常学校认证只读查询当前教学周。" +
                    "它不会在首页后台自动登录，也不会遍历整个学期。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Button(
                onClick = onOpenAccountSettings,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("设置学校账号") }
            Text(
                text = "保存账号不等于认证通过。第一次进入课表时才会发起学校认证；" +
                    "验证码或二次验证会转到学校自己的网页登录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadingCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            Text(
                "正在读取学期与当前教学周…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoTermsCard(onRetry: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("尚未取得学期列表", style = MaterialTheme.typography.titleMedium)
            Text(
                "可能是课程服务暂时不可用或认证需要交互。不会把未知状态当作空课表展示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRetry) { Text("重试") }
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
    var weekMenuOpen by remember { mutableStateOf(false) }
    val rangeStart = state.weekDates.minOrNull()
    val rangeEnd = state.weekDates.maxOrNull()
    val selectedWeek = state.selectedWeek

    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f, fill = false)) {
                TextButton(
                    onClick = { menuOpen = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        term.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    state.terms.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    item.label,
                                    fontWeight = if (item == term) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
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
            Text(
                "共 ${term.totalWeeks} 周",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { selectedWeek?.let { onSelectWeek((it - 1).coerceAtLeast(1)) } },
                enabled = selectedWeek != null && selectedWeek > 1,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "上一周",
                )
            }
            Box {
                TextButton(
                    onClick = { weekMenuOpen = true },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "第 ${selectedWeek ?: "–"} 周",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (rangeStart != null && rangeEnd != null) {
                            Text(
                                "${rangeStart.monthValue}/${rangeStart.dayOfMonth}" +
                                    " – ${rangeEnd.monthValue}/${rangeEnd.dayOfMonth}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // 用下拉周选择替代占一整行的周次条，节省的纵向空间留给完整七天网格。
                DropdownMenu(
                    expanded = weekMenuOpen,
                    onDismissRequest = { weekMenuOpen = false },
                ) {
                    (1..term.totalWeeks).forEach { week ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "第 $week 周",
                                    fontWeight = if (state.selectedWeek == week) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                            },
                            onClick = {
                                weekMenuOpen = false
                                onSelectWeek(week)
                            },
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    selectedWeek?.let {
                        onSelectWeek((it + 1).coerceAtMost(term.totalWeeks))
                    }
                },
                enabled = selectedWeek != null && selectedWeek < term.totalWeeks,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "下一周",
                )
            }
        }
    }
}

@Composable
private fun ConnectedStrip(onOpenWebLogin: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(
            text = "已连接",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpenWebLogin) { Text("使用学校网页登录") }
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
    val tone = connectionTone(state.connection)
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("课表连接", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                StatusPill(
                    text = connectionLabel(state.connection),
                    containerColor = tone.container,
                    contentColor = tone.content,
                )
            }
            if (state.message != null) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            state.weekFetchedAtEpochMillis?.let { fetched ->
                Text(
                    "数据时间：${formatFetchedAt(fetched)}" +
                        if (state.weekState == TimetableWeekState.CACHED) " · 历史缓存" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.isBusy) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
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
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                when (state.weekState) {
                    TimetableWeekState.READY -> StatusPill(
                        text = "在线成功",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    TimetableWeekState.CACHED -> StatusPill(
                        text = "离线缓存",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    TimetableWeekState.ERROR -> StatusPill(
                        text = "未更新",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    else -> Unit
                }
            }
            Spacer(Modifier.height(4.dp))
            when (state.weekState) {
                TimetableWeekState.NOT_QUERIED ->
                    EmptyWeekHint("该教学周尚未查询；切换周会按需查询。")
                TimetableWeekState.ERROR ->
                    EmptyWeekHint(state.message ?: "该教学周查询失败；未缓存时不能离线查看。")
                TimetableWeekState.LOADING -> if (state.meetings.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// 14 节 × 44dp：左轴可读优先，常规竖屏仍保持完整七天；纵向随页面滚动。
// 13–14 节没有学校官方时刻，显示“待确认”，不按等差推算，也不新增第 15 节。
private val SLOT_HEIGHT = 44.dp
private const val PERIODS = 14
private val PERIOD_COLUMN_WIDTH = 48.dp
private val DAY_HEADER_HEIGHT = 38.dp
private val MIN_DAY_WIDTH = 38.dp

@Composable
private fun WeekGrid(
    state: TimetableUiState,
    onCourseClick: (TimetableMeeting) -> Unit,
) {
    val view = remember(state.weekDates, state.meetings) {
        runCatching { buildTimetableWeekView(state.weekDates, state.meetings) }.getOrNull()
    } ?: return
    val byDate = view.meetings.groupBy { it.date }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth - PERIOD_COLUMN_WIDTH
        val dayWidth = (available / 7f).coerceAtLeast(MIN_DAY_WIDTH)
        // 极窄窗口（分屏/超大字体）才退回横滑；常规手机宽度七天直接同屏。
        val needsScroll = dayWidth * 7 + PERIOD_COLUMN_WIDTH > maxWidth
        Row(
            Modifier.then(
                if (needsScroll) {
                    Modifier.horizontalScroll(rememberScrollState())
                } else {
                    Modifier
                },
            ),
        ) {
            Column(Modifier.width(PERIOD_COLUMN_WIDTH)) {
                Spacer(Modifier.height(DAY_HEADER_HEIGHT))
                repeat(PERIODS) { index ->
                    val period = index + 1
                    val range = UpcTimetableSchedule.period(period)
                    Column(
                        Modifier
                            .height(SLOT_HEIGHT)
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "$period",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (range != null) {
                            Text(
                                range.startLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 7.sp,
                                lineHeight = 8.sp,
                                maxLines = 1,
                            )
                            Text(
                                range.endLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 7.sp,
                                lineHeight = 8.sp,
                                maxLines = 1,
                            )
                        } else {
                            Text(
                                "待确认",
                                color = MaterialTheme.colorScheme.outline,
                                fontSize = 7.sp,
                                lineHeight = 8.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            view.dates.forEach { date ->
                DayColumn(
                    date = date,
                    width = dayWidth,
                    isToday = date == state.today,
                    known = date in view.knownDates,
                    meetings = byDate[date].orEmpty(),
                    colorSlots = state.courseColorSlots,
                    onCourseClick = onCourseClick,
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    date: LocalDate,
    width: Dp,
    isToday: Boolean,
    known: Boolean,
    meetings: List<TimetableMeeting>,
    colorSlots: Map<String, Int>,
    onCourseClick: (TimetableMeeting) -> Unit,
) {
    val laneInfo = remember(meetings) { assignLanes(meetings) }
    Column(
        Modifier
            .width(width)
            .padding(horizontal = 0.5.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(DAY_HEADER_HEIGHT)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .background(
                    if (isToday) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        Color.Transparent
                    },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = weekdayLabel(date.dayOfWeek.value),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                color = if (isToday) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 1,
            )
            Text(
                "${date.monthValue}/${date.dayOfMonth}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 8.sp,
                lineHeight = 9.sp,
                maxLines = 1,
            )
            if (!known) {
                Text(
                    "未同步",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    maxLines = 1,
                )
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(SLOT_HEIGHT * PERIODS)
                .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                .background(
                    if (isToday) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                ),
        ) {
            Column(Modifier.fillMaxSize()) {
                repeat(PERIODS) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(SLOT_HEIGHT)
                            .border(
                                0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            ),
                    )
                }
            }
            if (!known) {
                Text(
                    "课程未同步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                )
            }

            val lanes = laneInfo.second.coerceAtLeast(1)
            val laneWidth = maxWidth / lanes
            meetings.forEachIndexed { index, meeting ->
                val lane = laneInfo.first.getOrElse(index) { 0 }
                val top = SLOT_HEIGHT * (meeting.startPeriod - 1)
                val height = SLOT_HEIGHT * (meeting.endPeriod - meeting.startPeriod + 1) - 2.dp
                val tone = courseTone(meeting, colorSlots[courseColorKey(meeting)])
                Surface(
                    modifier = Modifier
                        .offset(x = laneWidth * lane + 1.dp, y = top + 1.dp)
                        .width((laneWidth - 2.dp).coerceAtLeast(14.dp))
                        .height(height)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCourseClick(meeting) }
                        .semantics {
                            contentDescription =
                                "课程详情 ${meeting.courseName} ${meeting.sourcePeriodsLabel()}"
                        },
                    shape = RoundedCornerShape(6.dp),
                    color = tone.container,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                    ) {
                        Text(
                            meeting.courseName,
                            style = MaterialTheme.typography.labelMedium,
                            color = tone.content,
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (height >= 58.dp) {
                            Text(
                                UpcTimetableSchedule.label(meeting) ?: "时间待确认",
                                style = MaterialTheme.typography.labelSmall,
                                color = tone.content.copy(alpha = 0.9f),
                                fontSize = 8.sp,
                                lineHeight = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (height >= 76.dp) {
                            Text(
                                meeting.location ?: "地点未提供",
                                style = MaterialTheme.typography.labelSmall,
                                color = tone.content.copy(alpha = 0.85f),
                                fontSize = 8.sp,
                                lineHeight = 9.sp,
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
    var todayNow by remember { mutableStateOf(java.time.LocalDate.now(TIMETABLE_ZONE)) }
    var now by remember { mutableStateOf(java.time.LocalTime.now(TIMETABLE_ZONE)) }
    LaunchedEffect(Unit) {
        while (true) {
            todayNow = java.time.LocalDate.now(TIMETABLE_ZONE)
            now = java.time.LocalTime.now(TIMETABLE_ZONE)
            delay(30_000)
        }
    }
    val matchesActualToday = today == todayNow
    val ranges = remember(state.todayMeetings) {
        state.todayMeetings.associateWith { UpcTimetableSchedule.range(it) }
    }
    val nowMinute = now.hour * 60 + now.minute
    val current = state.todayMeetings.firstOrNull { meeting ->
        ranges[meeting]?.let { nowMinute in it.startMinute until it.endMinute } == true
    }
    val next = state.todayMeetings.firstOrNull { meeting ->
        ranges[meeting]?.let { it.startMinute > nowMinute } == true
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今日课程", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${todayNow.monthValue}月${todayNow.dayOfMonth}日 " +
                        weekdayLabel(todayNow.dayOfWeek.value),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (state.todayLoaded && state.todayFetchedAtEpochMillis != null) {
                    Text(
                        formatFetchedAt(state.todayFetchedAtEpochMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                !state.todayLoaded || !matchesActualToday -> Text(
                    "今日课程尚未查询。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                today == null -> Text(
                    "今日日期不可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.todayMeetings.isEmpty() -> Text(
                    "今天没有排课",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> state.todayMeetings.forEach { meeting ->
                    val range = ranges[meeting]
                    val ended = range != null && range.endMinute <= nowMinute
                    val isCurrent = meeting == current
                    val isNext = meeting == next
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onCourseClick(meeting) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tone = courseTone(meeting, state.courseColorSlots[courseColorKey(meeting)])
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(34.dp)
                                .background(tone.container, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    meeting.courseName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                when {
                                    isCurrent -> TodayTag("上课中", MaterialTheme.colorScheme.primary)
                                    isNext -> TodayTag("下一堂", MaterialTheme.colorScheme.tertiary)
                                }
                            }
                            Text(
                                listOfNotNull(
                                    range?.label ?: "时间待确认",
                                    meeting.location ?: "地点未提供",
                                    if (range == null) periodLabel(meeting) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (ended) {
                            Text(
                                "已结束",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayTag(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier.padding(start = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun CourseDetailDialog(
    meeting: TimetableMeeting,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(meeting.courseName)
                Text(
                    "${meeting.date} ${weekdayLabel(meeting.weekday)} · ${periodLabel(meeting)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(
                        text = "${meeting.weekLabel} 周",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    StatusPill(
                        text = "只读",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                HorizontalDivider(
                    Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                DetailLine("日期", "${meeting.date} ${weekdayLabel(meeting.weekday)}")
                DetailLine("节次", meeting.sourcePeriodsLabel())
                DetailLine("周次", meeting.weekLabel)
                DetailLine("教师", meeting.teacher ?: "未提供")
                DetailLine("地点", meeting.location ?: "未提供")
                DetailLine(
                    "上课时间",
                    UpcTimetableSchedule.label(meeting) ?: "时间待确认",
                )
                DetailLine("原始时间字段", meeting.courseTime ?: "未提供")
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
                    "钟点优先取自学校响应 course_time；缺失时仅查学校官方作息。" +
                        "13–14 节没有官方时刻时显示“时间待确认”，不按等差推算。",
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TimetableInfoDialog(
    onDismiss: () -> Unit,
    onOpenWebLogin: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课表说明") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "只读查询。学期、周次、节次、日期和课程时间均按学校响应展示；" +
                        "不自动遍历整学期，不周期轮询，也不提供编辑、提醒或导出。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "周网格按真实日期连续展示周日至周六；查看其他教学周时，" +
                        "“今日课程”仍按上海时区的实际今天计算。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "左轴 1–12 节使用学校 2020 年发布的作息；13–14 节没有可靠的" +
                        "官方时刻，显示“时间待确认”，不按等差推算，也没有第 15 节。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "作息来源：中国石油大学（华东）《关于调整作息时间的通知》\n" +
                        UpcTimetableSchedule.SOURCE_URL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "临时调课、停课或教室变更以学校通知为准；缓存会明确标注，“尚未查询”" +
                        "不会显示成“没有课”。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onOpenWebLogin) { Text("使用学校网页登录") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun connectionTone(connection: TimetableConnection): ToneColors {
    val scheme = MaterialTheme.colorScheme
    return when (connection) {
        TimetableConnection.CONNECTED ->
            ToneColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        TimetableConnection.CONNECTING,
        TimetableConnection.LOADING,
        TimetableConnection.DEGRADED_CACHE,
        TimetableConnection.NETWORK_UNAVAILABLE,
        -> ToneColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        TimetableConnection.AUTH_FAILED,
        TimetableConnection.SESSION_EXPIRED,
        TimetableConnection.PROTOCOL_ERROR,
        TimetableConnection.BLOCKED_BY_CLEANUP,
        -> ToneColors(scheme.errorContainer, scheme.onErrorContainer)
        else -> ToneColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
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

private fun formatFetchedAt(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(TIMETABLE_ZONE)
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
