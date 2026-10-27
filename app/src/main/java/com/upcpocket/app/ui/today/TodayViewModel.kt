package com.upcpocket.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.repo.ScheduleRepository
import com.upcpocket.app.domain.FocusStats
import com.upcpocket.app.domain.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 今天页的状态。
 *
 * 全部是本地事实：学期是哪一段、第几周、今天专注了多久、连续几天。
 * 没有"电量/余额"这类需要校园接口的字段 —— 它们随功能一起删掉了。
 */
data class TodayUiState(
    val loaded: Boolean = false,
    /** 演示模式是否开着（决定要不要在首屏挂演示横幅） */
    val demoMode: Boolean = false,
    /** 当前学期名；没有学期时为空串 */
    val semesterName: String = "",
    /** 当前第几周；学期外为 null */
    val week: Int? = null,
    val totalWeeks: Int = 0,
    /** 开学日；null = 没设置 */
    val startDate: LocalDate? = null,
    /** 今天专注的秒数与番茄数 */
    val todayFocusSeconds: Int = 0,
    val todayFocusCount: Int = 0,
    /** 连续专注天数（含今天；今天没有记录时为 0） */
    val streakDays: Int = 0,
) {
    /** 「2025-2026 第一学期 · 第 3 周 / 共 18 周」 */
    val semesterLine: String
        get() {
            if (semesterName.isBlank()) return "还没有学期"
            val weekPart = week?.let { "第 $it 周" } ?: "不在学期内"
            val totalPart = if (totalWeeks > 0) " / 共 $totalWeeks 周" else ""
            return "$semesterName · $weekPart$totalPart"
        }

    /** 「开学日 9 月 1 日 · 含演示课表」这类补充说明 */
    val courseSummary: String
        get() = buildList {
            startDate?.let { add("开学日 ${it.monthValue} 月 ${it.dayOfMonth} 日") }
            if (week == null && startDate != null) add("今天不在教学周内")
            if (demoMode) add("含演示课表")
        }.joinToString(" · ").ifBlank { "点这里去看课表" }
}

class TodayViewModel : ViewModel() {

    private val repo: ScheduleRepository = AppGraph.scheduleRepository

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** 从课表/专注页切回来时重算（今天专注了多少会变） */
    fun onResume() {
        viewModelScope.launch { load() }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val prefs = runCatching { AppGraph.uiPrefsStore.flow.first() }.getOrNull()
        val semester = runCatching { repo.currentSemester() }.getOrNull()
        val week = semester?.let { runCatching { repo.resolveCurrentWeek(it, today) }.getOrNull() }

        // 今天（按 FocusStats 的口径：凌晨 4 点起算）的专注记录
        val dayStart = FocusStats.dayStart(FocusStats.dayKey(now))
        val dayEnd = FocusStats.dayEnd(FocusStats.dayKey(now))
        val records = runCatching { AppGraph.focusRepository.recordsSince(dayStart) }
            .getOrDefault(emptyList())
        val focuses = records.filter { it.phase == Phase.FOCUS && it.startedAt < dayEnd }

        _state.update {
            it.copy(
                loaded = true,
                demoMode = prefs?.demoMode ?: true,
                semesterName = semester?.displayName.orEmpty(),
                week = week,
                totalWeeks = semester?.weekCount ?: 0,
                startDate = semester?.startDate,
                todayFocusSeconds = focuses.sumOf { r -> r.actualSeconds.coerceAtLeast(0) },
                todayFocusCount = focuses.count { r -> r.completed },
                streakDays = streakDays(),
            )
        }
    }

    /**
     * 连续专注天数。
     *
     * 口径：从今天往前数，**连续有专注记录**的天数；今天还没有记录时算 0
     * （不把"昨天之前连续"说成"连续 N 天"，那会让人以为今天已经做完了）。
     * 只看最近 400 天，和统计页的窗口一致。
     */
    private suspend fun streakDays(): Int {
        val from = FocusStats.dayStart(
            FocusStats.dayKey(System.currentTimeMillis()).minusDays(400),
        )
        val records = runCatching { AppGraph.focusRepository.recordsSince(from) }
            .getOrDefault(emptyList())
        val byDay = FocusStats.secondsByDay(records)
        var day = FocusStats.dayKey(System.currentTimeMillis())
        var streak = 0
        while ((byDay[day] ?: 0) > 0) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }
}
