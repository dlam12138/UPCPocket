package com.upcpocket.app.ui.schedule

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.core.ScheduleText
import com.upcpocket.app.core.WeekMath
import com.upcpocket.app.core.WeekSettings
import com.upcpocket.app.core.WeekSource
import com.upcpocket.app.core.WeekStartDay
import com.upcpocket.app.data.local.DemoTimetable
import com.upcpocket.app.data.local.LocalDefaults
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.data.repo.ScheduleRepository
import com.upcpocket.app.domain.Course
import com.upcpocket.app.domain.Period
import com.upcpocket.app.domain.Plan
import com.upcpocket.app.domain.PlanSpec
import com.upcpocket.app.domain.Semester
import com.upcpocket.app.domain.Session
import com.upcpocket.app.domain.WeekAlignment
import com.upcpocket.app.ui.widget.Widgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 课表页的界面状态（纯本地）。
 *
 * 与上游相比删掉了整块"远端"状态：`loggedIn`、`cacheNotice`、`refreshing`、
 * `dataFetchedAt`、`gradMode`/`gms*`。本地数据不需要"数据截至几点"，也不会因为
 * 会话过期而失败 —— 剩下的失败只有一种：数据库读写异常。
 */
data class ScheduleUiState(
    val started: Boolean = false,
    val loading: Boolean = false,

    /** 演示课表是否开启（只影响显示，不影响数据库） */
    val demoMode: Boolean = false,

    // ── 教学周参数（按学期存）─────────────────────────────────────────
    /** 第 1 周的起始日；null = 还没定（界面显示"待设置"） */
    val weekStart: LocalDate? = null,
    val weekStartDay: WeekStartDay = WeekStartDay.MONDAY,
    /** 本学期总周次（用户可改；null = 用学期里的默认值） */
    val weekTotal: Int? = null,
    /** 周次参数是否已确认 */
    val weekConfirmed: Boolean = false,
    val weekSource: WeekSource = WeekSource.INFERRED,

    val error: String? = null,
    /** 提示（不是错误） */
    val notice: String? = null,

    val semesters: List<Semester> = emptyList(),
    val selectedSemester: Semester? = null,
    val weekIndex: Int = 1,
    /**
     * [weekIndex] 是**按哪一天**算出来的。
     *
     * 光存周次不够：跨天（尤其跨周）后必须重新对准当前周，而判断"该不该重算"
     * 只能靠这个日期。用户手动翻周不改它 —— 同一天内翻的周是他自己的选择，
     * 隔天则应该自动回到当前周。
     */
    val weekResolvedOn: LocalDate? = null,

    /** 整学期全部课（自定义日程 + 演示课）；翻周在内存里过滤，不读库 */
    val allSessions: List<Session> = emptyList(),
    val periods: List<Period> = emptyList(),
    /** 自己补录的课程（课程总览用） */
    val courses: List<Course> = emptyList(),
    /** 演示课程聚合（总览里单独一组），关掉演示时为空 */
    val demoCourses: List<Course> = emptyList(),
    /** 本学期自己加的日程 */
    val plans: List<Plan> = emptyList(),

    /** 日程编辑框：null 且 [planEditorOpen] 为 true = 新建 */
    val editingPlan: Plan? = null,
    val planEditorOpen: Boolean = false,
    /** 撞课确认：待保存的内容 + 撞到的课名 */
    val pendingPlan: PlanSpec? = null,
    val pendingConflicts: List<String> = emptyList(),
    /** 补录保存/删除成功后的一次性确认 */
    val planNotice: String? = null,
    val detailCourse: Course? = null,
    /** detailCourse 的显示覆盖（名称 + 各时段教室）；null = 没查到/没有 */
    val detailOverrides: DisplayOverridesView? = null,
    /** 当前打开的详情是不是演示课（演示课不可编辑） */
    val detailIsDemo: Boolean = false,
) {
    /** 该学期有哪些教学周（升序） */
    val weeks: List<Int> get() = selectedSemester?.weekIndices.orEmpty()
    val weekCount: Int get() = weeks.size

    fun sessionsOf(week: Int): List<Session> = allSessions.filter { it.weekIndex == week }

    /** 课表里有没有演示课（网格要给这些块加"演示"标记） */
    val hasDemoSessions: Boolean
        get() = demoMode && allSessions.any { it.source == DemoTimetable.SOURCE }
}

