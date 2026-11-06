package com.upcpocket.app.ui.classroom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.data.classroom.ClassroomPeriodGroup
import com.upcpocket.app.data.classroom.ClassroomQueryMode
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomCampusStore
import com.upcpocket.app.data.classroom.FreeClassroomQuery
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
import com.upcpocket.app.data.classroom.NoOpFreeClassroomCampusStore
import com.upcpocket.app.data.classroom.toggleGroup
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CLASSROOM_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/**
 * 空闲教室页状态。
 *
 * 每次真正进入页面都从上海时区今天、上午和“全部教学楼”开始，只恢复上次校区；
 * 读取保存校区完成后才发一次初始查询，不会先用默认校区查一遍再换校区。
 *
 * 预设模式（上午/下午/晚上等快捷组）切换校区或有效日期后自动查询；自定义节次
 * 模式只更新条件，必须点“查询”。同一查询键已有成功结果或正在查询时不重复发请求。
 * 页面没有后台轮询。
 *
 * “今天”固定按学校所在时区（Asia/Shanghai）计算，不跟随设备时区漂移。筛选变化会
 * 取消在途请求并递增代次；旧响应即使晚到也不能覆盖新筛选。
 */
internal class FreeClassroomViewModel(
    private val repository: FreeClassroomRepository,
    private val campusStore: FreeClassroomCampusStore = NoOpFreeClassroomCampusStore,
    private val clock: Clock = Clock.system(CLASSROOM_ZONE),
) : ViewModel() {

    var campus by mutableStateOf(FreeClassroomCampus.TANGDAOWAN)
        private set
    var date by mutableStateOf(LocalDate.now(clock))
        private set
    var building by mutableStateOf("全部")
        private set
    var periods by mutableStateOf(ClassroomPeriodGroup.MORNING.periods)
        private set
    var mode by mutableStateOf(ClassroomQueryMode.PRESET)
        private set

    var result by mutableStateOf<FreeClassroomResult?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * 读取上次校区期间禁用筛选。
     *
     * 这样页面不会先用默认校区发请求，等本地校区恢复后又发一次。
     */
    var restoringCampus by mutableStateOf(true)
        private set

    /** 防止迟到的旧请求覆盖新请求；筛选变化会取消在途请求并递增代次。 */
    private var requestSeq = 0
    private var searchJob: Job? = null
    private var activeQuery: FreeClassroomQuery? = null
    private var lastSuccessfulQuery: FreeClassroomQuery? = null

    init {
        viewModelScope.launch {
            val saved = try {
                campusStore.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (saved != null) campus = saved
            restoringCampus = false
            autoSearch()
        }
    }

    fun today(): LocalDate = LocalDate.now(clock)

    fun currentQuery(): FreeClassroomQuery? {
        if (periods.isEmpty()) return null
        return FreeClassroomQuery(campus, date, building, periods)
    }

    /** 筛选一变，旧请求就不再属于当前页面；迟到结果不得写回。 */
    private fun invalidateInFlight() {
        if (searchJob != null) {
            Diagnostics.record(
                module = DiagnosticModule.CLASSROOM,
                action = DiagnosticAction.QUERY_CHANGED,
                result = DiagnosticResult.CANCELLED,
                reason = DiagnosticReason.FILTER_CHANGED,
            )
        }
        requestSeq++
        searchJob?.cancel()
        searchJob = null
        activeQuery = null
        loading = false
    }

    fun chooseCampus(value: FreeClassroomCampus) {
        if (restoringCampus || campus == value) return
        invalidateInFlight()
        campus = value
        building = "全部"
        error = null
        persistCampus(value)
        if (mode == ClassroomQueryMode.PRESET) autoSearch()
    }

    fun chooseDate(value: LocalDate) {
        if (restoringCampus || date == value) return
        invalidateInFlight()
        date = value
        error = null
        if (mode == ClassroomQueryMode.PRESET) autoSearch()
    }

    fun moveDate(days: Long) {
        if (restoringCampus) return
        invalidateInFlight()
        date = date.plusDays(days)
        error = null
        if (mode == ClassroomQueryMode.PRESET) autoSearch()
    }

    fun chooseBuilding(value: String) {
        if (restoringCampus || building == value) return
        invalidateInFlight()
        building = value
        error = null
        // 教学楼选择保持手动查询；“查询全部”和底部查询按钮都走显式 search()。
    }

    /**
     * 点击上午/下午/晚上等快捷组。
     *
     * 预设模式下按原有规则叠加/取消整组；自定义模式下点击快捷组表示切回预设，
     * 直接采用该组，随后恢复自动查询。
     */
    fun choosePreset(group: ClassroomPeriodGroup) {
        if (restoringCampus) return
        invalidateInFlight()
        val next = if (mode == ClassroomQueryMode.CUSTOM) {
            group.periods
        } else {
            toggleGroup(periods, group)
        }
        mode = ClassroomQueryMode.PRESET
        periods = next
        error = null
        autoSearch()
    }

    /** 自选节次只更新条件，不自动查询；切回预设后由 [choosePreset] 恢复自动查询。 */
    fun applyPeriods(value: Set<Int>) {
        if (restoringCampus) return
        invalidateInFlight()
        mode = ClassroomQueryMode.CUSTOM
        periods = value.filter { it in 1..13 }.toSet()
        error = null
    }

    /** 手动查询/重试始终按当前条件发一次请求，即使已有同一键的结果。 */
    fun search() {
        if (restoringCampus) return
        val query = currentQuery()
        if (query == null) {
            invalidateInFlight()
            error = "请至少选择一节"
            return
        }
        dispatch(query)
    }

    /**
     * 自动查询：同一有效查询键正在请求或已有成功结果时不重复发请求。
     * 失败后没有成功结果，再次触发或被用户手动重试时会重新请求。
     */
    private fun autoSearch() {
        val query = currentQuery() ?: return
        if (query == activeQuery) return
        if (query == lastSuccessfulQuery && result?.query == query) return
        dispatch(query)
    }

    private fun dispatch(query: FreeClassroomQuery) {
        // 允许新查询取代旧查询；旧 job 的结果不会写回。
        searchJob?.cancel()
        val id = ++requestSeq
        activeQuery = query
        val operationId = Diagnostics.newOperationId()
        val started = System.currentTimeMillis()
        Diagnostics.record(
            module = DiagnosticModule.CLASSROOM,
            action = DiagnosticAction.QUERY_START,
            result = DiagnosticResult.PENDING,
            operationId = operationId,
        )
        loading = true
        error = null
        searchJob = viewModelScope.launch {
            try {
                val fresh = repository.query(query)
                if (id == requestSeq) {
                    result = fresh
                    lastSuccessfulQuery = fresh.query
                    val duration = (System.currentTimeMillis() - started).coerceAtLeast(0)
                    Diagnostics.record(
                        module = DiagnosticModule.CLASSROOM,
                        action = DiagnosticAction.QUERY_COMPLETE,
                        result = DiagnosticResult.OK,
                        reason = if (fresh.rooms.isEmpty()) {
                            DiagnosticReason.EMPTY
                        } else {
                            null
                        },
                        durationMs = duration,
                        operationId = operationId,
                    )
                } else {
                    Diagnostics.record(
                        module = DiagnosticModule.CLASSROOM,
                        action = DiagnosticAction.STALE_RESULT,
                        result = DiagnosticResult.SKIPPED,
                        reason = DiagnosticReason.STALE,
                        operationId = operationId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnknownHostException) {
                if (id == requestSeq) {
                    error = "无法连接学校服务，请检查网络后重试"
                    recordQueryFailure(
                        operationId,
                        started,
                        DiagnosticReason.NETWORK,
                        null,
                    )
                }
            } catch (_: SocketTimeoutException) {
                if (id == requestSeq) {
                    error = "学校服务响应超时，请稍后重试"
                    recordQueryFailure(
                        operationId,
                        started,
                        DiagnosticReason.NETWORK,
                        null,
                        DiagnosticResult.TIMEOUT,
                    )
                }
            } catch (failed: IOException) {
                if (id == requestSeq) {
                    error = failed.message ?: "查询失败，请检查网络后重试"
                    recordQueryFailure(
                        operationId,
                        started,
                        DiagnosticReason.NETWORK,
                        failed,
                    )
                }
            } catch (failed: Exception) {
                if (id == requestSeq) {
                    error = "查询失败，请检查网络或稍后重试"
                    recordQueryFailure(
                        operationId,
                        started,
                        DiagnosticReason.UNKNOWN,
                        failed,
                    )
                }
            } finally {
                if (id == requestSeq) {
                    loading = false
                    searchJob = null
                    activeQuery = null
                }
            }
        }
    }

    /**
     * 校区写入使用 NonCancellable：用户选完立即返回时，页面销毁不应丢掉这次偏好。
     * 写入失败只影响下次默认校区，不影响本次查询。
     */
    private fun persistCampus(value: FreeClassroomCampus) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                runCatching { campusStore.write(value) }
            }
        }
    }

    private fun recordQueryFailure(
        operationId: String,
        started: Long,
        reason: DiagnosticReason,
        failure: Throwable?,
        result: DiagnosticResult = DiagnosticResult.FAIL,
    ) {
        val duration = (System.currentTimeMillis() - started).coerceAtLeast(0)
        if (failure == null) {
            Diagnostics.record(
                module = DiagnosticModule.CLASSROOM,
                action = DiagnosticAction.QUERY_FAIL,
                result = result,
                reason = reason,
                durationMs = duration,
                operationId = operationId,
            )
        } else {
            Diagnostics.recordException(
                module = DiagnosticModule.CLASSROOM,
                action = DiagnosticAction.QUERY_FAIL,
                result = result,
                reason = reason,
                failure = failure,
                durationMs = duration,
                operationId = operationId,
            )
        }
    }
}
