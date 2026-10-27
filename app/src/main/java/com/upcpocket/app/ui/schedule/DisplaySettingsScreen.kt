package com.upcpocket.app.ui.schedule

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.core.WeekMath
import com.upcpocket.app.core.WeekSettings
import com.upcpocket.app.core.WeekSource
import com.upcpocket.app.core.WeekStartDay
import com.upcpocket.app.data.prefs.BlockBorder
import com.upcpocket.app.data.prefs.CourseColorMode
import com.upcpocket.app.data.prefs.CourseTextMode
import com.upcpocket.app.data.prefs.GridDensity
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.ui.components.GlassCard
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「教学周」卡片要显示的值。
 *
 * 本原型没有教务校历：周次的唯一来源是**用户**在设置里填的开学日 / 当前周次 / 总周数。
 * 这里只负责读写与展示，真正的换算在 [com.upcpocket.app.core.WeekMath]。
 */
data class WeekUi(
    val semesterId: Int? = null,
    val weekStart: LocalDate? = null,
    val startDay: WeekStartDay = WeekStartDay.MONDAY,
    val total: Int? = null,
    val confirmed: Boolean = false,
    val source: WeekSource = WeekSource.INFERRED,
    /** 按 [weekStart] 与今天算出的当前周次 */
    val currentWeek: Int? = null,
    /** 学期名（展示用） */
    val semesterName: String = "",
)

class DisplaySettingsViewModel : ViewModel() {

    private val store: UiPrefsStore = AppGraph.uiPrefsStore
    private val scheduleRepo = AppGraph.scheduleRepository