/** 详情页看到的显示覆盖：名称覆盖 + 各时段教室覆盖。 */
data class DisplayOverridesView(
    val name: String? = null,
    val rooms: Map<String, String> = emptyMap(),
) {
    val any: Boolean get() = name != null || rooms.isNotEmpty()
}

/**
 * 课表页 ViewModel —— M1 全部走本地。
 *
 * 没有 `refreshSemesters()` / `refreshCourseTable()` 这类"先联网再落库"的路径：
 * 数据只有两个来源，一是用户自己保存的日程（Room），二是演示模板（内存）。
 * 所以这里的"刷新"只是**重新读一遍本地数据 + 重推桌面组件**。
 */
class ScheduleViewModel : ViewModel() {

    private val repo: ScheduleRepository = AppGraph.scheduleRepository
    private val uiPrefs: UiPrefsStore = AppGraph.uiPrefsStore

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { bootstrap() }
        viewModelScope.launch { observeDemoMode() }
    }

    // ── 演示模式 ────────────────────────────────────────────────────────

    /**
     * 演示开关变化时重载课表。
     *
     * 开关只改 DataStore，但课表内容会变（演示课进出），所以这里跟着重读一次本地数据；
     * 桌面组件也要推一遍 —— 否则用户关掉演示、桌面上还挂着演示课。
     */
    private suspend fun observeDemoMode() {
        uiPrefs.flow
            .map { it.demoMode }
            .distinctUntilChanged()
            .drop(1)   // 首帧由 bootstrap 处理
            .collect { enabled ->
                _state.update { it.copy(demoMode = enabled) }
                _state.value.selectedSemester?.let { loadSemester(it) }
                pushWidgets()
            }
    }

    // ── 载入 ────────────────────────────────────────────────────────────

    fun onResume() {
        // 从设置页改完周次参数回来：先把新参数读进来（纯本地）
        viewModelScope.launch { reloadWeekSettings() }
        if (!_state.value.started) {
            viewModelScope.launch { bootstrap() }
        } else {
            // 跨天/跨周之后回到今天那一周（同一天内用户翻过的周保留）
            resyncWeekIfDayChanged()
        }
    }

    /**
     * 隔天就把显示周次重新对准当前周；同一天内用户手动翻过的周保留。
     * 纯本地计算（`resolveCurrentWeek` 不读库），所以直接同步调用。
     */
    private fun resyncWeekIfDayChanged() {
        val s = _state.value
        val semester = s.selectedSemester ?: return
        val today = LocalDate.now()
        if (!WeekAlignment.needsRealign(s.weekResolvedOn, today)) return
        val week = WeekAlignment.realigned(
            resolvedOn = s.weekResolvedOn,
            today = today,
            storedWeek = s.weekIndex,
            currentWeek = repo.resolveCurrentWeek(semester, today),
            firstWeek = semester.firstWeek,
        )
        _state.update { it.copy(weekIndex = week, weekResolvedOn = today) }
    }

    private suspend fun bootstrap() {
        _state.update { it.copy(started = true, loading = true, error = null) }
        try {
            // 首次启动的种子（作息 + 学期）；幂等，App 启动时也调过一次
            repo.ensureSeeded()
            val all = repo.semesters()
            val demo = uiPrefs.flow.first().demoMode
            val selected = all.firstOrNull { it.isCurrent } ?: all.firstOrNull()
            _state.update {
                it.copy(
                    loading = selected == null,
                    demoMode = demo,
                    semesters = all,
                    selectedSemester = selected,
                    error = if (selected == null) "还没有学期，去「课程表设置」建一个" else null,
                )
            }
            if (selected != null) loadSemester(selected)
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = e.readableMessage()) }
        }
    }

    /** 载入某学期的全部本地数据（自定义日程 + 演示课 + 作息 + 课程聚合） */
    private suspend fun loadSemester(semester: Semester) {
        val today = LocalDate.now()
        val settings = resolveWeekSettings(
            semesterId = semester.id,
            calendarStart = semester.startDate,
            dataWeeks = semester.weekIndices,
        )
        val week = if (settings.weekOneStart != null) {
            WeekMath.weekIndexAt(settings.weekOneStart, today)
                .coerceIn(semester.firstWeek ?: 1, semester.lastWeek ?: Int.MAX_VALUE)
        } else {
            WeekAlignment.currentWeek(
                currentWeek = repo.resolveCurrentWeek(semester, today),
                firstWeek = semester.firstWeek,
            )
        }
        val sessions = repo.sessionsOfSemester(semester.id)
        val courses = repo.courses(semester.id)
        val demoCourses = repo.demoCourses(semester.id)
        val plans = repo.customPlans(semester.id)
        _state.update {
            it.copy(
                loading = false,
                selectedSemester = semester,
                periods = repo.periods(),
                weekIndex = week,
                weekResolvedOn = today,
                allSessions = sessions,
                courses = courses,
                demoCourses = demoCourses,
                plans = plans,
                weekStart = settings.weekOneStart,
                weekStartDay = settings.startDay,
                weekTotal = settings.totalWeeks,
                weekConfirmed = settings.confirmed,
                weekSource = settings.source,
            )
        }
    }

    /**
     * 取某学期的周次参数，必要时按学期的起始日填一次。
     *
     * **只有两个来源**：用户保存的设置、学期里的开学日。后者是用户在设置里亲手定的，
     * 所以视为已确认（[WeekSource.CALENDAR] 在这里表示"来自学期设置"）。
     */
    private suspend fun resolveWeekSettings(
        semesterId: Int,
        calendarStart: LocalDate?,
        dataWeeks: List<Int>,
    ): WeekSettings {
        val saved = runCatching { uiPrefs.weekSettings(semesterId) }.getOrDefault(WeekSettings())
        if (saved.weekOneStart != null) return saved
        if (calendarStart == null) return saved
        val start = WeekMath.startOfWeek(calendarStart, saved.startDay)
        val filled = saved.copy(
            weekOneStart = start,
            totalWeeks = saved.totalWeeks ?: dataWeeks.maxOrNull(),
            confirmed = true,
            source = WeekSource.CALENDAR,
        )
        runCatching { uiPrefs.saveWeekSettings(semesterId, filled) }
        return filled
    }

    /** 从设置页回来时重新读周次参数（纯本地） */
    private suspend fun reloadWeekSettings() {
        val id = _state.value.selectedSemester?.id ?: return
        val st = runCatching { uiPrefs.weekSettings(id) }.getOrNull() ?: return
        val start = st.weekOneStart ?: return
        val today = LocalDate.now()
        val week = WeekMath.weekIndexAt(start, today).coerceIn(1, st.totalWeeks ?: Int.MAX_VALUE)

        // ⚠️ 作息也可能刚在设置页里改过。网格、今天页、详情与桌面组件的时间都由 periods
        // 决定，只刷新周次而不同步 periods，回到课表页看到的还是旧时间
        // （复核报告 P2-2 的另一半）。这里把最新作息一起读进状态。
        val freshPeriods = runCatching { repo.periods() }.getOrDefault(_state.value.periods)

        _state.update {
            it.copy(
                periods = freshPeriods,
                weekStart = start,
                weekStartDay = st.startDay,
                weekTotal = st.totalWeeks,
                weekConfirmed = st.confirmed,
                weekSource = st.source,
                weekIndex = week,
                weekResolvedOn = today,
                notice = null,
            )
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * 刷新：重读本地数据 + 重推桌面组件。
     *
     * 名字保留成 refresh 是为了让顶栏那个刷新按钮继续有意义 ——
     * 现在它做的是"把库里的最新内容重新读一遍"（比如在设置页改了作息/周次）。
     */
    fun refresh() {
        val semester = _state.value.selectedSemester
        viewModelScope.launch {
            if (semester == null) {
                _state.update { it.copy(loading = true) }
                bootstrap()
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            runCatching { loadSemester(semester) }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.readableMessage()) }
                }
            pushWidgets()
        }
    }

    // ── 自定义日程 ──────────────────────────────────────────────────────

    /** 打开编辑框。[plan] 为空 = 新建 */
    fun openPlanEditor(plan: Plan? = null) {
        if (_state.value.selectedSemester == null) {
            _state.update { it.copy(notice = "还没有学期，先去「课程表设置」建一个") }
            return
        }
        _state.update { it.copy(planEditorOpen = true, editingPlan = plan) }
    }

    fun closePlanEditor() = _state.update {
        it.copy(
            planEditorOpen = false,
            editingPlan = null,
            pendingPlan = null,
            pendingConflicts = emptyList(),
        )
    }

    /** 保存前先看有没有和自己其他日程撞车；撞了就等用户确认，不撞直接存 */
    fun savePlan(spec: PlanSpec) {
        val semesterId = _state.value.selectedSemester?.id ?: return
        viewModelScope.launch {
            val conflicts = runCatching { repo.planConflicts(semesterId, spec) }
                .getOrDefault(emptyList())
            if (conflicts.isNotEmpty()) {
                _state.update {
                    it.copy(
                        pendingPlan = spec,
                        pendingConflicts = conflicts.map { s ->
                            "${ScheduleText.weekdayName(s.weekday)}第 ${s.startUnit}-${s.endUnit} 节" +
                                "《${s.courseName}》"
                        },
                    )
                }
                return@launch
            }
            writePlan(spec)
        }
    }

    /** 用户确认"仍然保存" */
    fun confirmPendingPlan() {
        val spec = _state.value.pendingPlan ?: return
        viewModelScope.launch { writePlan(spec) }
    }

    fun dismissPendingPlan() = _state.update {
        it.copy(pendingPlan = null, pendingConflicts = emptyList())
    }

    private suspend fun writePlan(spec: PlanSpec) {
        val semester = _state.value.selectedSemester ?: return
        var saved = false
        runCatching { repo.savePlan(spec) }
            .onSuccess { saved = true }
            .onFailure { e -> _state.update { it.copy(error = e.readableMessage()) } }
        closePlanEditor()
        if (saved) {
            // 桌面组件读的就是这套 Session 数据，但它的重画只在「打开 App / 课程边界」发生 ——
            // 保存后立刻去看桌面会停在旧画面，所以在保存点主动推一遍（失败无声，开屏兜底仍在）。
            pushWidgets()
            _state.update { it.copy(planNotice = "已保存到课表 · 「今天」页与桌面小组件已同步") }
        }
        loadSemester(semester)
    }

    fun importCsv(semesterId: Int, rows: List<com.upcpocket.app.domain.CsvScheduleImport.Row>,
        onComplete: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val semester = _state.value.selectedSemester
                require(semester?.id == semesterId) { "学期已切换，请重新预览" }
                val (added, skipped) = repo.importCsvRows(semesterId, rows)
                loadSemester(semester!!)
                pushWidgets()
                _state.update { it.copy(planNotice = "CSV 已导入 $added 条时段，跳过 $skipped 条重复记录") }
                onComplete(null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onComplete("导入失败，请重新选择文件或检查格式")
            }
        }
    }

    fun deletePlan(code: String) {
        val semester = _state.value.selectedSemester ?: return
        viewModelScope.launch {
            var removed = false
            runCatching { repo.deletePlan(semester.id, code) }
                .onSuccess { removed = true }
                .onFailure { e -> _state.update { it.copy(error = e.readableMessage()) } }
            closePlanEditor()
            if (removed) {
                pushWidgets()
                _state.update { it.copy(planNotice = "已从课表移除 · 桌面小组件已同步") }
            }
            loadSemester(semester)
        }
    }

    /** 补录确认提示的关闭（展示几秒后由界面收起） */
    fun clearPlanNotice() = _state.update { it.copy(planNotice = null) }

    /**
     * 点课程块。
     *
     * - 自己加的日程 → 打开编辑框（可以直接改时间或删掉）；
     * - 演示课 → 只开详情，并且明确标出"演示数据、不可编辑"；
     * - 手动补录的课程 → 详情页。
     */
    fun onSessionClick(session: Session) {
        when (session.source) {
            ScheduleRepository.SOURCE_CUSTOM -> {
                val plan = _state.value.plans.firstOrNull { it.code == session.courseCode }
                if (plan != null) {
                    openPlanEditor(plan)
                } else {
                    // 手动补录的「课程」没有对应的 Plan 条目时（历史数据），按课程详情打开
                    _state.value.courses.firstOrNull { it.code == session.courseCode }
                        ?.let { openDetail(it, demo = false) }
                }
            }
            DemoTimetable.SOURCE -> {
                _state.value.demoCourses.firstOrNull { it.code == session.courseCode }
                    ?.let { openDetail(it, demo = true) }
            }
            ScheduleRepository.SOURCE_SCHOOL -> {
                _state.value.courses.firstOrNull { it.code == session.courseCode }
                    ?.let { openDetail(it, demo = false) }
            }
        }
    }

    fun selectSemester(id: Int) {
        val semester = _state.value.semesters.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    selectedSemester = semester,
                    // 换学期时把"周次是按哪天算的"一并清掉，避免拿旧学期的周次当初始页
                    weekResolvedOn = null,
                )
            }
            loadSemester(semester)
        }
    }

    /**
     * 学校课表同步写完之后调用：先重新读学期列表（同步可能新建了一条学期），
     * 再切到它，并推一次桌面组件。
     */
    fun refreshSemestersAndSelect(id: Int) {
        viewModelScope.launch {
            val all = runCatching { repo.semesters() }
                .onFailure { e -> _state.update { it.copy(error = e.readableMessage()) } }
                .getOrDefault(emptyList())
            _state.update { it.copy(semesters = all) }
            all.firstOrNull { it.id == id }?.let { target ->
                _state.update {
                    it.copy(
                        loading = true,
                        error = null,
                        selectedSemester = target,
                        weekResolvedOn = null,
                    )
                }
                loadSemester(target)
                pushWidgets()
                _state.update {
                    it.copy(planNotice = "学校课表已同步 · 「今天」页与桌面小组件已更新")
                }
            }
        }
    }

    /** 切换教学周：纯本地，不读库 */
    fun goToWeek(week: Int) {
        if (week !in _state.value.weeks) return
        _state.update { it.copy(weekIndex = week) }
    }

    /** 设置「当前周次」—— 等价于反推开学日期，并把这次修改记为**用户设置**（已确认） */
    fun setCurrentWeek(week: Int) {
        val s = _state.value
        val upper = s.weekTotal ?: s.weeks.lastOrNull() ?: week
        if (week < 1 || week > maxOf(upper, week)) return
        val today = LocalDate.now()
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(
                weekOneStart = WeekMath.weekOneStart(today, week, s.weekStartDay),
                confirmed = true,
                source = WeekSource.USER,
            ),
            week = week,
            today = today,
        )
    }

    /** 设置「开学日期（第 1 周起始日）」；选到哪天都吸附到那一周的第一天 */
    fun setWeekStartDate(date: LocalDate) {
        val s = _state.value
        val start = WeekMath.startOfWeek(date, s.weekStartDay)
        val today = LocalDate.now()
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(
                weekOneStart = start,
                confirmed = true,
                source = WeekSource.USER,
            ),
            week = WeekMath.weekIndexAt(start, today).coerceAtLeast(1),
            today = today,
        )
    }

    /** 设置「本学期总周次」；只影响显示范围，不删数据 */
    fun setWeekTotal(total: Int?) {
        val s = _state.value
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(totalWeeks = total?.coerceIn(1, 30)),
            week = s.weekIndex,
            today = LocalDate.now(),
        )
    }

    /** 设置「每周起始日」（周一/周日） */
    fun setWeekStartDay(day: WeekStartDay) {
        val s = _state.value
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(
                startDay = day,
                weekOneStart = s.weekStart?.let { WeekMath.startOfWeek(it, day) },
            ),
            week = s.weekIndex,
            today = LocalDate.now(),
        )
    }

    /** 重置为学期里的开学日：清掉本学期的手动设置，下次进入按学期起始日重算 */
    fun resetWeekSettings() {
        val id = _state.value.selectedSemester?.id ?: return
        viewModelScope.launch {
            runCatching { uiPrefs.clearWeekSettings(id) }
            _state.value.selectedSemester?.let { loadSemester(it) }
        }
    }

    private fun ScheduleUiState.weekSettingsSnapshot() = WeekSettings(
        weekOneStart = weekStart,
        startDay = weekStartDay,
        totalWeeks = weekTotal,
        confirmed = weekConfirmed,
        source = weekSource,
    )

    /** 写设置 + 立刻按新基准重算当前周次（不重新读库） */
    private fun applyWeekSettings(settings: WeekSettings, week: Int, today: LocalDate) {
        val bounded = week.coerceIn(1, settings.totalWeeks ?: maxOf(week, 1))
        _state.update {
            it.copy(
                weekStart = settings.weekOneStart,
                weekStartDay = settings.startDay,
                weekTotal = settings.totalWeeks,
                weekConfirmed = settings.confirmed,
                weekSource = settings.source,
                weekIndex = bounded,
                weekResolvedOn = today,
                notice = null,
            )
        }
        viewModelScope.launch {
            val semester = _state.value.selectedSemester ?: return@launch
            runCatching { uiPrefs.saveWeekSettings(semester.id, settings) }
            // 开学日/总周数变了，学期本身也要跟着更新（周次列表与结束日期）
            runCatching {
                repo.saveSemesterSettings(
                    semesterId = semester.id,
                    startDate = settings.weekOneStart ?: semester.startDate,
                    totalWeeks = settings.totalWeeks,
                )
            }
            val fresh = repo.semester(semester.id)
            if (fresh != null) {
                _state.update { it.copy(selectedSemester = fresh, semesters = repo.semesters()) }
            }
        }
    }

    // ── 详情与显示覆盖 ──────────────────────────────────────────────────

    /**
     * 打开课程详情。
     *
     * [demo] 为 true 时详情页只读：演示课每次按模板重新生成，写进去的覆盖会被下一次生成盖掉，
     * 与其让用户以为改成功了，不如直接标出来。
     */
    fun openDetail(
        course: Course,
        demo: Boolean = course.sessions.firstOrNull()?.source == DemoTimetable.SOURCE,
    ) {
        viewModelScope.launch {
            val ovs = if (demo) {
                emptyList()
            } else {
                runCatching {
                    repo.displayOverrides(course.semesterId).filter { it.courseCode == course.code }
                }.getOrDefault(emptyList())
            }
            _state.update {
                it.copy(
                    detailCourse = course,
                    detailIsDemo = demo,
                    detailOverrides = DisplayOverridesView(
                        name = ovs.firstOrNull { it.kind == ScheduleRepository.KIND_NAME }?.value,
                        rooms = ovs
                            .filter {
                                it.kind == ScheduleRepository.KIND_ROOM &&
                                    it.weekday != null && it.startUnit != null
                            }
                            .associate {
                                // ⚠️ 整个键必须括号起来：`a + b to v` 会被解析成 `a + (b to v)`
                                ("${it.weekday}|${it.startUnit}" +
                                    (it.weeks?.takeIf { w -> w.isNotBlank() }
                                        ?.let { w -> "|$w" } ?: "")) to it.value
                            },
                    ),
                )
            }
        }
    }

    fun closeDetail() = _state.update {
        it.copy(detailCourse = null, detailOverrides = null, detailIsDemo = false)
    }

    /**
     * 保存显示覆盖：名称整门课；教室按时段，键 "weekday|startUnit"
     * （全部周）或 "weekday|startUnit|1,3,5"（分周）。空白 = 清除该项。
     */
    fun applyDisplayEdits(course: Course, name: String, roomEdits: Map<String, String>) {
        viewModelScope.launch {
            runCatching {
                repo.saveDisplayNameOverride(course.semesterId, course.code, name)
                roomEdits.forEach { (key, value) ->
                    val parts = key.split("|")
                    val wd = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
                    val su = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    val weeks = parts.getOrNull(2)
                        ?.takeIf { it.isNotBlank() }
                        ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                        ?.takeIf { it.isNotEmpty() }
                    repo.saveDisplayRoomOverride(course.semesterId, course.code, wd, su, weeks, value)
                }
            }
            reloadAfterDisplayEdit(course)
        }
    }

    /** 恢复一门课的本地原始显示 */
    fun resetDisplayOverrides(course: Course) {
        viewModelScope.launch {
            runCatching { repo.clearDisplayOverrides(course.semesterId, course.code) }
            reloadAfterDisplayEdit(course)
        }
    }

    private suspend fun reloadAfterDisplayEdit(old: Course) {
        _state.value.selectedSemester?.let { loadSemester(it) }
        val fresh = _state.value.courses.firstOrNull { it.key == old.key } ?: old
        openDetail(fresh, demo = false)
        // 小组件此刻还挂着改前的旧画面：读链路本来通，缺的只是这一步刷新
        pushWidgets()
    }

    /** 课程总览用的排序（按课程名） */
    fun overviewCourses(): List<Course> = _state.value.courses.sortedBy { it.nameZh }

    // ── 工具 ────────────────────────────────────────────────────────────

    /** 桌面组件重推；失败无声（桌面保持上一次内容即可，开屏还有兜底） */
    private suspend fun pushWidgets() {
        AppGraph.appContextOrNull()?.let { ctx ->
            runCatching { Widgets.refreshAll(ctx) }
                .onFailure { Log.w("ScheduleViewModel", "刷新小组件失败", it) }
        }
    }

    /** 本地数据只可能因数据库异常失败，给一句能看懂的话就够，不必区分会话/网络 */
    private fun Throwable.readableMessage(): String =
        "读取本地课表失败：${message?.take(80) ?: javaClass.simpleName}"

    companion object {
        /** 新建学期时用的默认周数（测试与设置页共用） */
        const val DEFAULT_WEEKS = LocalDefaults.DEFAULT_TOTAL_WEEKS
    }
}
