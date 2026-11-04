package com.upcpocket.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.data.chaoxing.ChaoxingException
import com.upcpocket.app.data.chaoxing.ChaoxingReserveRoom
import com.upcpocket.app.data.chaoxing.ChaoxingSessionExpiredException
import com.upcpocket.app.data.chaoxing.ChaoxingTenant
import com.upcpocket.app.library.ChaoxingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChaoxingReserveUiState(
    val tenant: ChaoxingTenant = ChaoxingTenant.GUZHENKOU,
    val rooms: List<ChaoxingReserveRoom> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val sessionExpired: Boolean = false,
    val detail: ChaoxingReserveRoom? = null,
    val officialUrl: String = ChaoxingTenant.GUZHENKOU.officialUrl,
)

class ChaoxingReserveViewModel(
    private val service: ChaoxingService,
    initialTenant: ChaoxingTenant = ChaoxingTenant.GUZHENKOU,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ChaoxingReserveUiState(
            tenant = initialTenant,
            officialUrl = initialTenant.officialUrl,
        ),
    )
    val state: StateFlow<ChaoxingReserveUiState> = _state.asStateFlow()

    init {
        loadRooms()
    }

    fun selectTenant(tenant: ChaoxingTenant) {
        if (_state.value.tenant == tenant || _state.value.busy) return
        _state.value = _state.value.copy(
            tenant = tenant,
            rooms = emptyList(),
            detail = null,
            error = null,
            sessionExpired = false,
            officialUrl = tenant.officialUrl,
        )
        loadRooms()
    }

    fun refresh() {
        if (_state.value.busy) return
        loadRooms()
    }

    fun openRoom(room: ChaoxingReserveRoom) {
        _state.value = _state.value.copy(detail = room)
    }

    fun closeRoom() {
        _state.value = _state.value.copy(detail = null)
    }

    private fun loadRooms() {
        val current = _state.value
        _state.value = current.copy(busy = true, error = null, sessionExpired = false)
        viewModelScope.launch {
            try {
                val rooms = service.reserveRooms(current.tenant)
                _state.value = _state.value.copy(
                    busy = false,
                    rooms = rooms,
                    error = if (rooms.isEmpty()) "学校返回空研讨间列表；未当作可预约。" else null,
                )
            } catch (expired: ChaoxingSessionExpiredException) {
                _state.value = _state.value.copy(
                    busy = false,
                    sessionExpired = true,
                    error = "Chaoxing 会话已失效，请重新网页登录。",
                )
            } catch (failure: ChaoxingException) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = when (failure.code) {
                        "chaoxing_tenant_context" ->
                            "该研讨间入口需要学校业务目录生成的租户上下文；" +
                                "当前只提供官方页回退，未把 302 当作空列表。"
                        else -> "研讨间读取失败：${failure.code}"
                    },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "研讨间读取失败；请重试。",
                )
            }
        }
    }

    companion object {
        fun factory(
            service: ChaoxingService,
            initialTenant: ChaoxingTenant = ChaoxingTenant.GUZHENKOU,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChaoxingReserveViewModel(service, initialTenant) as T
            }
    }
}

internal fun ChaoxingReserveRoom.todayInterval(weekday: Int): String? {
    val interval = intervals.firstOrNull { it.weekday == weekday } ?: return null
    return "${interval.start}-${interval.end}"
}
