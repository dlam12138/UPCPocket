package com.upcpocket.app.ui.classroom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.data.classroom.ClassroomPeriodGroup
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomQuery
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val CLASSROOM_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/**
 * 空闲教室页状态。
 *
 * 首次进入自动查一次“默认校区 + 今天 + 上午 + 全部教学楼”，只为加载结果和教学楼列表；
 * 之后筛选改动不会自动发请求，必须点查询。没有后台轮询。
 *
 * “今天”固定按学校所在时区（Asia/Shanghai）计算，不跟随设备时区漂移。筛选变化会
 * 取消在途请求并递增代次；旧响应即使晚到也不能覆盖新筛选。
 */
internal class FreeClassroomViewModel(
    private val repository: FreeClassroomRepository,
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

    var result by mutableStateOf<FreeClassroomResult?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 防止迟到的旧请求覆盖新请求；筛选变化会取消在途请求并递增代次。 */
    private var requestSeq = 0
    private var searchJob: Job? = null

    init {
        search()
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
        loading = false
    }

    fun chooseCampus(value: FreeClassroomCampus) {
        if (campus == value) return
        invalidateInFlight()
        campus = value
        building = "全部"
        error = null
    }

    fun chooseDate(value: LocalDate) {
        if (date == value) return
        invalidateInFlight()
        date = value
        error = null
    }

    fun moveDate(days: Long) {
        invalidateInFlight()
        date = date.plusDays(days)
        error = null
    }

    fun chooseBuilding(value: String) {
        if (building == value) return
        invalidateInFlight()
        building = value
        error = null
    }

    fun toggleGroup(group: ClassroomPeriodGroup) {
        invalidateInFlight()
        periods = toggleGroup(periods, group)
        error = null
    }

    fun applyPeriods(value: Set<Int>) {
        invalidateInFlight()
        periods = value.filter { it in 1..13 }.toSet()
        error = null
    }

    fun search() {
        val query = currentQuery()
        if (query == null) {
            invalidateInFlight()
            error = "请至少选择一节"
            return
        }
        // 允许新查询取代旧查询；旧 job 的结果不会写回。
        searchJob?.cancel()
        val id = ++requestSeq
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
                }
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
