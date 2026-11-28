package com.upcpocket.app.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.CircularProgressIndicator
import com.upcpocket.app.ui.components.GlassDropdownMenu as DropdownMenu
import com.upcpocket.app.ui.components.GlassDropdownMenuItem as DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.upcpocket.app.ui.components.GlassIconButton as IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.upcpocket.app.ui.components.GlassModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.Scaffold
import com.upcpocket.app.ui.components.GlassSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.LocalPluginPageActive
import com.upcpocket.app.ui.components.CampusSegmentedTabs
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.ReorderableModuleColumn
import com.upcpocket.app.ui.components.glassHighlight
import com.upcpocket.app.ui.components.glassPane
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.StatusPill
import com.upcpocket.app.ui.components.mergeReorderedSubset
import com.upcpocket.app.ui.components.normalizeModuleOrder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * 课表页可长按拖动排序的功能模块标识。
 *
 * 仅控制展示顺序；日程待办是否合入课表页仍由宿主 [LocalTimetableTodoSlot] 决定。
 */
enum class TimetableModuleId(val label: String) {
    TODO_SCHEDULE("日程待办"),
    TIMETABLE("周课表");

    companion object {
        val DEFAULT_ORDER: List<TimetableModuleId> = listOf(TODO_SCHEDULE, TIMETABLE)

        fun fromStoredValue(raw: String?): List<TimetableModuleId> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val parsed = raw.split(',')
                .mapNotNull { token -> entries.firstOrNull { it.name == token.trim() } }
            return normalizeModuleOrder(parsed, DEFAULT_ORDER)
        }

        fun toStoredValue(order: List<TimetableModuleId>): String =
            normalizeModuleOrder(order, DEFAULT_ORDER).joinToString(",") { it.name }
    }
}

/** 宿主注入的课表页模块排序偏好、拖动开关与写回回调。 */
data class TimetableModuleLayout(
    val order: List<TimetableModuleId> = TimetableModuleId.DEFAULT_ORDER,
    /** 是否允许长按拖动；关闭时仍按 [order] 渲染并锁定顺序。 */
    val reorderEnabled: Boolean = false,
    val onOrderChange: (List<TimetableModuleId>) -> Unit = {},
)

/**
 * 课表页展示范围。
 *
 * [ALL] 同时显示课程和本机日程待办/个人日程；[COURSES_ONLY] 只保留课程网格、
 * 学期/选周与课程详情。切换只改变展示，不触发学校请求，也不改变已选教学周。
 */
enum class TimetableDisplayMode(val label: String) {
    ALL("全部"),
    COURSES_ONLY("仅看课程");

    companion object {
        /** DataStore 只保存枚举名；缺键或未知值回退 [ALL]。 */
        fun fromStoredValue(raw: String?): TimetableDisplayMode =
            entries.firstOrNull { it.name == raw } ?: ALL
    }
}

/**
 * 宿主注入的课表页展示范围偏好与写回回调。
 *
 * 未注入时默认 [TimetableDisplayMode.ALL] 且不提供写回回调，直接挂载待办槽位或
 * 模块排序的旧调用者保持原有内容与控件；只有宿主显式把 [mode] 传为 null 才表示
 * 本机偏好尚未读到：页面暂不渲染切换控件，并按“仅看课程”隐藏本机日程内容，
 * 避免先露出可能应隐藏的卡片后再收起。
 */
data class TimetableDisplayPreference(
    val mode: TimetableDisplayMode? = TimetableDisplayMode.ALL,
    val onModeChange: ((TimetableDisplayMode) -> Unit)? = null,
)

val LocalTimetableDisplayPreference = compositionLocalOf { TimetableDisplayPreference() }

/**
 * 宿主装配的课表页顶部槽位。
 *
 * 只承载本机日程待办等宿主注入内容；不参与课表账号、登录或周查询状态。槽位与课表
 * 主体彼此独立，即使账号未保存或受阻也会完整渲染。
 */
val LocalTimetableTodoSlot = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** 课表页模块排序偏好；未提供时回退为 [TimetableModuleId.DEFAULT_ORDER]。 */
val LocalTimetableModuleLayout = compositionLocalOf { TimetableModuleLayout() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    service: TimetableService,
    onOpenAccountSettings: () -> Unit,
    onOpenCampusLocation: ((String) -> Unit)? = null,
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
        onOpenCampusLocation = onOpenCampusLocation,
    )
}

private fun timetableModuleKey(module: TimetableModuleId): Any = module.name
private fun timetableModuleLabel(module: TimetableModuleId): String = module.label
private fun timetableModuleTestTag(module: TimetableModuleId): String =
    "timetable_module_${module.name.lowercase()}"

@Composable
private fun TimetableActiveEffect(viewModel: TimetableViewModel) {
    val active = LocalPluginPageActive.current
    LaunchedEffect(active, viewModel) {
        if (active) viewModel.start()
    }
}

