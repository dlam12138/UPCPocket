package com.upcpocket.app.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import com.upcpocket.app.ui.components.GlassCard
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.core.ScheduleText
import com.upcpocket.app.core.WeekMath
import com.upcpocket.app.data.prefs.BlockBorder
import com.upcpocket.app.data.prefs.CourseColorMode
import com.upcpocket.app.data.prefs.CourseTextMode
import com.upcpocket.app.data.prefs.GridDensity
import com.upcpocket.app.data.local.DemoTimetable
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.data.repo.ScheduleRepository
import com.upcpocket.app.domain.Course
import com.upcpocket.app.domain.FocusRecord
import com.upcpocket.app.domain.FocusStats
import com.upcpocket.app.domain.Period
import com.upcpocket.app.domain.Phase
import com.upcpocket.app.domain.Semester
import com.upcpocket.app.domain.Session
import com.upcpocket.app.domain.toSlotLines
import java.time.LocalDate
import java.time.LocalTime

private val CELL_HEIGHT = 54.dp
private val UNIT_COL_WIDTH = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onOpenOverview: (Int) -> Unit,
    onOpenDisplaySettings: () -> Unit,
    vm: ScheduleViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())
    var showCsvImport by remember { mutableStateOf(false) }
    state.selectedSemester?.let { semester ->
        if (showCsvImport) androidx.compose.runtime.key(semester.id) {
            CsvImportDialog(semester, state.periods, vm) { showCsvImport = false }
        }
    }

    var showSemesters by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    Scaffold(
        // 壁纸画在外壳层，这里必须透明，否则页面自己的底色会把壁纸盖掉
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                // 左上角不显示"课表"二字，直接放学期切换
                title = { SemesterButton(state.selectedSemester) { showSemesters = true } },
                actions = {
                    TextButton(enabled = state.selectedSemester != null, onClick = { showCsvImport = true }) { Text("导入") }
                    IconButton(onClick = { vm.openPlanEditor() }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加日程")
                    }
                    // 本地数据没有"同步"：这里的刷新只是重读库 + 重推桌面组件
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新读取本地课表")
                    }
                    // 顶栏只留一个设置入口：教学周、显示、背景、入学年、课程总览都在里面
                    IconButton(onClick = onOpenDisplaySettings) {
                        Icon(Icons.Filled.Tune, contentDescription = "课程表设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            state.notice?.let { msg ->
                Text(
                    text = "$msg（点一下收起）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.dismissNotice() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 补录保存/删除成功的一次性确认：与错误不同色（primary），桌面小组件在中途已无感刷新。
            state.planNotice?.let { msg ->
                Text(
                    text = "$msg（点一下收起）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.clearPlanNotice() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 演示模式横幅：写在网格上方、和内容同一层级，翻周也一直看得见。
            // 演示课是虚构的，这件事必须一眼可见 —— 否则一张像真课表的画面比空白更危险。
            if (state.hasDemoSessions) {
                DemoBanner(onOpenSettings = onOpenDisplaySettings)
            }

            when {
                state.weeks.isEmpty() -> NoSemesterBlock(
                    loading = state.loading,
                    onRefresh = vm::refresh,
                )

                else -> {
                    WeekHeader(
                        week = state.weekIndex,
                        total = state.weekCount,
                        onPick = { showWeekPicker = true },
                        onWallpaper = prefs.backgroundPath != null,
                        confirmed = state.weekConfirmed,
                        onOpenSettings = onOpenDisplaySettings,
                    )
                    // 周次还没解析出来之前**不要挂翻页器**：它的初始页取自 state.weekIndex，
                    // 而 bootstrap 会先给 weeks（19 周）、后给真实周次，中间那一小段
                    // weekIndex 还是默认的 1 —— 于是翻页器从第 1 周挂载、再一路动画滚到本周，
                    // 动画每经过一页都会被写回 ViewModel（见 WeekPager 的注释）。
                    // 等解析完再挂，初始页直接就是本周，没有"从第 1 周滚过来"这一出。
                    val weekReady = state.weekResolvedOn != null || !state.loading
                    if (weekReady) {
                        WeekPager(
                            weeks = state.weeks,
                            weekIndex = state.weekIndex,
                            sessionsOf = state::sessionsOf,
                            periods = state.periods,
                            weekMondayOf = { week ->
                                state.weekStart
                                    ?.let { WeekMath.dateOf(it, week, 1, state.weekStartDay) }
                                    ?: ScheduleText.mondayOf(state.selectedSemester?.startDate, week)
                            },
                            onWeekSelected = vm::goToWeek,
                            prefs = prefs,
                            // 点课程块 → 自定义日程开编辑框，演示课开只读详情
                            onSessionClick = vm::onSessionClick,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
    if (showSemesters) {
        SemesterSheet(
            semesters = state.semesters,
            selectedId = state.selectedSemester?.id,
            onPick = { vm.selectSemester(it); showSemesters = false },
            onDismiss = { showSemesters = false },
        )
    }

    if (showWeekPicker) {
        WeekPickerDialog(
            weeks = state.weeks,
            current = state.weekIndex,
            onPick = {
                vm.goToWeek(it)
                showWeekPicker = false
            },
            onDismiss = { showWeekPicker = false },
        )
    }

    state.detailCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onDismiss = vm::closeDetail,
            overrides = state.detailOverrides,
            periods = state.periods,
            // 演示课不可编辑：覆盖写进去也会被下一次生成盖掉，不如直接关掉入口
            onApplyEdits = if (state.detailIsDemo) null else vm::applyDisplayEdits,
            onResetOverrides = if (state.detailIsDemo) null else vm::resetDisplayOverrides,
            demo = state.detailIsDemo,
        )
    }

    if (state.planEditorOpen) {
        PlanEditDialog(
            initial = state.editingPlan,
            semesterId = state.selectedSemester?.id ?: 0,
            availableWeeks = state.weeks.ifEmpty { (1..18).toList() },
            maxUnit = maxOf(10, state.periods.maxOfOrNull { it.indexNo } ?: 0),
            canStartFocus = true,
            currentWeek = state.weekIndex,
            onDismiss = vm::closePlanEditor,
            onSave = vm::savePlan,
            onDelete = { plan -> vm.deletePlan(plan.code) },
            onStartFocus = { plan ->
                vm.closePlanEditor()
                startFocusFor(name = plan.name, contextRef = "plan:${plan.code}")
            },
        )
    }

    if (state.pendingPlan != null) {
        PlanConflictDialog(
            conflicts = state.pendingConflicts,
            onConfirm = vm::confirmPendingPlan,
            onDismiss = vm::dismissPendingPlan,
        )
    }
}

/**
 * 从课表跳到番茄钟：标签自动用课名/日程名，并记下关联对象。
 * 这样统计里能算出"这门课实际专注了多久"。
 */
private fun startFocusFor(name: String, contextRef: String) {
    AppGraph.pomodoroEngine.start(
        phase = Phase.FOCUS,
        tagId = null,
        tagName = name,
        contextRef = contextRef,
    )
}

@Composable
private fun SemesterButton(semester: Semester?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Text(
            text = semester?.displayName ?: "选择学期",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换学期")
    }
}

@Composable
private fun WeekHeader(
    week: Int,
    total: Int,
    onPick: () -> Unit,
    onWallpaper: Boolean = false,
    /** 周次参数是否已确认；false 时表头下面给一行小灰字提醒（不抢眼，但看得见） */
    confirmed: Boolean = true,
    onOpenSettings: (() -> Unit)? = null,
) {
    val shadow = wallpaperShadow(onWallpaper, MaterialTheme.colorScheme.surface)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = onPick),
            ) {
                // 只留周次。原来下面还有一行「9/21 - 9/27」的日期区间，用户要求删掉 ——
                // "这是哪几天"改由星期表头下面那行日期回答（每一列都有，翻到哪一周都看得出来）。
                // 也刻意不再显示「9/7 起」：用户觉得表头上挂基准日太啰嗦，基准改到设置页里看。
                Text(
                    text = "第 $week 周 / 共 $total 周",
                    style = MaterialTheme.typography.titleMedium.copy(shadow = shadow),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (!confirmed && onOpenSettings != null) {
            // 只在"周次是自动猜的、还没确认"时出现，确认后永久消失。
            // 用一行小灰字而不是按钮：它是个提醒，不该抢周次的视觉重量。
            Text(
                text = "周次为自动推断，可在课程表设置里确认",
                style = MaterialTheme.typography.labelSmall.copy(shadow = shadow),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** 壁纸开着时给直接压在照片上的字加一层极轻投影 —— 比给文字垫实色块自然得多 */
private fun wallpaperShadow(enabled: Boolean, surface: Color): Shadow? = if (enabled) {
    Shadow(
        color = surface.copy(alpha = 0.7f),
        offset = Offset(0f, 1.5f),
        blurRadius = 4f,
    )
} else {
    null
}

/** 星期表头行的高度：周几（约 16dp）+ 日期小字（约 12dp）+ 一点余量 */
private val WEEKDAY_HEADER_HEIGHT = 38.dp

@Composable
private fun WeekPager(
    weeks: List<Int>,
    weekIndex: Int,
    sessionsOf: (Int) -> List<Session>,
    periods: List<Period>,
    weekMondayOf: (Int) -> LocalDate?,
    onWeekSelected: (Int) -> Unit,
    prefs: UiPrefs,
    onSessionClick: (Session) -> Unit,
) {
    val initial = weeks.indexOf(weekIndex).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { weeks.size })

    // ① 用户滑动 → 通知 ViewModel。
    //
    // ⚠️ 两个要点，都是真机上踩出来的（课表页停在上一周）：
    //  · 只听 **settledPage**（松手落定后的页），不听 currentPage：后者在动画过程中
    //    会逐页变化，等于把动画的中间态写回 ViewModel；
    //  · **程序化滚动期间（isScrollInProgress）一律不写**：否则"滚到本周"的动画
    //    每经过一页就改一次 weekIndex，而 weekIndex 一变又会取消正在跑的动画
    //    （下面 ② 重新执行）—— 两个 effect 互相驱动，最后稳定停在中途某一周。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (scrolling) return@collect
                weeks.getOrNull(page)?.let { if (it != weekIndex) onWeekSelected(it) }
            }
    }
    // ② ViewModel 变化（点周次选择、隔天自动回到本周）→ 滚过去。
    //    这是单向的：①不会回写中间态，所以不会再打架。
    LaunchedEffect(weekIndex, weeks) {
        val idx = weeks.indexOf(weekIndex)
        if (idx >= 0 && idx != pagerState.currentPage) pagerState.animateScrollToPage(idx)
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val week = weeks.getOrNull(page)
        if (week != null) {
            ScheduleGrid(
                sessions = sessionsOf(week),
                periods = periods,
                prefs = prefs,
                // 星期表头下面那行日期要用它：算不出来（没学期/没起始日）就不显示日期
                weekMonday = weekMondayOf(week),
                onSessionClick = onSessionClick,
            )
        }
    }
}

@Composable
private fun ScheduleGrid(
    sessions: List<Session>,
    periods: List<Period>,
    prefs: UiPrefs,
    /** 这一周周一的日期；null = 算不出来（没选学期/没同步），表头就只显示周几 */
    weekMonday: java.time.LocalDate?,
    onSessionClick: (Session) -> Unit,
) {
    val days = 1..prefs.dayCount
    val cellH = prefs.cellHeightDp.dp
    val gap = if (prefs.blockBorder == BlockBorder.NONE) 0.75.dp else 1.dp
    // 网格至少 10 格；用户作息里有 11–13 节时自动长到最大节次。
    // 不能把 10 写死，否则导入的 11–13 节课在网格里根本没有格子可画。
    val unitCount = maxOf(10, periods.maxOfOrNull { it.indexNo } ?: 0)
    val dark = isSystemInDarkTheme()
    val todayIso = LocalDate.now().toString()
    val today = LocalDate.now()
    val now = LocalTime.now()
    // 有壁纸时，星期表头和节次时间这些直接压在照片上的小字加一层轻投影
    val labelShadow = wallpaperShadow(
        prefs.backgroundPath != null,
        MaterialTheme.colorScheme.surface,
    )
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // ⚠️ 表头**不放在可滚动区里**：往下翻课表时"周几 + 日期"要一直看得见
    // （用户要求固定住；它只是一行普通 Row，不吃横向拖动，所以左右翻周的手感不受影响）。
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(WEEKDAY_HEADER_HEIGHT)) {
            Spacer(Modifier.width(UNIT_COL_WIDTH))
            for (d in days) {
                val date = weekMonday?.plusDays((d - 1).toLong())
                val isToday = date != null && date == today
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = ScheduleText.weekdayName(d).removePrefix("周"),
                        style = MaterialTheme.typography.labelMedium.copy(shadow = labelShadow),
                        color = labelColor,
                    )
                    // 日期：10-01 这种写法（带月份、补零）。今天那一列用主色 + 加粗标出来
                    if (date != null) {
                        Text(
                            text = ScheduleText.dayLabel(date),
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                labelColor
                            },
                            style = LocalTextStyle.current.copy(shadow = labelShadow),
                        )
                    }
                }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val gridLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            Row(
                modifier = Modifier.fillMaxWidth().drawBehind {
                    val unitW = UNIT_COL_WIDTH.toPx()
                    val rowH = cellH.toPx()
                    val gapPx = gap.toPx()
                    val stroke = 0.7.dp.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    // 横向虚线：每节课的上边界 + 最后一节的下边界
                    for (i in 0 until unitCount) {
                        val y = (rowH + gapPx) * i
                        drawLine(gridLine, Offset(0f, y), Offset(size.width, y), stroke, pathEffect = dash)
                    }
                    drawLine(
                        gridLine,
                        Offset(0f, size.height),
                        Offset(size.width, size.height),
                        stroke,
                        pathEffect = dash,
                    )
                    // 纵向虚线：时间列与各天之间
                    val dayCount = days.count()
                    val dayW = (size.width - unitW) / dayCount
                    for (i in 0..dayCount) {
                        val x = unitW + dayW * i
                        drawLine(gridLine, Offset(x, 0f), Offset(x, size.height), stroke, pathEffect = dash)
                    }
                },
            ) {
                // 左侧节次列：1..unitCount，与右侧各天严格对齐
                Column(
                    modifier = Modifier.width(UNIT_COL_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (unit in 1..unitCount) {
                        Column(
                            modifier = Modifier.fillMaxWidth().height(cellH),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "$unit",
                                style = MaterialTheme.typography.labelMedium.copy(shadow = labelShadow),
                            )
                            periods.firstOrNull { it.indexNo == unit }?.let {
                                // 起止放在同一行，避免行高不够时和时间数字叠在一起
                                Text(
                                    text = "${it.startText}-${it.endText}",
                                    fontSize = 7.sp,
                                    lineHeight = 9.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = labelColor,
                                    style = LocalTextStyle.current.copy(shadow = labelShadow),
                                )
                            }
                        }
                    }
                }

                // 每天一列 —— 只有按列排布才可能把跨节次的课画成一个连续的整块
                for (day in days) {
                    DayColumn(
                        modifier = Modifier.weight(1f),
                        day = day,
                        sessions = sessions,
                        periods = periods,
                        prefs = prefs,
                        cellH = cellH,
                        gap = gap,
                        unitCount = unitCount,
                        dark = dark,
                        todayIso = todayIso,
                        now = now,
                        onSessionClick = onSessionClick,
                    )
                }
            }
        Spacer(Modifier.height(16.dp))
        } // 关闭可滚动区（表头在上面，不跟着滚）
    } // 关闭外层 Column
} // 关闭 ScheduleGrid

/**
 * 一天的列。
 *
 * 跨节次的课只画**一个**块：块高 = 节数 × 行高 + 被吃掉的间隙，
 * 并跳过被覆盖的格子。因为 `总高 = 行高 × 节数 + 间隙 × (格数 - 1)` 在合并前后相等，
 * 各天与左侧节次列仍然严格对齐。
 */
@Composable
private fun DayColumn(
    modifier: Modifier,
    day: Int,
    sessions: List<Session>,
    periods: List<Period>,
    prefs: UiPrefs,
    cellH: Dp,
    gap: Dp,
    unitCount: Int,
    dark: Boolean,
    todayIso: String,
    now: LocalTime,
    onSessionClick: (Session) -> Unit,
) {
    val corner = RoundedCornerShape(6.dp)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        var unit = 1
        while (unit <= unitCount) {
            val session = sessions.firstOrNull { it.weekday == day && unit in it.startUnit..it.endUnit }
            if (session == null) {
                Box(Modifier.fillMaxWidth().height(cellH))
                unit += 1
                continue
            }
            val span = (session.endUnit - session.startUnit + 1).coerceIn(1, unitCount - unit + 1)
            CourseBlock(
                session = session,
                span = span,
                height = cellH * span + gap * (span - 1),
                prefs = prefs,
                corner = corner,
                periods = periods,
                dark = dark,
                live = prefs.liveOutlineEnabled && isLiveNow(session, periods, todayIso, now),
                onClick = onSessionClick,
            )
            unit += span
        }
    }
}

@Composable
private fun CourseBlock(
    session: Session,
    span: Int,
    height: Dp,
    prefs: UiPrefs,
    corner: Shape,
    periods: List<Period>,
    dark: Boolean,
    live: Boolean,
    onClick: (Session) -> Unit,
) {
    val bg = when {
        // 自定义日程用用户自己挑的颜色，不受"课程配色"设置影响 ——
        // 挑完就得一直是他挑的那个色，不能被"单色模式"改掉
        session.source == ScheduleRepository.SOURCE_CUSTOM ->
            planBlockColor(session.color, dark)
        prefs.colorMode == CourseColorMode.SINGLE -> MaterialTheme.colorScheme.secondaryContainer
        else -> courseColor(session.courseCode, dark)
    }
    // 「正在上的课」描边（借鉴 Sked 的 liveCourseOutline）；否则按设置画实线/虚线/不画
    val decoration = when {
        live -> Modifier.border(prefs.liveOutlineWidth.dp, MaterialTheme.colorScheme.primary, corner)
        prefs.blockBorder == BlockBorder.SOLID ->
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, corner)
        prefs.blockBorder == BlockBorder.DASHED ->
            Modifier.dashedBorder(MaterialTheme.colorScheme.outline)
        else -> Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(bg, corner)
            .then(decoration)
            .clickable { onClick(session) },
    ) {
        // 实验/见习标记：右上角小角标。以前用文字后缀「（实验）」，窄格里会把课名
        // 挤到截断（issue #6 的「微机原理及接口技术（实…」），角标不占文字区。
        if (session.experiment) {
            Text(
                text = "实",
                fontSize = 7.sp,
                lineHeight = 8.sp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                    .padding(horizontal = 2.dp),
            )
        }
        // 演示标记：演示课的课名本身就带「（演示）」，这里再加一个角标，
        // 保证即使用户把课名改成别的（演示课不可编辑，但字号调小后可能看不出后缀）也一眼可辨。
        if (session.source == DemoTimetable.SOURCE) {
            Text(
                text = "演示",
                fontSize = 6.sp,
                lineHeight = 7.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 1.dp, start = 1.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(3.dp))
                    .padding(horizontal = 2.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 2.dp).align(Alignment.Center),
        ) {
            // 长课名（研究生常是班级名）自动小一号：行数不变、每行装得更多，
            // 比"放开行数撑爆格子"稳（issue #6）
            val nameFont = (prefs.courseFontSp + ScheduleText.fontAdjust(session.courseName))
                .coerceAtLeast(prefs.courseFontSp - 2)
            Text(
                text = session.courseName,
                fontSize = nameFont.sp,
                lineHeight = (nameFont + 2).sp,
                // 合并后的块更高可以多放几行；单节块 3 行也放得下
                // （紧凑 34.5dp + 副行 ≈ 44dp < 48dp；标准 39 + 10.5 < 54）
                maxLines = if (span >= 2) 4 else 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )
            val secondary = buildList {
                // 格子里默认只放地点（教师名长、格子小，详情页能看到）。
                // 手动补录的课程若填了教师，跨节次（span >= 2）时多给一行 —— 单节块太矮，会挤掉课名。
                if (session.source == com.upcpocket.app.data.repo.ScheduleRepository.SOURCE_CUSTOM &&
                    span >= 2
                ) {
                    session.teacherName?.takeIf { it.isNotBlank() }
                        ?.let { add(SlotText.Kind.TEACHER to it) }
                }
                if (prefs.courseTextMode != CourseTextMode.NAME) {
                    add(SlotText.Kind.ROOM to ScheduleText.tidyPlace(session.roomText))
                }
            }
            secondary.forEachIndexed { index, (kind, line) ->
                if (line == null) return@forEachIndexed
                // 地点自适应（issue #6）：超长先缩一号、再「保头保尾」缩写（楼名…房号），
                // 行数按块高给（跨节 3 行、单节 1 行）；教师名维持单行。
                val text = if (kind == SlotText.Kind.ROOM) {
                    ScheduleText.abbreviatePlace(
                        line,
                        maxWeighted = if (span >= 2) 26 else 12,
                    ) ?: line
                } else {
                    line
                }
                val roomFont = if (kind == SlotText.Kind.ROOM) {
                    (prefs.roomFontSp + ScheduleText.fontAdjust(text))
                        .coerceAtLeast(prefs.roomFontSp - 1)
                } else {
                    prefs.roomFontSp
                }
                val maxLines = when {
                    kind != SlotText.Kind.ROOM -> 1
                    span >= 2 -> 3
                    else -> 1
                }
                Text(
                    text = text,
                    fontSize = roomFont.sp,
                    lineHeight = (roomFont + 1.5f).sp,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 副行的种类：教师 / 地点（地点才享受换行与缩写待遇） */
private object SlotText {
    enum class Kind { TEACHER, ROOM }
}

/**
 * 编辑框里的一个教室字段：同一时段里**教室相同**的教学周合成一组；
 * 单双周换教室（单周 302、双周 405）会拆成多组、各自带周次，
 * 覆盖键随之带上周次，互不串扰。[wholeSlot] = 该时段只有一种教室 → 一条覆盖管全部周。
 */
internal data class RoomEditSlot(
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val room: String?,
    val weeks: List<Int>,
    val wholeSlot: Boolean,
) {
    /** 与 [DisplayOverridesView.rooms] / [ScheduleViewModel.applyDisplayEdits] 约定的键 */
    val editKey: String
        get() = "$weekday|$startUnit" +
            (if (wholeSlot) "" else "|" + weeks.joinToString(","))

    /** 初始值：精确周的覆盖 → 全部周的覆盖 → 教务原值 */
    fun initialValue(rooms: Map<String, String>): String =
        rooms[editKey] ?: rooms["$weekday|$startUnit"] ?: room.orEmpty()
}

/** 按「时段 → 教室」两级归并出可编辑字段（与详情页分时段明细同一套思路） */
internal fun roomEditSlots(sessions: List<Session>): List<RoomEditSlot> =
    sessions.groupBy { it.weekday to it.startUnit }
        .flatMap { (slot, sameSlot) ->
            val byRoom = sameSlot.groupBy { it.roomText ?: "" }
            val groups = (if (byRoom.size == 1) listOf(sameSlot) else byRoom.values.toList())
                .map { g ->
                    val head = g.first()
                    RoomEditSlot(
                        weekday = slot.first,
                        startUnit = slot.second,
                        endUnit = head.endUnit,
                        room = head.roomText,
                        weeks = g.map { it.weekIndex }.distinct().sorted(),
                        wholeSlot = byRoom.size == 1,
                    )
                }
            groups
        }
        .sortedWith(compareBy({ it.weekday }, { it.startUnit }, { it.weeks.firstOrNull() ?: 0 }))

/**
 * 编辑一门课的**本地显示**（issue #6 保底）：课程名 + 各时段教室。
 * 只改本机显示，教务刷新不会覆盖；留空 = 恢复教务原值。
 * 同一时段不同周教室不同时按教室拆成多个字段（键带周次），不会互相覆盖。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDisplayDialog(
    course: Course,
    overrides: DisplayOverridesView?,
    onDismiss: () -> Unit,
    onSave: (String, Map<String, String>) -> Unit,
) {
    var name by remember(course) { mutableStateOf(overrides?.name ?: course.nameZh) }
    val slots = remember(course) { roomEditSlots(course.sessions) }
    val rooms = remember(course) {
        val initial = slots.associate { it.editKey to it.initialValue(overrides?.rooms ?: emptyMap()) }
        mutableStateMapOf(*initial.toList().toTypedArray())
    }
    // 多教室的时段（单双周换教室这类）要提示一次，用户才知道为什么拆成了几个字段
    val multiSlots = remember(course) {
        slots.groupBy { it.weekday to it.startUnit }
            .filterValues { it.size > 1 }
            .map { it.key }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑显示（仅本机）") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "改的是显示，不改教务数据；刷新课表不会被覆盖。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("课程名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                slots.forEachIndexed { i, s ->
                    val key = s.editKey
                    if ((s.weekday to s.startUnit) in multiSlots &&
                        slots.getOrNull(i - 1)?.let { it.weekday to it.startUnit } != (s.weekday to s.startUnit)
                    ) {
                        Text(
                            text = "该时段不同周教室不同，已按周拆分：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = if (s.wholeSlot) {
                            "${ScheduleText.weekdayName(s.weekday)} ${s.startUnit}-${s.endUnit} 节"
                        } else {
                            "${ScheduleText.weekdayName(s.weekday)} ${s.startUnit}-${s.endUnit} 节 · " +
                                ScheduleText.compressWeeks(s.weeks)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        value = rooms[key].orEmpty(),
                        onValueChange = { rooms[key] = it.take(20) },
                        label = { Text("教室（清空恢复默认）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), rooms.toMap()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 这节课此刻正在上 —— 供「正在上的课」描边使用。按真实日期判断，调休也正确。 */
private fun isLiveNow(
    s: Session,
    periods: List<Period>,
    todayIso: String,
    now: LocalTime,
): Boolean {
    if (s.date != todayIso) return false
    val startText = periods.firstOrNull { it.indexNo == s.startUnit }?.startText ?: s.startTime ?: return false
    val endText = periods.firstOrNull { it.indexNo == s.endUnit }?.endText ?: s.endTime ?: return false
    val start = runCatching { LocalTime.parse(startText) }.getOrNull() ?: return false
    val end = runCatching { LocalTime.parse(endText) }.getOrNull() ?: return false
    return !now.isBefore(start) && !now.isAfter(end)
}

/** 按课程代码取色：色相由哈希决定，明度按深浅色模式调整，保证文字始终可读 */
private fun courseColor(code: String, dark: Boolean): Color {
    val hue = (((code.hashCode() % 360) + 360) % 360).toFloat()
    return if (dark) {
        Color.hsl(hue, 0.35f, 0.26f)
    } else {
        Color.hsl(hue, 0.55f, 0.88f)
    }
}

/**
 * 圆角虚线描边。
 *
 * Compose 没有内置虚线组件，用 drawBehind + dashPathEffect 自己画。
 * 虚线用来把相邻、颜色接近的课程块分开，避免连成一片看不出边界。
 */
private fun Modifier.dashedBorder(
    color: Color,
    dash: Dp = 4.dp,
    gap: Dp = 3.dp,
    strokeWidth: Dp = 1.dp,
    radius: Dp = 6.dp,
): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        style = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx())),
        ),
    )
}

@Composable
private fun NoSemesterBlock(
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text("正在读取本地课表…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("还没有课表内容", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "两种开始方式：打开「演示数据」先看看界面，或者点右上角 + 自己添加日程。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onRefresh) { Text("重新读取") }
        }
    }
}

