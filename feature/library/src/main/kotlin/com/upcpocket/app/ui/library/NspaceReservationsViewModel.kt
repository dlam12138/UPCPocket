package com.upcpocket.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.data.nspace.NspaceException
import com.upcpocket.app.data.nspace.NspaceReservation
import com.upcpocket.app.data.nspace.NspaceSessionExpiredException
import com.upcpocket.app.library.NspaceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NspaceReservationsState(
    val reservations: List<NspaceReservation> = emptyList(),
    val page: Int = 1,
    val totalPage: Int = 1,
    val totalCount: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val cancellingId: String? = null,
)

class NspaceReservationsViewModel(
    private val service: NspaceService,
) : ViewModel() {
    private val _state = MutableStateFlow(NspaceReservationsState())
    val state: StateFlow<NspaceReservationsState> = _state.asStateFlow()

    init {
        load(1)
    }

    fun load(page: Int = _state.value.page) {
        if (_state.value.busy) return
        val safePage = page.coerceAtLeast(1)
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = service.myReservations(safePage)
                _state.value = _state.value.copy(
                    busy = false,
                    reservations = result.reservations,
                    page = result.currentPage,
                    totalPage = result.totalPage,
                    totalCount = result.totalCount,
                )
            } catch (expired: NspaceSessionExpiredException) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "空间预约会话已失效，请先登录。",
                )
            } catch (failure: NspaceException) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "预约记录读取失败：${failure.code}",
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "预约记录读取失败；请重试。",
                )
            }
        }
    }

    fun cancel(reservation: NspaceReservation) {
        if (_state.value.busy || _state.value.cancellingId != null) return
        _state.value = _state.value.copy(
            cancellingId = reservation.id,
            error = null,
            message = null,
        )
        viewModelScope.launch {
            try {
                service.cancelReservation(reservation.id)
                _state.value = _state.value.copy(
                    cancellingId = null,
                    message = "已向学校提交取消；正在刷新记录。",
                )
                // 只在取消成功后重新读取，服务端状态仍以刷新结果为准。
                load(_state.value.page)
            } catch (expired: NspaceSessionExpiredException) {
                _state.value = _state.value.copy(
                    cancellingId = null,
                    error = "空间预约会话已失效，请先登录。",
                )
            } catch (failure: NspaceException) {
                _state.value = _state.value.copy(
                    cancellingId = null,
                    error = "取消失败：${failure.code}",
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    cancellingId = null,
                    error = "取消失败；未把请求当作成功。",
                )
            }
        }
    }

    companion object {
        fun factory(service: NspaceService): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NspaceReservationsViewModel(service) as T
            }
    }
}