@Composable
private fun TimetableHomeActiveEffect(
    service: TimetableService,
    accountEpoch: Long,
) {
    val active = LocalPluginPageActive.current
    // 首页激活或账号代次变化时恢复一次本机缓存；时钟只由 TodayPanel 在 UI 本地更新。
    LaunchedEffect(active, accountEpoch, service) {
        if (!active) return@LaunchedEffect
        service.restoreTodayFromCache()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableScreen(
    viewModel: TimetableViewModel,
    onOpenAccountSettings: () -> Unit,
    onOpenCampusLocation: ((String) -> Unit)? = null,
) {
    TimetableActiveEffect(viewModel)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dockClearance = LocalDockClearance.current
    var detail by remember { mutableStateOf<TimetableMeetingSelection?>(null) }
    var loginVisible by remember { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var loginHint by remember { mutableStateOf<String?>(null) }
    var loginEpoch by remember { mutableLongStateOf(0L) }
    var infoVisible by remember { mutableStateOf(false) }
    val visibleDetail = if (state.accountManagedActive && !state.accountBlocked) {
        resolveMeetingSelection(detail, state.accountEpoch, state.meetings)
    } else {
        null
    }

    LaunchedEffect(
        state.accountEpoch,
        state.accountBlocked,
        state.accountManagedActive,
        state.meetings,
    ) {
        if (state.accountBlocked || !state.accountManagedActive) {
            detail = null
            loginVisible = false
            loginHint = null
            viewModel.cancelInteractiveLogin()
        } else if (detail != null && visibleDetail == null) {
            detail = null
        }
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

    val scrollState = rememberScrollState()
    val todoSlot = LocalTimetableTodoSlot.current
    val personalAgenda = LocalTimetablePersonalAgenda.current
    val displayPreference = LocalTimetableDisplayPreference.current
    val onDisplayModeChange = displayPreference.onModeChange
    // 没有日程待办或个人日程能力时，切换控件没有可隐藏的内容，直接按课程展示。
    val hasLocalAgendaContent = todoSlot != null || personalAgenda != null
    // 未注入偏好时默认「全部」；宿主显式传 null 表示尚未读到，先按“仅看课程”处理。
    val coursesOnly = hasLocalAgendaContent &&
        displayPreference.mode != TimetableDisplayMode.ALL
    // 同一个可见性条件过滤待办槽位与个人日程：仅课程时二者均不进入组合，相关的
    // 窗口订阅、30 秒计时、日期入口和弹层随之停止或撤下，课程与选周保持不变。
    val visibleTodoSlot = todoSlot.takeUnless { coursesOnly }
    val visiblePersonalAgenda = personalAgenda.takeUnless { coursesOnly }
    // 日期与当日列表都是本机状态；保存 epochDay，配置变更后仍停留在同一日期。
    var agendaEpochDay by rememberSaveable {
        mutableLongStateOf(LocalDate.now(TIMETABLE_ZONE).toEpochDay())
    }
    var agendaSheetEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val agendaDate = LocalDate.ofEpochDay(agendaEpochDay)
    val agendaNowMillis = if (visiblePersonalAgenda != null) {
        rememberAgendaNowMillis()
    } else {
        null
    }
    val agendaDayState = if (visiblePersonalAgenda != null) {
        visiblePersonalAgenda.windowState(timetableAgendaDayWindow(agendaDate))
    } else {
        null
    }
    // 周网格标记只读取当前实际周列日期；未查询或日期不完整时不推断。
    val weekAgendaWindow = if (visiblePersonalAgenda == null) {
        null
    } else {
        remember(state.weekDates) {
            timetableAgendaWeekWindow(state.weekDates)
                ?.takeIf { state.weekDates.size == 7 }
        }
    }
    val weekAgendaState = if (visiblePersonalAgenda != null && weekAgendaWindow != null) {
        visiblePersonalAgenda.windowState(weekAgendaWindow)
    } else {
        null
    }
    val daysWithAgenda: Set<LocalDate> = if (weekAgendaState == null) {
        emptySet()
    } else {
        remember(weekAgendaState, state.weekDates) {
            val entries = (weekAgendaState as? TimetableAgendaState.Ready)?.entries.orEmpty()
            if (entries.isEmpty() || state.weekDates.size != 7) {
                emptySet()
            } else {
                state.weekDates.filterTo(mutableSetOf()) { date ->
                    entries.any { timetableAgendaCoversDate(it, date) }
                }
            }
        }
    }
    val sheetEpochDay = agendaSheetEpochDay
    val agendaSheetDate = sheetEpochDay?.let { LocalDate.ofEpochDay(it) }
    val agendaSheetState = if (visiblePersonalAgenda != null && agendaSheetDate != null) {
        visiblePersonalAgenda.windowState(timetableAgendaDayWindow(agendaSheetDate))
    } else {
        null
    }
    // 切到仅课程时撤下已打开的日期日程弹层，切回后不自动重开。
    LaunchedEffect(coursesOnly) {
        if (coursesOnly) agendaSheetEpochDay = null
    }
    val moduleLayout = LocalTimetableModuleLayout.current
    val normalizedOrder = remember(moduleLayout.order) {
        normalizeModuleOrder(moduleLayout.order, TimetableModuleId.DEFAULT_ORDER)
    }
    val visibleModules = remember(normalizedOrder, visibleTodoSlot != null) {
        normalizedOrder.filter { module ->
            module != TimetableModuleId.TODO_SCHEDULE || visibleTodoSlot != null
        }
    }
    val onReorderFinished = remember(normalizedOrder, moduleLayout.onOrderChange) {
        { reordered: List<TimetableModuleId> ->
            moduleLayout.onOrderChange(mergeReorderedSubset(normalizedOrder, reordered))
        }
    }
    val timetableIsGrouped = !state.accountBlocked && state.accountManagedActive

    Scaffold(
        containerColor = com.upcpocket.app.ui.theme.appScaffoldContainerColor(),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
                title = { Text("课表") },
                actions = {
                    IconButton(onClick = { infoVisible = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "课表说明")
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
                .verticalScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp + dockClearance),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasLocalAgendaContent && displayPreference.mode != null &&
                onDisplayModeChange != null
            ) {
                CampusSegmentedTabs(
                    tabs = TimetableDisplayMode.entries.map { it.label },
                    selectedIndex = if (coursesOnly) {
                        TimetableDisplayMode.COURSES_ONLY.ordinal
                    } else {
                        TimetableDisplayMode.ALL.ordinal
                    },
                    onSelect = { index ->
                        TimetableDisplayMode.entries.getOrNull(index)
                            ?.let(onDisplayModeChange)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("timetable_display_mode"),
                )
            }
            ReorderableModuleColumn(
                items = visibleModules,
                key = ::timetableModuleKey,
                scrollState = scrollState,
                spacing = 8.dp,
                // 仅看课程时只有周课表一项，不需要拖动，也不写回排序。
                enabled = moduleLayout.reorderEnabled && !coursesOnly,
                itemLabel = ::timetableModuleLabel,
                itemTestTag = ::timetableModuleTestTag,
                itemGrouped = { module ->
                    module == TimetableModuleId.TIMETABLE && timetableIsGrouped
                },
                onReorderFinished = onReorderFinished,
            ) { module, _ ->
                when (module) {
                    TimetableModuleId.TODO_SCHEDULE -> {
                        visibleTodoSlot?.invoke()
                    }
                    TimetableModuleId.TIMETABLE -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 个人日程只读本机，不依赖学校账号、登录或周查询；在
                            // 「全部」下提供日期入口与新增/编辑，「仅看课程」时隐藏。
                            if (visiblePersonalAgenda != null && agendaDayState != null) {
                                TimetablePersonalAgendaCard(
                                    state = agendaDayState,
                                    selectedDate = agendaDate,
                                    // 只有查看上海实际今天时才标「进行中/已结束」。
                                    nowMinute = agendaNowMillis?.let {
                                        agendaMinuteForDate(it, agendaDate)
                                    },
                                    showShanghaiTime = timetableAgendaNeedsShanghaiNote(),
                                    onSelectDate = { agendaEpochDay = it.toEpochDay() },
                                    onCreate = visiblePersonalAgenda::openCreate,
                                    onEdit = visiblePersonalAgenda::openEdit,
                                )
                            }
                            when {
                                state.accountBlocked -> {
                                    AccountBlockedCard(
                                        message = state.message ?: "学校账号暂不可用。",
                                        onOpenAccountSettings = onOpenAccountSettings,
                                    )
                                }
                                !state.accountManagedActive -> {
                                    FirstRunCard(
                                        isLoading = state.connection == TimetableConnection.LOADING,
                                        onOpenAccountSettings = onOpenAccountSettings,
                                    )
                                }
                                else -> {
                                    if (state.connection != TimetableConnection.CONNECTED ||
                                        state.message != null ||
                                        (!state.isBusy && state.terms.isEmpty())
                                    ) {
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
                                            NoTermsCard()
                                        }
                                    } else {
                                        // 页面以学期/周选择和周网格为主；今日课程由插件贡献到首页。
                                        TermAndWeekHeader(
                                            state = state,
                                            onSelectTerm = viewModel::selectTerm,
                                            onSelectWeek = viewModel::selectWeek,
                                        )
                                        WeekPanel(
                                            state,
                                            onCourseClick = {
                                                detail = it.selection(state.accountEpoch)
                                            },
                                            daysWithAgenda = daysWithAgenda,
                                            onAgendaDateClick = visiblePersonalAgenda?.let { agenda ->
                                                { date ->
                                                    agendaEpochDay = date.toEpochDay()
                                                    agendaSheetEpochDay = date.toEpochDay()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }

    visibleDetail?.let { meeting ->
        CourseDetailBottomSheet(
            meeting = meeting,
            onDismiss = { detail = null },
            onOpenCampusLocation = onOpenCampusLocation,
        )
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
        state.accountManagedActive && !state.accountBlocked
    ) {
        TimetableLoginDialog(
            url = loginUrl!!,
            hint = loginHint,
            onClose = {
                viewModel.cancelInteractiveLogin()
                loginVisible = false
                loginHint = null
            },
        )
    }
    if (visiblePersonalAgenda != null && agendaSheetDate != null &&
        agendaSheetState != null
    ) {
        TimetableAgendaDaySheet(
            state = agendaSheetState,
            date = agendaSheetDate,
            // 弹窗可能停留在昨天或明天，只有实际今天才显示进行状态。
            nowMinute = agendaNowMillis?.let { agendaMinuteForDate(it, agendaSheetDate) },
            showShanghaiTime = timetableAgendaNeedsShanghaiNote(),
            onEdit = { id ->
                agendaSheetEpochDay = null
                visiblePersonalAgenda.openEdit(id)
            },
            onCreate = { date ->
                agendaSheetEpochDay = null
                visiblePersonalAgenda.openCreate(date)
            },
            onDismiss = { agendaSheetEpochDay = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableHomeSection(
    service: TimetableService,
    onOpenTimetable: () -> Unit,
    onOpenAccountSettings: () -> Unit,
    onOpenCampusLocation: ((String) -> Unit)? = null,
) {
    val state by service.todayState.collectAsStateWithLifecycle()
    TimetableHomeActiveEffect(service = service, accountEpoch = state.accountEpoch)
    var detail by remember { mutableStateOf<TimetableMeetingSelection?>(null) }
    var actualToday by remember { mutableStateOf(LocalDate.now(TIMETABLE_ZONE)) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now(TIMETABLE_ZONE)
            val nextDate = now.toLocalDate().plusDays(1).atStartOfDay(TIMETABLE_ZONE)
            delay(Duration.between(now, nextDate).toMillis().coerceAtLeast(1L))
            actualToday = LocalDate.now(TIMETABLE_ZONE)
        }
    }

    val personalAgenda = LocalTimetablePersonalAgenda.current
    val agendaState = if (personalAgenda != null) {
        personalAgenda.windowState(timetableAgendaDayWindow(actualToday))
    } else {
        null
    }
    val visibleDetail = if (state.accountManagedActive && !state.accountBlocked &&
        state.today == actualToday
    ) {
        resolveMeetingSelection(detail, state.accountEpoch, state.todayMeetings)
    } else {
        null
    }
    LaunchedEffect(
        state.accountEpoch,
        state.accountBlocked,
        state.accountManagedActive,
        state.today,
        actualToday,
        state.todayMeetings,
    ) {
        if (state.accountBlocked || !state.accountManagedActive ||
            state.today != actualToday ||
            (detail != null && visibleDetail == null)
        ) {
            detail = null
        }
    }

    TodayPanel(
        state = state,
        showDate = false,
        onCourseClick = { detail = it.selection(state.accountEpoch) },
        onOpenTimetable = onOpenTimetable,
        onOpenAccountSettings = onOpenAccountSettings,
        agenda = agendaState,
        onAgendaClick = personalAgenda?.let { agenda ->
            { id -> agenda.openEdit(id) }
        },
        onAgendaCreate = personalAgenda?.let { agenda ->
            { agenda.openCreate(actualToday) }
        },
    )
    visibleDetail?.let { meeting ->
        CourseDetailBottomSheet(
            meeting = meeting,
            onDismiss = { detail = null },
            onOpenCampusLocation = onOpenCampusLocation,
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
            Text("校方托管账号需要更新", style = MaterialTheme.typography.titleLarge)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            com.upcpocket.app.ui.components.CampusButton(onClick = onOpenAccountSettings) {
                Text("管理托管账号")
            }
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
            Text("连接课表", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "先绑定校方托管账号，再点击右上角刷新读取课表。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            com.upcpocket.app.ui.components.CampusButton(
                onClick = onOpenAccountSettings,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("绑定托管账号") }
            Text(
                text = "学校要求验证时，请按提示打开官方页面完成验证。",
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
                "正在读取课表…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoTermsCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("尚未取得学期", style = MaterialTheme.typography.titleMedium)
            Text(
                "绑定校方托管账号后点击右上角刷新；如需验证，请按提示打开学校官方页面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val rangeStart = state.weekDates.minOrNull()
    val rangeEnd = state.weekDates.maxOrNull()
    val selectedWeek = state.selectedWeek
    val fontScale = LocalDensity.current.fontScale

    GlassCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            val stackRows = maxWidth < 300.dp || fontScale > 1.15f
            if (stackRows) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TermSelector(
                        term = term,
                        terms = state.terms,
                        onSelectTerm = onSelectTerm,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WeekSelector(
                        term = term,
                        selectedWeek = selectedWeek,
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd,
                        onSelectWeek = onSelectWeek,
                        modifier = Modifier.fillMaxWidth(),
                        fillWeekButton = true,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TermSelector(
                        term = term,
                        terms = state.terms,
                        onSelectTerm = onSelectTerm,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    WeekSelector(
                        term = term,
                        selectedWeek = selectedWeek,
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd,
                        onSelectWeek = onSelectWeek,
                        fillWeekButton = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun TermSelector(
    term: TimetableTerm,
    terms: List<TimetableTerm>,
    onSelectTerm: (TimetableTerm) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        com.upcpocket.app.ui.components.CampusTextButton(
            onClick = { menuOpen = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    term.label,
                    modifier = Modifier.fillMaxWidth().testTag("timetable_term_label"),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "共 ${term.totalWeeks} 周",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
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
            terms.forEach { item ->
                DropdownMenuItem(
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
}

@Composable
private fun WeekSelector(
    term: TimetableTerm,
    selectedWeek: Int?,
    rangeStart: LocalDate?,
    rangeEnd: LocalDate?,
    onSelectWeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillWeekButton: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { selectedWeek?.let { onSelectWeek((it - 1).coerceAtLeast(1)) } },
            enabled = selectedWeek != null && selectedWeek > 1,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周")
        }
        Box(Modifier.then(if (fillWeekButton) Modifier.weight(1f) else Modifier)) {
            com.upcpocket.app.ui.components.CampusTextButton(
                onClick = { menuOpen = true },
                modifier = if (fillWeekButton) Modifier.fillMaxWidth() else Modifier,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                (1..term.totalWeeks).forEach { week ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "第 $week 周",
                                fontWeight = if (selectedWeek == week) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onSelectWeek(week)
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = {
                selectedWeek?.let { onSelectWeek((it + 1).coerceAtMost(term.totalWeeks)) }
            },
            enabled = selectedWeek != null && selectedWeek < term.totalWeeks,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周")
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
    val tone = connectionTone(state.connection)
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("课表状态", style = MaterialTheme.typography.titleSmall)
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
            if (!state.isBusy && state.connection == TimetableConnection.IDLE) {
                Text(
                    "校方托管账号已绑定，点击右上角刷新读取课表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (state.connection == TimetableConnection.INTERACTION_REQUIRED) {
                Text(
                    "请在学校官方页面完成验证后返回重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            state.weekFetchedAtEpochMillis?.let { fetched ->
                Text(
                    "更新于 ${formatFetchedAt(fetched)}" +
                        if (state.weekState == TimetableWeekState.CACHED) " · 缓存" else "",
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
                    com.upcpocket.app.ui.components.CampusButton(onClick = onOpenLogin) {
                        Text("打开学校官方页")
                    }
                }
                if (state.canRetry && !state.isBusy) {
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = onRetry) { Text("重试") }
                }
                if (state.connection == TimetableConnection.NOT_CONFIGURED ||
                    state.connection == TimetableConnection.AUTH_FAILED ||
                    state.accountManagedActive
                ) {
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = onOpenAccountSettings) { Text("学校账号") }
                }
                if (state.connection != TimetableConnection.INTERACTION_REQUIRED &&
                    state.connection != TimetableConnection.NOT_CONFIGURED &&
                    !state.accountBlocked
                ) {
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = onOpenWebLogin) { Text("网页登录") }
                }
            }
        }
    }
}

@Composable
internal fun WeekPanel(
    state: TimetableUiState,
    onCourseClick: (TimetableMeeting) -> Unit,
    daysWithAgenda: Set<LocalDate> = emptySet(),
    onAgendaDateClick: ((LocalDate) -> Unit)? = null,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                when (state.weekState) {
                    TimetableWeekState.READY -> StatusPill(
                        text = "已更新",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    TimetableWeekState.CACHED -> StatusPill(
                        text = "缓存",
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
                    EmptyWeekHint("这一周还没有查询，点击右上角刷新。")
                TimetableWeekState.ERROR ->
                    EmptyWeekHint("查询失败，暂无缓存。")
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
                    WeekGrid(state, onCourseClick, daysWithAgenda, onAgendaDateClick)
                }
                TimetableWeekState.EMPTY -> EmptyWeekHint("本周无课程")
                TimetableWeekState.READY,
                TimetableWeekState.CACHED,
                -> WeekGrid(state, onCourseClick, daysWithAgenda, onAgendaDateClick)
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

// 常规行高沿用原版 44dp；大字号按比例扩展，窄窗口通过横滑保持可读宽度。
// 13–14 节没有学校官方时刻，显示“待确认”，不按等差推算，也不新增第 15 节。
private val BASE_SLOT_HEIGHT = 44.dp
private const val PERIODS = 14
private val PERIOD_COLUMN_WIDTH = 48.dp
private val BASE_DAY_HEADER_HEIGHT = 40.dp
private val BASE_MIN_DAY_WIDTH = 37.dp

private val CARD_DETAIL_MIN_WIDTH = 30.dp
private val CARD_COMPACT_MIN_WIDTH = 24.dp

@Composable
private fun WeekGrid(
    state: TimetableUiState,
    onCourseClick: (TimetableMeeting) -> Unit,
    daysWithAgenda: Set<LocalDate> = emptySet(),
    onAgendaDateClick: ((LocalDate) -> Unit)? = null,
) {
    var actualToday by remember { mutableStateOf(LocalDate.now(TIMETABLE_ZONE)) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now(TIMETABLE_ZONE)
            val nextDate = now.toLocalDate().plusDays(1).atStartOfDay(TIMETABLE_ZONE)
            delay(Duration.between(now, nextDate).toMillis().coerceAtLeast(1L))
            actualToday = LocalDate.now(TIMETABLE_ZONE)
        }
    }
    val view = remember(state.weekDates, state.meetings) {
        runCatching { buildTimetableWeekView(state.weekDates, state.meetings) }.getOrNull()
    } ?: return
    val byDate = view.meetings.groupBy { it.date }
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val standardSlotHeight = if (fontScale > 1.15f) {
        58.dp * fontScale
    } else {
        maxOf(BASE_SLOT_HEIGHT, BASE_SLOT_HEIGHT * fontScale)
    }
    val dayHeaderHeight = maxOf(BASE_DAY_HEADER_HEIGHT, BASE_DAY_HEADER_HEIGHT * fontScale)
    val periodColumnWidth = maxOf(PERIOD_COLUMN_WIDTH, 34.dp * fontScale)
    val minDayWidth = maxOf(BASE_MIN_DAY_WIDTH, BASE_MIN_DAY_WIDTH * fontScale)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = (maxWidth - periodColumnWidth).coerceAtLeast(0.dp)
        val dayWidth = (available / 7f).coerceAtLeast(minDayWidth)
        val slotHeight = standardSlotHeight
        // 仅当最小日宽也放不下七天时横滑，避免平均除法的浮点余差误触发。
        val needsScroll = minDayWidth * 7 + periodColumnWidth > maxWidth
        Row(
            Modifier.then(
                if (needsScroll) {
                    Modifier.horizontalScroll(rememberScrollState())
                } else {
                    Modifier
                },
            ).testTag("timetable_week_grid"),
        ) {
            Column(Modifier.width(periodColumnWidth)) {
                Spacer(Modifier.height(dayHeaderHeight))
                repeat(PERIODS) { index ->
                    val period = index + 1
                    val range = UpcTimetableSchedule.period(period)
                    Column(
                        Modifier
                            .height(slotHeight)
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
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                range.endLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Text(
                                "待确认",
                                color = MaterialTheme.colorScheme.outline,
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            view.dates.forEach { date ->
                DayColumn(
                    date = date,
                    width = dayWidth,
                    slotHeight = slotHeight,
                    dayHeaderHeight = dayHeaderHeight,
                    isToday = date == actualToday,
                    known = date in view.knownDates,
                    hasAgenda = date in daysWithAgenda,
                    meetings = byDate[date].orEmpty(),
                    colorSlots = state.courseColorSlots,
                    onCourseClick = onCourseClick,
                    onAgendaClick = onAgendaDateClick?.let { callback ->
                        { callback(date) }
                    },
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    date: LocalDate,
    width: Dp,
    slotHeight: Dp,
    dayHeaderHeight: Dp,
    isToday: Boolean,
    known: Boolean,
    meetings: List<TimetableMeeting>,
    colorSlots: Map<String, Int>,
    onCourseClick: (TimetableMeeting) -> Unit,
    hasAgenda: Boolean = false,
    onAgendaClick: (() -> Unit)? = null,
) {
    val laneInfo = remember(meetings) { assignLanes(meetings) }
    Column(
        Modifier
            .width(width)
            .padding(horizontal = 0.5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(dayHeaderHeight)
                .testTag("timetable_day_header_$date")
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .glassPane(
                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                    color = if (isToday) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    } else {
                        Color.Transparent
                    },
                )
                .then(
                    if (onAgendaClick != null) {
                        Modifier.clickable(
                            onClickLabel = "查看 ${date.monthValue}月${date.dayOfMonth}日个人日程",
                            role = Role.Button,
                            onClick = onAgendaClick,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
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
            if (hasAgenda) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 3.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                        .testTag("timetable_day_agenda_$date"),
                )
            }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(slotHeight * PERIODS)
                .testTag("timetable_day_slots_$date")
                .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                .glassPane(
                    shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp),
                    color = if (isToday) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.74f)
                    },
                ),
        ) {
            val gridLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val stroke = 0.5.dp.toPx()
                        for (period in 2 until PERIODS step 2) {
                            val y = slotHeight.toPx() * period
                            drawLine(
                                color = gridLine,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = stroke,
                            )
                        }
                    }
                    .border(
                        0.5.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
                        RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp),
                    ),
            )
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

            meetings.forEachIndexed { index, meeting ->
                val dayLane = laneInfo.getOrElse(index) { DayLane(lane = 0, laneCount = 1) }
                val laneWidth = maxWidth / dayLane.laneCount.coerceAtLeast(1)
                val cardWidth = (laneWidth - 2.dp).coerceAtLeast(1.dp)
                val periodCount = meeting.endPeriod - meeting.startPeriod + 1
                val top = slotHeight * (meeting.startPeriod - 1)
                val height = slotHeight * periodCount - 2.dp
                val tone = courseTone(meeting, colorSlots[courseColorKey(meeting)])
                val timeLabel = UpcTimetableSchedule.label(meeting)
                val timeLines = timeLabel?.split('–', limit = 2)?.let { parts ->
                    if (parts.size == 2) "${parts[0]}\n${parts[1]}" else timeLabel
                } ?: "时间待确认"
                val location = meeting.location?.takeIf(String::isNotBlank) ?: "地点未提供"
                val courseTag = "timetable_course_${meeting.date}_${meeting.startPeriod}_$index"
                Surface(
                    modifier = Modifier
                        .offset(x = laneWidth * dayLane.lane + 1.dp, y = top + 1.dp)
                        .width(cardWidth)
                        .height(height)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tone.container, RoundedCornerShape(6.dp))
                        .glassHighlight(RoundedCornerShape(6.dp))
                        .clickable { onCourseClick(meeting) }
                        .semantics {
                            contentDescription = listOfNotNull(
                                "课程详情 ${meeting.courseName}",
                                meeting.sourcePeriodsLabel(),
                                "上课时间 ${timeLabel ?: "时间待确认"}",
                                meeting.location?.takeIf(String::isNotBlank)?.let { "地点 $it" },
                            ).joinToString("，")
                        },
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    if (periodCount >= 2 && cardWidth >= CARD_DETAIL_MIN_WIDTH) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 1.dp, vertical = 1.dp),
                        ) {
                            TimetableCardRegion {
                                TimetableCardText(
                                    text = meeting.courseName,
                                    color = tone.content,
                                    fontSize = 9.sp,
                                    lineHeight = 10.sp,
                                    maxLines = 2,
                                    fontWeight = FontWeight.SemiBold,
                                    testTag = "${courseTag}_title",
                                )
                            }
                            TimetableCardRegion {
                                TimetableCardText(
                                    text = timeLines,
                                    color = tone.content.copy(alpha = 0.9f),
                                    fontSize = 8.sp,
                                    lineHeight = 9.sp,
                                    maxLines = 2,
                                    testTag = "${courseTag}_time",
                                )
                            }
                            TimetableCardRegion {
                                TimetableCardText(
                                    text = location,
                                    color = tone.content.copy(alpha = 0.85f),
                                    fontSize = 8.sp,
                                    lineHeight = 9.sp,
                                    maxLines = 2,
                                    testTag = "${courseTag}_location",
                                )
                            }
                        }
                    } else {
                        val narrow = cardWidth < CARD_COMPACT_MIN_WIDTH
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 0.5.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TimetableCardText(
                                text = meeting.courseName,
                                color = tone.content,
                                fontSize = if (narrow) 7.sp else 9.sp,
                                lineHeight = if (narrow) 8.sp else 10.sp,
                                maxLines = 2,
                                fontWeight = FontWeight.SemiBold,
                                testTag = "${courseTag}_title",
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
    state: TimetableTodayUiState,
    onCourseClick: (TimetableMeeting) -> Unit,
    showDate: Boolean = true,
    onOpenTimetable: (() -> Unit)? = null,
    onOpenAccountSettings: (() -> Unit)? = null,
    agenda: TimetableAgendaState? = null,
    onAgendaClick: ((String) -> Unit)? = null,
    onAgendaCreate: (() -> Unit)? = null,
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
                Text(
                    if (agenda == null) "今日课程" else "今日安排",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (showDate) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${todayNow.monthValue}月${todayNow.dayOfMonth}日 " +
                            weekdayLabel(todayNow.dayOfWeek.value),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (agenda != null && timetableAgendaNeedsShanghaiNote()) {
                    Text(
                        "上海时间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .testTag("today_agenda_timezone"),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (matchesActualToday && state.todayLoaded &&
                    state.todayFetchedAtEpochMillis != null
                ) {
                    Text(
                        "更新于 ${formatFetchedAt(state.todayFetchedAtEpochMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (agenda != null && onAgendaCreate != null) {
                    com.upcpocket.app.ui.components.CampusTextButton(
                        onClick = onAgendaCreate,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("today_agenda_add"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("新增日程") }
                }
            }
            val schoolStatus = todaySchoolStatus(
                state = state,
                matchesActualToday = matchesActualToday,
                onOpenTimetable = onOpenTimetable,
                onOpenAccountSettings = onOpenAccountSettings,
            )
            schoolStatus?.let { status ->
                TodayStatus(
                    text = status.text,
                    actionLabel = status.actionLabel,
                    action = status.action,
                )
            }
            val schoolReady = schoolStatus == null &&
                state.todayState == TimetableTodayState.READY
            val schoolEmptyToday = schoolStatus == null &&
                state.todayState == TimetableTodayState.EMPTY
            val agendaState = agenda
            if (agendaState == null) {
                when {
                    schoolReady -> state.todayMeetings.forEach { meeting ->
                        TodayCourseRow(
                            meeting = meeting,
                            range = ranges[meeting],
                            toneSlot = state.todayCourseColorSlots[courseColorKey(meeting)],
                            nowMinute = nowMinute,
                            isCurrent = meeting == current,
                            isNext = meeting == next,
                            showSource = false,
                            onClick = { onCourseClick(meeting) },
                        )
                    }

                    schoolEmptyToday -> TodayStatus(text = "今天没有排课。")
                    else -> Unit
                }
            } else {
                if (agendaState is TimetableAgendaState.Loading) {
                    Text(
                        "正在读取本机日程…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp).testTag("today_agenda_loading"),
                    )
                } else if (agendaState is TimetableAgendaState.Failed) {
                    Text(
                        "本机日程读取失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp).testTag("today_agenda_failed"),
                    )
                }
                val segments = (agendaState as? TimetableAgendaState.Ready)
                    ?.let { ready -> projectTimetableAgendaDay(ready.entries, todayNow) }
                val openAgenda: (String) -> Unit = onAgendaClick ?: {}
                when {
                    schoolReady -> {
                        val items = buildMergedToday(
                            state = state,
                            ranges = ranges,
                            current = current,
                            next = next,
                            segments = segments.orEmpty(),
                        )
                        if (items.isEmpty()) {
                            // 只有日程也读取成功时才说“没有安排”；读取中/失败时
                            // 保留上面的状态行，不伪装成真正的空结果。
                            if (segments != null) {
                                TodayStatus(text = "今天没有安排。")
                            }
                        } else {
                            items.forEach { item ->
                                when (item) {
                                    is TodayAgendaItem.Course -> TodayCourseRow(
                                        meeting = item.meeting,
                                        range = item.range,
                                        toneSlot = item.toneSlot,
                                        nowMinute = nowMinute,
                                        isCurrent = item.isCurrent,
                                        isNext = item.isNext,
                                        showSource = true,
                                        onClick = { onCourseClick(item.meeting) },
                                    )

                                    is TodayAgendaItem.Schedule -> key(item.segment.entry.id) {
                                        TimetableAgendaRow(
                                            segment = item.segment,
                                            nowMinute = nowMinute,
                                            showSourceTag = true,
                                            onClick = { openAgenda(item.segment.entry.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    schoolEmptyToday -> when {
                        segments == null -> Unit
                        segments.isEmpty() -> TodayStatus(text = "今天没有安排。")
                        else -> segments.forEach { segment ->
                            key(segment.entry.id) {
                                TimetableAgendaRow(
                                    segment = segment,
                                    nowMinute = nowMinute,
                                    showSourceTag = true,
                                    onClick = { openAgenda(segment.entry.id) },
                                )
                            }
                        }
                    }

                    else -> when {
                        segments == null -> Unit
                        segments.isEmpty() -> TodayStatus(text = "今天没有个人日程。")
                        else -> segments.forEach { segment ->
                            key(segment.entry.id) {
                                TimetableAgendaRow(
                                    segment = segment,
                                    nowMinute = nowMinute,
                                    showSourceTag = true,
                                    onClick = { openAgenda(segment.entry.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class TodayStatusSpec(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * 首页学校课程的展示状态；返回 null 表示课程数据可用（READY 或 EMPTY 且已读本机）。
 *
 * 课表账号未保存、未同步、旧账号或旧日期的课程都在这里收敛成明确状态，绝不把过期
 * 课程混入「今日安排」。
 */
private fun todaySchoolStatus(
    state: TimetableTodayUiState,
    matchesActualToday: Boolean,
    onOpenTimetable: (() -> Unit)?,
    onOpenAccountSettings: (() -> Unit)?,
): TodayStatusSpec? = when (state.todayState) {
    TimetableTodayState.ACCOUNT_RESTORING -> TodayStatusSpec(
        text = state.todayMessage ?: "正在恢复学校账号。",
    )

    TimetableTodayState.ACCOUNT_REQUIRED -> TodayStatusSpec(
        text = state.todayMessage ?: "请先绑定校方托管账号。",
        actionLabel = "托管账号",
        action = onOpenAccountSettings,
    )

    TimetableTodayState.ACCOUNT_BLOCKED -> TodayStatusSpec(
        text = state.todayMessage ?: "学校账号暂不可用。",
        actionLabel = "学校账号",
        action = onOpenAccountSettings,
    )

    TimetableTodayState.READING_CACHE -> TodayStatusSpec(
        text = if (matchesActualToday) "正在读取本机课表…" else "今日课表尚未同步。",
        actionLabel = if (matchesActualToday) null else "去课表",
        action = if (matchesActualToday) null else onOpenTimetable,
    )

    TimetableTodayState.NOT_SYNCED -> TodayStatusSpec(
        text = "今日课表尚未同步。",
        actionLabel = "去课表",
        action = onOpenTimetable,
    )

    TimetableTodayState.OUTSIDE_TERM -> if (!matchesActualToday) {
        TodayStatusSpec("今日课表尚未同步。", "去课表", onOpenTimetable)
    } else {
        TodayStatusSpec("今天不在已同步学期内。", "查看课表", onOpenTimetable)
    }

    TimetableTodayState.READ_ERROR -> TodayStatusSpec(
        text = if (matchesActualToday) {
            state.todayMessage ?: "本机课表读取失败。"
        } else {
            "今日课表尚未同步。"
        },
        actionLabel = "去课表",
        action = onOpenTimetable,
    )

    TimetableTodayState.EMPTY,
    TimetableTodayState.READY,
    -> if (!state.todayLoaded || !matchesActualToday) {
        TodayStatusSpec("今日课表尚未同步。", "去课表", onOpenTimetable)
    } else {
        null
    }
}

private sealed interface TodayAgendaItem {
    val sortMinute: Int
    val sortKey: String
    val sortOrder: Int

    data class Course(
        val meeting: TimetableMeeting,
        val range: TimetableClockRange?,
        val toneSlot: Int?,
        val isCurrent: Boolean,
        val isNext: Boolean,
        val order: Int,
    ) : TodayAgendaItem {
        override val sortMinute: Int get() = range?.startMinute ?: Int.MAX_VALUE
        override val sortKey: String get() = "课程 ${meeting.courseName}"
        override val sortOrder: Int get() = order
    }

    data class Schedule(
        val segment: TimetableAgendaSegment,
    ) : TodayAgendaItem {
        override val sortMinute: Int get() = segment.startMinute
        override val sortKey: String get() = "日程 ${segment.entry.title} ${segment.entry.id}"
        override val sortOrder: Int get() = 0
    }
}

/**
 * 学校课程与本机日程按上海当日分钟数合并；时间未知的课程排在最后并保留原文案，
 * 不按节次推算时刻。
 */
private fun buildMergedToday(
    state: TimetableTodayUiState,
    ranges: Map<TimetableMeeting, TimetableClockRange?>,
    current: TimetableMeeting?,
    next: TimetableMeeting?,
    segments: List<TimetableAgendaSegment>,
): List<TodayAgendaItem> {
    val courses = state.todayMeetings.mapIndexed { index, meeting ->
        TodayAgendaItem.Course(
            meeting = meeting,
            range = ranges[meeting],
            toneSlot = state.todayCourseColorSlots[courseColorKey(meeting)],
            isCurrent = meeting == current,
            isNext = meeting == next,
            order = index,
        )
    }
    return (courses + segments.map { TodayAgendaItem.Schedule(it) }).sortedWith(
        compareBy({ it.sortMinute }, { it.sortKey }, { it.sortOrder }),
    )
}

@Composable
private fun TodayCourseRow(
    meeting: TimetableMeeting,
    range: TimetableClockRange?,
    toneSlot: Int?,
    nowMinute: Int,
    isCurrent: Boolean,
    isNext: Boolean,
    showSource: Boolean,
    onClick: () -> Unit,
) {
    val ended = range != null && range.endMinute <= nowMinute
    val tone = courseTone(meeting, toneSlot)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(32.dp)
                .background(tone.container, RoundedCornerShape(2.dp))
                .glassHighlight(RoundedCornerShape(2.dp)),
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
                    modifier = Modifier.weight(1f),
                )
                if (showSource) {
                    TodayTag("课程", MaterialTheme.colorScheme.secondary)
                }
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
            )
            if (isCurrent && range != null) {
                val duration = (range.endMinute - range.startMinute).coerceAtLeast(1)
                val elapsed = (nowMinute - range.startMinute).coerceIn(0, duration)
                val remaining = (duration - elapsed).coerceAtLeast(0)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { elapsed.toFloat() / duration.toFloat() },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(999.dp)),
                    )
                    Text(
                        "剩 $remaining 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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

@Composable
private fun TodayStatus(
    text: String,
    actionLabel: String? = null,
    action: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && action != null) {
            com.upcpocket.app.ui.components.CampusTextButton(
                onClick = action,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(actionLabel)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetailBottomSheet(
    meeting: TimetableMeeting,
    onDismiss: () -> Unit,
    onOpenCampusLocation: ((String) -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(meeting.courseName, style = MaterialTheme.typography.titleLarge)
            Text(
                "${meeting.date} ${weekdayLabel(meeting.weekday)} · ${periodLabel(meeting)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusPill(
                text = "${meeting.weekLabel} 周",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            HorizontalDivider(
                Modifier.padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
            DetailLine("日期", "${meeting.date} ${weekdayLabel(meeting.weekday)}")
            DetailLine("节次", meeting.sourcePeriodsLabel())
            DetailLine("周次", meeting.weekLabel)
            DetailLine("教师", meeting.teacher ?: "未提供")
            DetailLine("地点", meeting.location ?: "未提供")
            val location = meeting.location?.trim()?.takeIf(String::isNotEmpty)
            if (location != null && onOpenCampusLocation != null) {
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = {
                        onDismiss()
                        onOpenCampusLocation(location)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("在校园地图查看")
                }
            }
            DetailLine(
                "上课时间",
                UpcTimetableSchedule.label(meeting) ?: "时间待确认",
            )
            DetailLine("课程类型", meeting.courseType ?: "未提供")
            DetailLine("课程编号", meeting.courseId ?: "未提供")
            if (meeting.sourcePeriods.size != meeting.periods.size) {
                Text(
                    "原始节次不连续，网格分段显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "1–12 节按学校作息；13–14 节暂无官方时刻。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            com.upcpocket.app.ui.components.CampusTextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
            ) {
                Text("关闭")
            }
        }
    }
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
    com.upcpocket.app.ui.components.CampusAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课表说明") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "课表按学校数据展示，不提供编辑或提醒。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "周网格按日期连续显示；切换周不影响今日课程。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "1–12 节按学校作息；13–14 节暂无官方时刻，显示“时间待确认”。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "作息来源：中国石油大学（华东）《关于调整作息时间的通知》\n" +
                        UpcTimetableSchedule.SOURCE_URL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "临时调课或教室变更以学校通知为准；缓存会标明。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                com.upcpocket.app.ui.components.CampusTextButton(onClick = onOpenWebLogin) { Text("网页登录") }
                com.upcpocket.app.ui.components.CampusTextButton(onClick = onDismiss) { Text("关闭") }
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

private data class DayLane(val lane: Int, val laneCount: Int)

private fun assignLanes(meetings: List<TimetableMeeting>): List<DayLane> {
    if (meetings.isEmpty()) return emptyList()
    val result = MutableList(meetings.size) { DayLane(lane = 0, laneCount = 1) }
    val lanes = IntArray(meetings.size)
    val laneEnds = mutableListOf<Int>()
    var groupStart = 0
    var groupMaxEnd = 0
    meetings.forEachIndexed { index, meeting ->
        if (index > groupStart && meeting.startPeriod > groupMaxEnd) {
            val laneCount = laneEnds.size
            for (member in groupStart until index) {
                result[member] = DayLane(lanes[member], laneCount)
            }
            laneEnds.clear()
            groupStart = index
        }
        groupMaxEnd = if (index == groupStart) {
            meeting.endPeriod
        } else {
            maxOf(groupMaxEnd, meeting.endPeriod)
        }
        val freeLane = laneEnds.indexOfFirst { end -> end < meeting.startPeriod }
        if (freeLane >= 0) {
            laneEnds[freeLane] = meeting.endPeriod
            lanes[index] = freeLane
        } else {
            laneEnds += meeting.endPeriod
            lanes[index] = laneEnds.lastIndex
        }
    }
    val laneCount = laneEnds.size.coerceAtLeast(1)
    for (member in groupStart until meetings.size) {
        result[member] = DayLane(lanes[member], laneCount)
    }
    return result
}

@Composable
private fun ColumnScope.TimetableCardRegion(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun TimetableCardText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    maxLines: Int,
    testTag: String,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = 0.sp,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
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
    TimetableConnection.LOADING -> "读取账号中"
    TimetableConnection.NOT_CONFIGURED -> "未设置账号"
    TimetableConnection.BLOCKED_BY_CLEANUP -> "清理中"
    TimetableConnection.IDLE -> "未连接"
    TimetableConnection.CONNECTING -> "连接中"
    TimetableConnection.CONNECTED -> "已连接"
    TimetableConnection.INTERACTION_REQUIRED -> "需要登录"
    TimetableConnection.AUTH_FAILED -> "登录失败"
    TimetableConnection.NETWORK_UNAVAILABLE -> "网络不可用"
    TimetableConnection.PROTOCOL_ERROR -> "服务异常"
    TimetableConnection.SESSION_EXPIRED -> "登录失效"
    TimetableConnection.DEGRADED_CACHE -> "缓存"
}

private fun formatFetchedAt(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(TIMETABLE_ZONE)
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
