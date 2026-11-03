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
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 空闲教室页状态。
 *
 * 首次进入自动查一次“默认校区 + 今天 + 上午 + 全部教学楼”，只为加载结果和教学楼列表；
 * 之后筛选改动不会自动发请求，必须点查询。没有后台轮询。
 */
internal class FreeClassroomViewModel(
    private val repository: FreeClassroomRepository,
) : ViewModel() {

    var campus by mutableStateOf(FreeClassroomCampus.TANGDAOWAN)
        private set
    var date by mutableStateOf(LocalDate.now())
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

    /** 防止迟到的旧请求覆盖新请求；查询期间筛选控件禁用，这里是最后一道保险。 */
    private var requestSeq = 0

    init {
        search()
    }

    fun currentQuery(): FreeClassroomQuery? {
        if (periods.isEmpty()) return null
        return FreeClassroomQuery(campus, date, building, periods)
    }

    fun chooseCampus(value: FreeClassroomCampus) {
        if (campus == value) return
        campus = value
        building = "全部"
        error = null
    }

    fun chooseDate(value: LocalDate) {
        if (date == value) return
        date = value
        error = null
    }

    fun moveDate(days: Long) {
        date = date.plusDays(days)
        error = null
    }

    fun chooseBuilding(value: String) {
        if (building == value) return
        building = value
        error = null
    }

    fun toggleGroup(group: ClassroomPeriodGroup) {
        periods = toggleGroup(periods, group)
        error = null
    }

    fun applyPeriods(value: Set<Int>) {
        periods = value.filter { it in 1..13 }.toSet()
        error = null
    }

    fun search() {
        if (loading) return
        val query = currentQuery()
        if (query == null) {
            error = "请至少选择一节"
            return
        }
        val id = ++requestSeq
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val fresh = repository.query(query)
                if (id == requestSeq) result = fresh
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnknownHostException) {
                if (id == requestSeq) error = "无法连接学校服务，请检查网络后重试"
            } catch (_: SocketTimeoutException) {
                if (id == requestSeq) error = "学校服务响应超时，请稍后重试"
            } catch (failed: IOException) {
                if (id == requestSeq) error = failed.message ?: "查询失败，请检查网络后重试"
            } catch (_: Exception) {
                if (id == requestSeq) error = "查询失败，请检查网络或稍后重试"
            } finally {
                if (id == requestSeq) loading = false
            }
        }
    }
}