    val prefs: StateFlow<UiPrefs> = store.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiPrefs())

    fun setShowWeekend(v: Boolean) = viewModelScope.launch { store.setShowWeekend(v) }

    /** 「课程总览」要打开的学期：优先当前学期，退而求其次第一个 */
    suspend fun currentSemesterId(): Int? =
        scheduleRepo.currentSemester()?.id ?: scheduleRepo.semesters().firstOrNull()?.id

    fun setBlockBorder(v: BlockBorder) = viewModelScope.launch { store.setBlockBorder(v) }
    fun setTextMode(v: CourseTextMode) = viewModelScope.launch { store.setCourseTextMode(v) }
    fun setDensity(v: GridDensity) = viewModelScope.launch { store.setDensity(v) }
    fun setLiveOutline(v: Boolean) = viewModelScope.launch { store.setLiveOutline(v) }
    fun setLiveOutlineWidth(v: Int) = viewModelScope.launch { store.setLiveOutlineWidth(v) }
    fun setColorMode(v: CourseColorMode) = viewModelScope.launch { store.setColorMode(v) }
    fun setBackgroundOpacity(v: Float) = viewModelScope.launch { store.setBackgroundOpacity(v) }
    fun setBackgroundBlur(v: Float) = viewModelScope.launch { store.setBackgroundBlur(v) }
    fun setBackgroundScrim(v: Float) = viewModelScope.launch { store.setBackgroundScrim(v) }
    fun setWallpaperNavBar(v: Boolean) = viewModelScope.launch { store.setWallpaperNavBar(v) }

    /** 演示模式开关：只改 DataStore，演示课从来不落库 */
    fun setDemoMode(v: Boolean) = viewModelScope.launch {
        store.setDemoMode(v)
        // 演示开关会改变课表内容，顺手把桌面组件推一遍
        AppGraph.appContextOrNull()?.let { ctx ->
            runCatching { com.upcpocket.app.ui.widget.Widgets.refreshAll(ctx) }
        }
    }

    fun clearBackground() {
        viewModelScope.launch {
            runCatching { ScheduleBackground.clear(AppGraph.appContextOrNull() ?: return@launch) }
            store.clearBackground()
        }
    }

    // ── 教学周参数 ────────────────────────────────────────────────────────

    private val _week = kotlinx.coroutines.flow.MutableStateFlow(WeekUi())
    val week: StateFlow<WeekUi> = _week

    private val _periods = kotlinx.coroutines.flow.MutableStateFlow<List<com.upcpocket.app.domain.Period>>(emptyList())
    val periods: StateFlow<List<com.upcpocket.app.domain.Period>> = _periods

    init {
        viewModelScope.launch {
            refreshWeek()
            _periods.value = runCatching { scheduleRepo.periods() }.getOrDefault(emptyList())
        }
    }

    /**
     * 保存节次作息。
     *
     * 节次表作为时间来源，同时在事务中同步已有日程的时间快照作为兜底——
     * 这样"学校调整作息"只需要改一处，也不用担心历史记录与新作息打架。
     */
    fun savePeriods(periods: List<com.upcpocket.app.domain.Period>) = viewModelScope.launch {
        runCatching { scheduleRepo.savePeriods(periods) }
        _periods.value = runCatching { scheduleRepo.periods() }.getOrDefault(emptyList())
        AppGraph.appContextOrNull()?.let { ctx ->
            runCatching { com.upcpocket.app.ui.widget.Widgets.refreshAll(ctx) }
        }
    }

    /** 读参数；若为空但学期有开学日，就自动填一次（那份开学日也是用户设的） */
    suspend fun refreshWeek() {
        val semester = runCatching { scheduleRepo.currentSemester() }.getOrNull()
        val id = semester?.id
        if (id == null) {
            _week.value = WeekUi()
            return
        }
        var st = store.weekSettings(id)
        if (st.weekOneStart == null && semester.startDate != null) {
            st = st.copy(
                weekOneStart = WeekMath.startOfWeek(semester.startDate, st.startDay),
                confirmed = true,
                source = WeekSource.CALENDAR,
            )
            runCatching { store.saveWeekSettings(id, st) }
        }
        val today = LocalDate.now()
        _week.value = WeekUi(
            semesterId = id,
            semesterName = semester.displayName,
            weekStart = st.weekOneStart,
            startDay = st.startDay,
            total = st.totalWeeks ?: semester.weekCount,
            confirmed = st.confirmed,
            source = st.source,
            currentWeek = st.weekOneStart?.let { WeekMath.weekIndexAt(it, today) },
        )
    }

    fun setWeekStart(date: LocalDate) = viewModelScope.launch {
        val w = _week.value
        val id = w.semesterId ?: return@launch
        val start = WeekMath.startOfWeek(date, w.startDay)
        store.saveWeekSettings(
            id,
            WeekSettings(
                weekOneStart = start,
                startDay = w.startDay,
                totalWeeks = w.total,
                confirmed = true,
                source = WeekSource.USER,
            ),
        )
        scheduleRepo.saveSemesterSettings(id, startDate = start, totalWeeks = w.total)
        refreshWeek()
    }

    fun setCurrentWeek(week: Int) = viewModelScope.launch {
        val w = _week.value
        val id = w.semesterId ?: return@launch
        val start = WeekMath.weekOneStart(LocalDate.now(), week, w.startDay)
        store.saveWeekSettings(
            id,
            WeekSettings(
                weekOneStart = start,
                startDay = w.startDay,
                totalWeeks = w.total,
                confirmed = true,
                source = WeekSource.USER,
            ),
        )
        scheduleRepo.saveSemesterSettings(id, startDate = start, totalWeeks = w.total)
        refreshWeek()
    }

    fun setWeekTotal(total: Int?) = viewModelScope.launch {
        val w = _week.value
        val id = w.semesterId ?: return@launch
        store.saveWeekSettings(
            id,
            WeekSettings(
                weekOneStart = w.weekStart,
                startDay = w.startDay,
                totalWeeks = total,
                confirmed = w.confirmed,
                source = w.source,
            ),
        )
        scheduleRepo.saveSemesterSettings(id, startDate = w.weekStart, totalWeeks = total)
        refreshWeek()
    }

    fun setWeekStartDay(day: WeekStartDay) = viewModelScope.launch {
        val w = _week.value
        val id = w.semesterId ?: return@launch
        val start = w.weekStart?.let { WeekMath.startOfWeek(it, day) }
        store.saveWeekSettings(
            id,
            WeekSettings(
                weekOneStart = start,
                startDay = day,
                totalWeeks = w.total,
                confirmed = w.confirmed,
                source = w.source,
            ),
        )
        if (start != null) {
            scheduleRepo.saveSemesterSettings(id, startDate = start, totalWeeks = w.total)
        }
        refreshWeek()
    }

    fun resetWeek() = viewModelScope.launch {
        val id = _week.value.semesterId ?: return@launch
        store.clearWeekSettings(id)
        refreshWeek()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    onOpenCrop: (Uri) -> Unit = {},
    onRecrop: () -> Unit = {},
    /** 「课程总览」入口 */
    onOpenOverview: (Int) -> Unit = {},
    vm: DisplaySettingsViewModel = viewModel(),
) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val periods by vm.periods.collectAsStateWithLifecycle()
    var showPeriodsEditor by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val background = rememberScheduleBackground(
        context = context,
        path = prefs.backgroundPath,
        version = prefs.backgroundVersion,
    )
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onOpenCrop(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课程表设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            val scope = rememberCoroutineScope()
            OutlinedButton(
                onClick = {
                    scope.launch {
                        vm.currentSemesterId()?.let { id -> onOpenOverview(id) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("课程总览（本学期全部课程）") }

            // 演示数据开关放最上面：它是"能不能看到东西"的开关，比配色重要
            SectionCard("演示数据") {
                SwitchRow(
                    title = "显示演示课表",
                    hint = "虚构的示例课程，用来体验课表、今天页与桌面组件；不写数据库，关掉即消失",
                    checked = prefs.demoMode,
                    onCheckedChange = vm::setDemoMode,
                )
                Text(
                    text = "演示课带「演示」标记、课名以「（演示）」结尾。你自己添加的日程永远排在它前面，" +
                        "也不会被它覆盖。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            // 节次作息：课表/今天/组件的所有时间都由它决定，和教学周同等重要
            SectionCard("节次时间") {
                ValueRow(
                    title = "上下课时间",
                    value = if (periods.isEmpty()) "未设置" else "共 ${periods.size} 节",
                    hint = "默认值只是常见排法，请改成你们的实际上课时间",
                    warn = periods.isEmpty(),
                    onClick = { showPeriodsEditor = true },
                )
            }

            // 教学周放在前面：周次对不对，比"好不好看"重要得多
            WeekSettingsCard(
                week = week,
                onSetWeekStart = vm::setWeekStart,
                onSetCurrentWeek = vm::setCurrentWeek,
                onSetTotal = vm::setWeekTotal,
                onSetStartDay = vm::setWeekStartDay,
                onReset = vm::resetWeek,
            )

            SectionCard("显示") {
                SwitchRow(
                    title = "显示周六周日",
                    hint = "关掉后只显示周一到周五，每列更宽、课名更完整",
                    checked = prefs.showWeekend,
                    onCheckedChange = vm::setShowWeekend,
                )
                ChoiceRow(
                    title = "课程块描边",
                    hint = "虚线便于分辨相邻且颜色接近的课程块",
                    options = listOf(
                        BlockBorder.NONE to "无",
                        BlockBorder.SOLID to "实线",
                        BlockBorder.DASHED to "虚线",
                    ),
                    selected = prefs.blockBorder,
                    onSelect = vm::setBlockBorder,
                )
                ChoiceRow(
                    title = "课程格内容",
                    hint = "老师不放在格子里（格子里放不下，点开课程详情就能看到）",
                    options = listOf(
                        CourseTextMode.NAME to "课名",
                        CourseTextMode.NAME_ROOM to "课名+地点",
                    ),
                    selected = prefs.courseTextMode,
                    onSelect = vm::setTextMode,
                )
                ChoiceRow(
                    title = "格子密度",
                    hint = "标准档把课名字号提到 11sp",
                    options = listOf(
                        GridDensity.COMPACT to "紧凑",
                        GridDensity.STANDARD to "标准",
                        GridDensity.COMFORTABLE to "舒适",
                    ),
                    selected = prefs.density,
                    onSelect = vm::setDensity,
                )
            }

            SectionCard("配色") {
                ChoiceRow(
                    title = "课程配色",
                    options = listOf(
                        CourseColorMode.COLORFUL to "多彩",
                        CourseColorMode.SINGLE to "单色",
                    ),
                    selected = prefs.colorMode,
                    onSelect = vm::setColorMode,
                )
            }

            SectionCard("正在上的课") {
                SwitchRow(
                    title = "描边高亮",
                    hint = "给当前正在上的那节课加一圈描边，扫一眼就知道现在上什么",
                    checked = prefs.liveOutlineEnabled,
                    onCheckedChange = vm::setLiveOutline,
                )
                if (prefs.liveOutlineEnabled) {
                    ChoiceRow(
                        title = "描边宽度",
                        options = listOf(1 to "细", 2 to "标准", 3 to "粗"),
                        selected = prefs.liveOutlineWidth,
                        onSelect = vm::setLiveOutlineWidth,
                    )
                }
            }

            SectionCard("课表背景") {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (background != null) {
                        Image(
                            bitmap = background,
                            contentDescription = "当前的课表背景",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        Text(
                            text = "当前用的是默认背景。选一张图，导入时会按课表比例让你裁剪。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        ) { Text(if (background == null) "选择图片" else "换一张") }

                        if (background != null) {
                            OutlinedButton(onClick = onRecrop) { Text("重新裁剪") }
                        }
                    }

                    if (background != null) {
                        val hasSource = remember(prefs.backgroundVersion) {
                            ScheduleBackground.hasSource(context)
                        }
                        if (!hasSource) {
                            Text(
                                text = "原图副本已经不在了，想换构图请点「换一张」重新选。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        WallpaperSlider(
                            label = "壁纸浓淡",
                            value = prefs.backgroundOpacity,
                            range = 0.15f..0.95f,
                            display = "${(prefs.backgroundOpacity * 100).roundToInt()}%",
                            hint = "图片自身的不透明度。",
                            onChange = vm::setBackgroundOpacity,
                        )
                        WallpaperSlider(
                            label = "模糊",
                            value = prefs.backgroundBlur,
                            range = 0f..20f,
                            display = if (prefs.backgroundBlur < 0.5f) {
                                "清晰"
                            } else {
                                "${prefs.backgroundBlur.roundToInt()}"
                            },
                            hint = "模糊一点，照片和文字更容易共存。",
                            onChange = vm::setBackgroundBlur,
                        )
                        WallpaperSlider(
                            label = "底色浓度",
                            value = prefs.backgroundScrim,
                            range = 0f..0.9f,
                            display = "${(prefs.backgroundScrim * 100).roundToInt()}%",
                            hint = "图片上那层主题底色。课名看不清就往右调。",
                            onChange = vm::setBackgroundScrim,
                        )
                        SwitchRow(
                            title = "底部导航栏一起透明",
                            hint = "只在课表这一页生效，其他页照旧",
                            checked = prefs.wallpaperNavBar,
                            onCheckedChange = vm::setWallpaperNavBar,
                        )
                        OutlinedButton(
                            onClick = vm::clearBackground,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("恢复默认壁纸") }
                    }
                }
            }

            // 说明：为什么这里没有"同步课表"按钮
            Text(
                text = "课表内容只有你自己添加的日程、主动导入的 CSV，以及可关闭的演示数据；" +
                    "App 不连接教务后台自动同步。教学周、节次和 11–13 节都在这里配置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPeriodsEditor) {
        PeriodsEditDialog(
            periods = periods,
            onDismiss = { showPeriodsEditor = false },
            onSave = { updated ->
                vm.savePeriods(updated)
                showPeriodsEditor = false
            },
        )
    }
}

@Composable
private fun WallpaperSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    hint: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = display,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    hint: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
    }
}

// ── 教学周卡片 ──────────────────────────────────────────────────────────

/**
 * 「教学周」设置卡片。
 *
 * 为什么这几项必须能改：本原型没有教务校历，周次完全靠用户定义。
 * 开学日期（第 1 周起始日）、当前周次、本学期总周次、每周起始日就是唯一事实来源；
 * 填错之后今天页和课表会整体错位，所以必须放在最显眼的位置，且可以随时重置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekSettingsCard(
    week: WeekUi,
    onSetWeekStart: (LocalDate) -> Unit,
    onSetCurrentWeek: (Int) -> Unit,
    onSetTotal: (Int?) -> Unit,
    onSetStartDay: (WeekStartDay) -> Unit,
    onReset: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }
    var showTotalPicker by remember { mutableStateOf(false) }

    val sourceText = when (week.source) {
        WeekSource.CALENDAR -> "来自本学期的开学日"
        WeekSource.USER -> "你手动设置的"
        WeekSource.INFERRED -> "自动推断"
    }
    val dateText = week.weekStart?.let { "%d/%d".format(it.monthValue, it.dayOfMonth) } ?: "未设置"

    SectionCard("教学周（本地设置）") {
        ValueRow(
            title = "开学日期（第 1 周${week.startDay.label}）",
            value = dateText,
            hint = if (week.weekStart == null) "点这里设置，否则周次和日期都会偏" else sourceText,
            warn = week.weekStart == null || !week.confirmed,
            onClick = { showDatePicker = true },
        )
        ValueRow(
            title = "当前周次",
            value = week.currentWeek?.takeIf { it >= 1 }?.let { "第 $it 周" } ?: "—",
            hint = "改它等于反推开学日期（以今天为准）",
            onClick = { showWeekPicker = true },
        )
        ValueRow(
            title = "本学期总周次",
            value = week.total?.toString() ?: "自动",
            hint = "只影响显示范围，不会删数据",
            onClick = { showTotalPicker = true },
        )
        ChoiceRow(
            title = "每周起始日",
            hint = "国内高校通常按周一起算",
            options = listOf(
                WeekStartDay.MONDAY to "周一",
                WeekStartDay.SUNDAY to "周日",
            ),
            selected = week.startDay,
            onSelect = onSetStartDay,
        )
        TextButton(
            onClick = onReset,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("重置为开学日推算") }
    }

    if (showDatePicker) {
        val initial = week.weekStart
            ?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val picked = java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            onSetWeekStart(picked)
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showWeekPicker) {
        NumberGridDialog(
            title = "现在实际是第几周？",
            hint = "选完会按今天反推开学日期，并记住这个设置",
            numbers = (1..(week.total ?: 25)).toList(),
            selected = week.currentWeek,
            onPick = { onSetCurrentWeek(it); showWeekPicker = false },
            onDismiss = { showWeekPicker = false },
        )
    }

    if (showTotalPicker) {
        NumberGridDialog(
            title = "本学期共几周？",
            hint = "调小只是不再显示后面的周，不会删任何日程",
            numbers = (1..30).toList(),
            selected = week.total,
            onPick = { onSetTotal(it); showTotalPicker = false },
            onDismiss = { showTotalPicker = false },
        )
    }
}

/** 设置页里的一行「标题 + 当前值」，点了进二级对话框 */
@Composable
private fun ValueRow(
    title: String,
    value: String,
    hint: String? = null,
    warn: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warn) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 数字网格选择对话框（周次/总周次共用） */
@Composable
private fun NumberGridDialog(
    title: String,
    hint: String,
    numbers: List<Int>,
    selected: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                numbers.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { n ->
                            val isSelected = n == selected
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onPick(n) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$n",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
