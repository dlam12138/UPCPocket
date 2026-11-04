package com.upcpocket.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.data.chaoxing.ChaoxingException
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoom
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail
import com.upcpocket.app.data.chaoxing.ChaoxingSessionExpiredException
import com.upcpocket.app.library.ChaoxingService
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChaoxingSeatUiState(
    val kind: ChaoxingSeatKind = ChaoxingSeatKind.SEAT,
    val dates: List<String> = dateOptions(),
    val selectedDate: String = dateOptions().first(),
    val rooms: List<ChaoxingSeatRoom> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val sessionExpired: Boolean = false,
    val detail: ChaoxingSeatRoomDetail? = null,
    val detailBusy: Boolean = false,
    val detailError: String? = null,
    val selectedStart: Int? = null,
    val usedSeats: Set<String> = emptySet(),
    val officialUrl: String = ChaoxingSeatKind.SEAT.officialUrl,
)

class ChaoxingSeatViewModel(
    private val service: ChaoxingService,
    initialKind: ChaoxingSeatKind = ChaoxingSeatKind.SEAT,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ChaoxingSeatUiState(
            kind = initialKind,
            officialUrl = initialKind.officialUrl,
        ),
    )
    val state: StateFlow<ChaoxingSeatUiState> = _state.asStateFlow()

    init {
        loadRooms()
    }

    fun selectKind(kind: ChaoxingSeatKind) {
        if (_state.value.kind == kind || _state.value.busy) return
        _state.value = _state.value.copy(
            kind = kind,
            officialUrl = kind.officialUrl,
            rooms = emptyList(),
            detail = null,
            error = null,
            sessionExpired = false,
        )
        loadRooms()
    }

    fun selectDate(date: String) {
        if (_state.value.selectedDate == date || _state.value.busy) return
        _state.value = _state.value.copy(
            selectedDate = date,
            rooms = emptyList(),
            detail = null,
            error = null,
        )
        loadRooms()
    }

    fun refresh() {
        if (_state.value.busy) return
        loadRooms()
    }

    fun openRoom(room: ChaoxingSeatRoom) {
        val current = _state.value
        if (current.detailBusy) return
        _state.value = current.copy(detailBusy = true, detailError = null, detail = null)
        viewModelScope.launch {
            try {
                val detail = service.seatRoomDetail(current.kind, room.id, current.selectedDate)
                val start = detail.startMinute
                val end = minOf(start + 60, detail.endMinute)
                val used = if (end > start) {
                    service.usedSeatNums(
                        current.kind,
                        room.id,
                        start,
                        end,
                        current.selectedDate,
                    )
                } else {
                    emptySet()
                }
                _state.value = _state.value.copy(
                    detailBusy = false,
                    detail = detail,
                    selectedStart = start.takeIf { end > start },
                    usedSeats = used,
                )
            } catch (expired: ChaoxingSessionExpiredException) {
                _state.value = _state.value.copy(
                    detailBusy = false,
                    sessionExpired = true,
                    detailError = "Chaoxing 会话已失效，请重新网页登录。",
                )
            } catch (failure: ChaoxingException) {
                _state.value = _state.value.copy(
                    detailBusy = false,
                    detailError = "座位图读取失败：${failure.code}",
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    detailBusy = false,
                    detailError = "座位图读取失败；请重试。",
                )
            }
        }
    }

    fun selectStart(start: Int) {
        val current = _state.value
        val detail = current.detail ?: return
        if (start < detail.startMinute || start >= detail.endMinute) return
        val end = minOf(start + 60, detail.endMinute)
        if (end <= start) return
        _state.value = current.copy(selectedStart = start, usedSeats = emptySet())
        viewModelScope.launch {
            try {
                val used = service.usedSeatNums(
                    current.kind,
                    detail.roomId,
                    start,
                    end,
                    current.selectedDate,
                )
                if (_state.value.detail?.roomId == detail.roomId) {
                    _state.value = _state.value.copy(usedSeats = used)
                }
            } catch (expired: ChaoxingSessionExpiredException) {
                _state.value = _state.value.copy(
                    sessionExpired = true,
                    detailError = "Chaoxing 会话已失效，请重新网页登录。",
                )
            } catch (failure: ChaoxingException) {
                _state.value = _state.value.copy(
                    detailError = "已用座位读取失败：${failure.code}",
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(detailError = "已用座位读取失败；请重试。")
            }
        }
    }

    fun closeRoom() {
        _state.value = _state.value.afterCloseRoom()
    }

    private fun loadRooms() {
        val current = _state.value
        _state.value = current.copy(busy = true, error = null, sessionExpired = false)
        viewModelScope.launch {
            try {
                val page = service.seatRooms(current.kind, current.selectedDate)
                _state.value = _state.value.copy(
                    busy = false,
                    rooms = page.rooms,
                    error = if (page.rooms.isEmpty()) "学校返回空区域列表；未当作可预约。" else null,
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
                    error = "座位区域读取失败：${failure.code}",
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "座位区域读取失败；请重试。",
                )
            }
        }
    }

    companion object {
        fun factory(
            service: ChaoxingService,
            initialKind: ChaoxingSeatKind = ChaoxingSeatKind.SEAT,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChaoxingSeatViewModel(service, initialKind) as T
            }
    }
}

internal fun dateOptions(): List<String> {
    val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    return listOf(today.toString(), today.plusDays(1).toString())
}

internal fun ChaoxingSeatUiState.afterCloseRoom(): ChaoxingSeatUiState =
    copy(
        detail = null,
        detailBusy = false,
        detailError = null,
        selectedStart = null,
        usedSeats = emptySet(),
    )

internal fun formatMinute(value: Int): String =
    "%02d:%02d".format(value / 60, value % 60)