/**
 * 演示模式横幅。
 *
 * 课表页、今天页都用它。文案刻意分成两行：第一行是结论（这是演示数据），
 * 第二行说明会影响什么（演示课不落库、关掉就消失），点击进设置。
 */
@Composable
internal fun DemoBanner(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onOpenSettings),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = com.upcpocket.app.data.local.DemoTimetable.BANNER,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "带「演示」标记的课是虚构的，只存在内存里；关掉后只剩你自己添加的日程。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetailSheet(
    course: Course,
    onDismiss: () -> Unit,
    /** 该课的显示覆盖；有则显示「已自定义」与编辑/恢复入口 */
    overrides: DisplayOverridesView? = null,
    /** 最新节次作息：详情里的时间与"每周课时"都优先按它算（改作息后要跟着变） */
    periods: List<Period> = emptyList(),
    onApplyEdits: ((Course, String, Map<String, String>) -> Unit)? = null,
    onResetOverrides: ((Course) -> Unit)? = null,
    /** 演示课：详情只读，明确写出"演示数据、不可编辑" */
    demo: Boolean = false,
) {
    var editOpen by remember(course) { mutableStateOf(false) }
    if (editOpen && onApplyEdits != null) {
        EditDisplayDialog(
            course = course,
            overrides = overrides,
            onDismiss = { editOpen = false },
            onSave = { name, rooms ->
                onApplyEdits(course, name, rooms)
                editOpen = false
            },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    course.nameZh,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (demo) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "演示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.tertiaryContainer,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (overrides?.any == true) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "已自定义",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            if (demo) {
                Text(
                    text = "这是虚构的演示课程，用来体验界面；它不写数据库，也不能编辑。" +
                        "关掉「演示数据」后就会消失，你自己添加的日程不受影响。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 显示编辑：楼名重复、地点太长这类显示问题，用户可自己改成顺眼的
            if (onApplyEdits != null) {
                Row {
                    TextButton(onClick = { editOpen = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (overrides?.any == true) "改显示" else "编辑显示")
                    }
                    if (overrides?.any == true && onResetOverrides != null) {
                        TextButton(onClick = { onResetOverrides(course) }) { Text("恢复默认") }
                    }
                }
            }
            if (course.teachers.isNotEmpty()) {
                DetailRow("教师", course.teachers.joinToString("、"))
            }
            val rooms = course.roomTexts.mapNotNull { ScheduleText.tidyPlace(it) }.distinct()
            if (rooms.isNotEmpty()) DetailRow("地点", rooms.joinToString("、"))
            // 时间口径与网格/今天/组件保持一致：**先读最新的节次表**，
            // 只有节次缺失（用户删过作息）才退回记录里那份写入时的快照。
            // 早期这里直接读 session.startTime，改完作息后详情还显示旧时间（复核报告 P2-2）。
            val clock = course.sessions.firstOrNull()?.let { s ->
                val start = periods.firstOrNull { it.indexNo == s.startUnit }?.startText ?: s.startTime
                val end = periods.firstOrNull { it.indexNo == s.endUnit }?.endText ?: s.endTime
                listOfNotNull(start, end).takeIf { it.size == 2 }?.joinToString("–")
            }
            DetailRow("时间", listOfNotNull(ScheduleText.timeSlots(course.slots), clock).joinToString(" · "))
            DetailRow("周次", ScheduleText.compressWeeks(course.weeks))
            // 演示课的 code 是内部编号，没有展示价值
            if (!demo && course.code.isNotBlank() && course.code != course.nameZh) {
                DetailRow("课程代码", course.code)
            }
            course.openDepartment?.takeIf { it.isNotBlank() }?.let { DetailRow("开课院系", it) }

            // 计划 vs 实际：计划 = 每周课表时长，实际 = 这门课累计的番茄钟时长。
            // 演示课不给"开始专注"：那会把虚构课名写进用户真实的专注统计里。
            CourseFocusSection(
                course = course,
                periods = periods,
                onStartFocus = onDismiss,
                allowStart = !demo,
            )

            // 按「时段 → (地点, 教师)」归并：整学期都一样就只列一行，真的不同才拆开。
            // 只有一行时没必要显示（上面的汇总已经说清楚了）。
            val slotLines = course.sessions.toSlotLines()
            if (slotLines.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("分时段明细", style = MaterialTheme.typography.titleSmall)
                slotLines.forEach { line ->
                    val unitText =
                        if (line.startUnit == line.endUnit) "${line.startUnit}节"
                        else "${line.startUnit}-${line.endUnit}节"
                    val detail = listOfNotNull(
                        line.room,
                        line.teacher,
                        ScheduleText.compressWeeks(line.weeks),
                    ).joinToString(" · ")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            text = "${ScheduleText.weekdayName(line.weekday)} $unitText",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(104.dp),
                        )
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 课程详情里的「计划 vs 实际」。
 *
 * 计划 = 每周课表上这门课的时长；实际 = 这门课累计的番茄钟专注时长。
 * 关联靠 [Session] 的 contextRef（`course:<key>`）—— 从课表点"开始专注"时写进去的。
 */
@Composable
private fun CourseFocusSection(
    course: Course,
    onStartFocus: () -> Unit,
    /** 最新节次作息；为空时退回记录里的时间快照 */
    periods: List<Period> = emptyList(),
    /** 演示课不给"开始专注"入口（避免把虚构课名写进真实统计） */
    allowStart: Boolean = true,
) {
    val records by AppGraph.focusRepository
        .observeRecords(0L)
        .collectAsStateWithLifecycle(initialValue = emptyList<FocusRecord>())

    val ref = "course:${course.key}"
    val focusedSeconds = records
        .filter { it.contextRef == ref && it.phase == Phase.FOCUS }
        .sumOf { it.actualSeconds }
    // 每周课时同样以**最新节次表**为准：用户把第 1 节从 08:00 改到 08:30 之后，
    // 这里必须跟着变，否则"计划 vs 实际"里的计划是个过期数字（复核报告 P2-2）。
    val periodsByUnit = periods.associateBy { it.indexNo }
    val plannedMinutes = course.sessions
        .distinctBy { Triple(it.weekday, it.startUnit, it.endUnit) }
        .sumOf { s ->
            val start = periodsByUnit[s.startUnit]?.startText ?: s.startTime
            val end = periodsByUnit[s.endUnit]?.endText ?: s.endTime
            minutesBetween(start, end)
        }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("计划 vs 实际", style = MaterialTheme.typography.titleSmall)
    if (plannedMinutes > 0) {
        DetailRow("每周课时", FocusStats.humanDuration(plannedMinutes * 60))
    }
    DetailRow(
        "番茄钟累计",
        if (focusedSeconds > 0) FocusStats.humanDuration(focusedSeconds) else "还没有记录",
    )
    if (allowStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                startFocusFor(name = course.nameZh, contextRef = ref)
                onStartFocus()
            }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("开始专注（标签：${course.nameZh}）")
            }
        }
    }
}

/** "08:00" + "09:40" → 100 分钟；拿不到时间就返回 0 */
private fun minutesBetween(start: String?, end: String?): Int {
    val a = parseHhmm(start) ?: return 0
    val b = parseHhmm(end) ?: return 0
    return (b - a).coerceAtLeast(0)
}

private fun parseHhmm(text: String?): Int? {
    val raw = text?.trim().orEmpty()
    val parts = raw.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return h * 60 + m
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemesterSheet(
    semesters: List<Semester>,
    selectedId: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text(
                "选择学期",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            semesters.groupBy { it.schoolYear }.forEach { (year, list) ->
                Text(
                    text = year.ifBlank { "其他" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                list.forEach { s ->
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onPick(s.id) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = s.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (s.isCurrent) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "本学期",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (s.id == selectedId) {
                                Text(
                                    "当前",
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
}

@Composable
private fun WeekPickerDialog(
    weeks: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到教学周") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                weeks.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { w ->
                            val selected = w == current
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onPick(w) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$w", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
