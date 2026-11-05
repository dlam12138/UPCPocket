package com.upcpocket.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.data.chaoxing.ChaoxingException
import com.upcpocket.app.data.chaoxing.ChaoxingReservationResult
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
    val selectedEnd: Int? = null,
    val usedSeats: Set<String> = emptySet(),
    /** 只有成功读取过所选时段才为 true；失败时不能用空集合冒充“0 个已用”。 */
    val usedSeatsLoaded: Boolean = false,
    val selectedSeat: String? = null,
    val reserving: Boolean = false,
    val reservationMessage: String? = null,
    /** 提交结果无法确认时保持警示，不显示成功也不要一键重试。 */
    val reservationUnknown: Boolean = false,
    val reservationSucceeded: Boolean = false,
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
    private var timeRequestGeneration = 0L

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
            selectedStart = null,
            selectedEnd = null,
            selectedSeat = null,
            usedSeats = emptySet(),
            usedSeatsLoaded = false,
            reservationMessage = null,
            reservationUnknown = false,
            reservationSucceeded = false,
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
            selectedStart = null,
            selectedEnd = null,
            selectedSeat = null,
            usedSeats = emptySet(),
            usedSeatsLoaded = false,
            reservationMessage = null,
            reservationUnknown = false,
            reservationSucceeded = false,
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
        _state.value = current.copy(
            detailBusy = true,
            detailError = null,
            detail = null,
            selectedStart = null,
            selectedEnd = null,
            selectedSeat = null,
            usedSeats = emptySet(),
            usedSeatsLoaded = false,
            reservationMessage = null,
            reservationUnknown = false,
            reservationSucceeded = false,
        )
        viewModelScope.launch {
            try {
                val detail = service.seatRoomDetail(current.kind, room.id, current.selectedDate)
                if (_state.value.kind != current.kind ||
                    _state.value.selectedDate != current.selectedDate
                ) {
                    return@launch
                }
                val step = detail.timeUnit
                val start = detail.startMinute
                val end = if (step > 0) minOf(start + step, detail.endMinute) else start
                val hasInterval = end > start
                val generation = ++timeRequestGeneration
                // 座位图先落地；已用座位查询失败只让“已用”状态显示为未知，
                // 不能把整张图丢掉，也不能用空集合冒充“0 个已用”。
                _state.value = _state.value.copy(
                    detailBusy = false,
                    detail = detail,
                    selectedStart = start.takeIf { hasInterval },
                    selectedEnd = end.takeIf { hasInterval },
                    usedSeats = emptySet(),
                    usedSeatsLoaded = false,
                    detailError = null,
                )
                if (hasInterval) queryUsedSeats(detail, start, end, generation)
            } catch (expired: ChaoxingSessionExpiredException) {
                _state.value = _state.value.copy(
                    detailBusy = false,
                    sessionExpired = true,
                    detailError = "登录已失效，请重新登录。",
                )
            } catch (failure: ChaoxingException) {
                _state.value = _state.value.copy(
                    detailBusy = false,
                    detailError = "座位图读取失败，请重试。",
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
        val step = detail.timeUnit
        if (step <= 0 || start < detail.startMinute || start >= detail.endMinute) return
        val end = minOf(start + step, detail.endMinute)
        if (end <= start) return
        val generation = ++timeRequestGeneration
        _state.value = current.copy(
            selectedStart = start,
            selectedEnd = end,
            selectedSeat = null,
            usedSeats = emptySet(),
            usedSeatsLoaded = false,
            reservationMessage = null,
            reservationUnknown = false,
            reservationSucceeded = false,
            detailError = null,
        )
        queryUsedSeats(detail, start, end, generation)
    }

    fun selectEnd(end: Int) {
        val current = _state.value
        val detail = current.detail ?: return
        val start = current.selectedStart ?: return
        val step = detail.timeUnit
        if (step <= 0 || end <= start || end > detail.endMinute || (end - start) % step != 0) return
        val generation = ++timeRequestGeneration
        _state.value = current.copy(
            selectedEnd = end,
            selectedSeat = null,
            usedSeats = emptySet(),
            usedSeatsLoaded = false,
            reservationMessage = null,
            reservationUnknown = false,
            reservationSucceeded = false,
            detailError = null,
        )
        queryUsedSeats(detail, start, end, generation)
    }

    private fun queryUsedSeats(
        detail: ChaoxingSeatRoomDetail,
        start: Int,
        end: Int,
        generation: Long,
    ) {
        val kind = _state.value.kind
        val date = _state.value.selectedDate
        viewModelScope.launch {
            try {
                val used = service.usedSeatNums(kind, detail.roomId, start, end, date)
                if (generation == timeRequestGeneration &&
                    _state.value.detail?.roomId == detail.roomId &&
                    _state.value.selectedStart == start &&
                    _state.value.selectedEnd == end
                ) {
                    _state.value = _state.value.copy(
                        usedSeats = used,
                        usedSeatsLoaded = true,
                    )
                }
            } catch (expired: ChaoxingSessionExpiredException) {
                if (generation == timeRequestGeneration) {
                    _state.value = _state.value.copy(
                        sessionExpired = true,
                        usedSeatsLoaded = false,
                        detailError = "登录已失效，请重新登录。",
                    )
                }
            } catch (failure: ChaoxingException) {
                if (generation == timeRequestGeneration) {
                    _state.value = _state.value.copy(
                        usedSeatsLoaded = false,
                        detailError = "已用座位读取失败，请重试。",
                    )
                }
            } catch (_: Exception) {
                if (generation == timeRequestGeneration) {
                    _state.value = _state.value.copy(
                        usedSeatsLoaded = false,
                        detailError = "已用座位读取失败；请重试。",
                    )
                }
            }
        }
    }

    fun selectSeat(seatNum: String) {
        val current = _state.value
        if (current.reserving || current.reservationSucceeded) return
        val selectable = current.usedSeatsLoaded && seatNum !in current.usedSeats
        if (!selectable) {
            _state.value = current.copy(
                selectedSeat = null,
                reservationMessage = if (current.usedSeatsLoaded) {
                    "该座位在当前时段已用，不能提交。"
                } else {
                    "当前时段已用状态尚未取得，不能选择座位。"
                },
                reservationUnknown = false,
                reservationSucceeded = false,
            )
            return
        }
        _state.value = current.copy(
            selectedSeat = if (current.selectedSeat == seatNum) null else seatNum,
            reservationMessage = null,
            reservationUnknown = false,
            reservationSucceeded = false,
        )
    }

    /**
     * 只允许在座位/时段/已用状态都确认后提交一次。任何超时/无法解析都保留“结果未知”，
     * 不自动重试，也不在 UI 里宣称成功或失败。
     */
    fun reserveSelected() {
        val current = _state.value
        val detail = current.detail ?: return
        val seat = current.selectedSeat ?: return
        val start = current.selectedStart ?: return
        val end = current.selectedEnd ?: return
        if (current.reserving || current.reservationSucceeded) return
        if (current.reservationUnknown) {
            _state.value = current.copy(
                reservationMessage = "上一次提交结果未知；请先到官方预约记录核对，" +
                    "确认未成功后重新进入房间再试，不能连续提交。",
            )
            return
        }
        if (!current.usedSeatsLoaded) {
            _state.value = current.copy(
                reservationMessage = "当前时段已用状态尚未取得，不能提交。",
                reservationUnknown = false,
            )
            return
        }
        if (seat in current.usedSeats) {
            _state.value = current.copy(
                selectedSeat = null,
                reservationMessage = "该座位在当前时段已用，不能提交。",
                reservationUnknown = false,
            )
            return
        }
        _state.value = current.copy(
            reserving = true,
            reservationMessage = null,
            reservationUnknown = false,
            reservationSucceeded = false,
        )
        viewModelScope.launch {
            try {
                val result = service.submitReservation(
                    kind = current.kind,
                    roomId = detail.roomId,
                    date = current.selectedDate,
                    startMinute = start,
                    endMinute = end,
                    seatNum = seat,
                )
                val message = when (result) {
                    is ChaoxingReservationResult.Success ->
                        "已提交 $seat（${formatMinute(start)}–${formatMinute(end)}）。" +
                            "请到官方预约记录核对，并按时到馆签到。"
                    is ChaoxingReservationResult.Rejected -> "预约未成功：${result.message}"
                    is ChaoxingReservationResult.InteractionRequired -> result.message
                    is ChaoxingReservationResult.Unknown -> result.message
                }
                _state.value = _state.value.copy(
                    reserving = false,
                    reservationMessage = message,
                    reservationUnknown = result is ChaoxingReservationResult.Unknown,
                    reservationSucceeded = result is ChaoxingReservationResult.Success,
                )
            } catch (expired: ChaoxingSessionExpiredException) {
                _state.value = _state.value.copy(
                    reserving = false,
                    sessionExpired = true,
                    reservationMessage = "登录已失效，提交结果未知；请重新登录后到学校记录核对。",
                    reservationUnknown = true,
                )
            } catch (failure: ChaoxingException) {
                _state.value = _state.value.copy(
                    reserving = false,
                    reservationMessage = "提交前检查失败，未提交。",
                    reservationUnknown = false,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    reserving = false,
                    reservationMessage = "提交结果未知；请先到学校记录核对，不要连续重试。",
                    reservationUnknown = true,
                )
            }
        }
    }

    fun clearReservationMessage() {
        _state.value = _state.value.copy(
            reservationMessage = null,
            reservationUnknown = false,
            reservationSucceeded = false,
        )
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
                    error = if (page.rooms.isEmpty()) "没有区域数据；这不代表有座。" else null,
                )
            } catch (expired: ChaoxingSessionExpiredException) {
                _state.value = _state.value.copy(
                    busy = false,
                    sessionExpired = true,
                    error = "登录已失效，请重新登录。",
                )
            } catch (failure: ChaoxingException) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "座位区域读取失败，请重试。",
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
        selectedEnd = null,
        selectedSeat = null,
        usedSeats = emptySet(),
        usedSeatsLoaded = false,
        reserving = false,
        reservationMessage = null,
        reservationUnknown = false,
        reservationSucceeded = false,
    )

internal fun formatMinute(value: Int): String =
    "%02d:%02d".format(value / 60, value % 60)
