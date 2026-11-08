package com.upcpocket.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.data.chaoxing.*
import com.upcpocket.app.data.library.LibraryHiddenSeat
import com.upcpocket.app.data.prefs.LibraryHiddenSeatsStore
import com.upcpocket.app.data.prefs.NoOpLibraryHiddenSeatsStore
import com.upcpocket.app.library.ChaoxingService
import com.upcpocket.app.library.ChaoxingReadContext
import java.time.Clock
import java.time.ZonedDateTime
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val usedSeatsLoaded: Boolean = false,
    val selectedSeat: String? = null,
    val reserving: Boolean = false,
    val reservationMessage: String? = null,
    val reservationUnknown: Boolean = false,
    val reservationSucceeded: Boolean = false,
    val interactionRequired: Boolean = false,
    val officialUrl: String = ChaoxingSeatKind.SEAT.officialUrl,
    val seatMode: RoomSeatMode = RoomSeatMode.SEAT,
    val querying: Boolean = false,
    val timeUnavailableReason: String? = null,
    val queryGeneration: Long = 0,
    val hiddenEntries: List<LibraryHiddenSeat> = emptyList(),
    val hiddenLoaded: Boolean = false,
    val hiddenBusy: Boolean = false,
    val showHiddenSheet: Boolean = false,
    val hiddenError: String? = null,
    val undoHidden: LibraryHiddenSeat? = null,
) {
    val hiddenIds: Set<String> get() = hiddenEntries.mapTo(linkedSetOf()) { it.seatId }
    val candidates: List<ChaoxingSeatPlace> get() = if (usedSeatsLoaded) {
        detail?.places.orEmpty().filter { it.seatNum !in usedSeats && it.seatNum !in detail?.disabledSeatNums.orEmpty() }
    } else emptyList()
}

internal val ChaoxingSeatKind.hiddenSystem: String get() = "chaoxing-$api"
// Fixed UPC tenant and library context; never school-account identity.
internal val ChaoxingSeatKind.hiddenBuilding: String get() =
    if (this == ChaoxingSeatKind.SEAT) "e32f8fbde51f9dba:4197453" else "e32f8fbde51f9dba:4258"

internal fun ChaoxingSeatUiState.validInterval(now: ZonedDateTime = chaoxingNow()): Boolean {
    val d = detail ?: return false
    val b = selectedStart ?: return false
    val e = selectedEnd ?: return false
    return e in chaoxingEndOptions(d, b, selectedDate, now)
}

private fun ChaoxingSeatRoomDetail.completeSeats(): Boolean =
    seatRulesKnown && capacity > 0 && places.size == capacity && places.map { it.seatNum }.toSet().size == capacity

internal fun ChaoxingSeatUiState.canConfirm(now: ZonedDateTime = chaoxingNow()): Boolean =
    validInterval(now) && detail?.completeSeats() == true && selectedSeat !in detail.disabledSeatNums && hiddenLoaded && !hiddenBusy && !reserving && !querying &&
        !reservationUnknown && !reservationSucceeded && usedSeatsLoaded &&
        selectedSeat != null && selectedSeat !in hiddenIds && selectedSeat !in usedSeats &&
        detail?.places?.any { it.seatNum == selectedSeat } == true

private fun ChaoxingSeatUiState.canConfirmAfterPreflight(seat: String?, now: ZonedDateTime): Boolean =
    copy(reserving = false, selectedSeat = seat).canConfirm(now)

/** Entire UI request identity; session context contains no credentials. */
private data class ChaoxingQueryKey(
    val context: ChaoxingReadContext?, val kind: ChaoxingSeatKind, val date: String,
    val room: Int?, val mode: RoomSeatMode, val start: Int?, val end: Int?, val generation: Long,
)

class ChaoxingSeatViewModel(
    private val service: ChaoxingService,
    initialKind: ChaoxingSeatKind = ChaoxingSeatKind.SEAT,
    private val hiddenStore: LibraryHiddenSeatsStore = NoOpLibraryHiddenSeatsStore,
    private val clock: Clock = Clock.system(CHAOXING_ZONE),
) : ViewModel() {
    private val _state = MutableStateFlow(ChaoxingSeatUiState(kind = initialKind, officialUrl = initialKind.officialUrl,
        dates = dateOptions(clock), selectedDate = dateOptions(clock).first()))
    val state: StateFlow<ChaoxingSeatUiState> = _state.asStateFlow()
    private var generation = 0L
    private var roomGeneration = 0L
    private var listGeneration = 0L
    private var detailContext: ChaoxingReadContext? = null
    private val hiddenLock = Mutex()

    init {
        viewModelScope.launch {
            combine(service.session, service.accountState, service.requestRevision) { _, _, _ -> service.readContext() }
                .distinctUntilChanged().collect {
                    ++generation; ++roomGeneration; ++listGeneration
                    detailContext = null
                    _state.value = _state.value.afterCloseRoom().copy(rooms = emptyList(), busy = false)
                    if (it != null) loadRooms()
                }
        }
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)
    private fun legalTimes(s: ChaoxingSeatUiState): ChaoxingSeatUiState {
        val d = s.detail ?: return s
        val instant = now()
        val starts = chaoxingStartOptions(d, s.selectedDate, instant)
        val start = s.selectedStart?.takeIf { it in starts } ?: starts.firstOrNull()
        val ends = start?.let { chaoxingEndOptions(d, it, s.selectedDate, instant) }.orEmpty()
        return s.copy(selectedStart = start, selectedEnd = s.selectedEnd?.takeIf { it in ends } ?: ends.firstOrNull(),
            timeUnavailableReason = chaoxingTimeUnavailableReason(d, s.selectedDate, instant))
    }

    private fun key() = _state.value.let {
        ChaoxingQueryKey(detailContext, it.kind, it.selectedDate, it.detail?.roomId,
            it.seatMode, it.selectedStart, it.selectedEnd, generation)
    }
    private fun current(k: ChaoxingQueryKey) = key() == k && service.isReadContextCurrent(k.context)
    private fun invalidate(s: ChaoxingSeatUiState = _state.value): ChaoxingSeatUiState {
        ++generation
        return s.copy(selectedSeat = null, usedSeats = emptySet(), usedSeatsLoaded = false,
            querying = false, queryGeneration = generation, detailError = null,
            reserving = false, reservationMessage = null, reservationUnknown = false,
            reservationSucceeded = false, interactionRequired = false)
    }

    fun selectKind(kind: ChaoxingSeatKind) {
        if (kind == _state.value.kind) return
        closeRoom()
        _state.value = _state.value.copy(kind = kind, officialUrl = kind.officialUrl, rooms = emptyList())
        loadRooms()
    }
    fun selectDate(date: String) {
        if (date !in _state.value.dates || date == _state.value.selectedDate) return
        closeRoom()
        _state.value = _state.value.copy(selectedDate = date, rooms = emptyList())
        loadRooms()
    }
    fun refresh() { loadRooms() }
    private fun loadRooms() {
        val context = service.readContext() ?: return
        val s = _state.value
        val request = ++listGeneration
        _state.value = s.copy(busy = true, error = null, sessionExpired = false)
        fun valid() = request == listGeneration && service.isReadContextCurrent(context) &&
            _state.value.kind == s.kind && _state.value.selectedDate == s.selectedDate
        viewModelScope.launch {
            try {
                val page = service.seatRooms(s.kind, s.selectedDate)
                if (valid()) _state.value = _state.value.copy(busy = false, rooms = page.rooms,
                    error = if (page.rooms.isEmpty()) "没有区域数据" else null)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (valid()) _state.value = _state.value.copy(busy = false, error = "区域读取失败，请重试。")
            }
        }
    }

    fun openRoom(room: ChaoxingSeatRoom) {
        val context = service.readContext() ?: return
        val s = _state.value
        val request = ++roomGeneration
        detailContext = context
        _state.value = invalidate(s.afterCloseRoom()).copy(detailBusy = true)
        fun valid() = request == roomGeneration && service.isReadContextCurrent(context) &&
            _state.value.kind == s.kind && _state.value.selectedDate == s.selectedDate
        viewModelScope.launch {
            try {
                val detail = service.seatRoomDetail(s.kind, room.id, s.selectedDate)
                if (!valid()) return@launch
                _state.value = legalTimes(_state.value.copy(detail = detail, detailBusy = false))
                queryUsedSeats()
                reloadHidden()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (valid()) _state.value = _state.value.copy(detailBusy = false, detailError = "座位图读取失败，请重试。")
            }
        }
    }
    fun closeRoom() {
        ++roomGeneration; ++generation
        detailContext = null
        _state.value = _state.value.afterCloseRoom()
    }
    fun setSeatMode(mode: RoomSeatMode) {
        if (_state.value.seatMode == mode) return
        _state.value = legalTimes(invalidate().copy(seatMode = mode))
        if (mode == RoomSeatMode.SEAT) queryUsedSeats()
    }
    fun selectStart(start: Int) {
        val s = _state.value
        val d = s.detail ?: return
        if (start !in chaoxingStartOptions(d, s.selectedDate, now())) return
        _state.value = legalTimes(invalidate(s).copy(selectedStart = start))
        if (s.seatMode == RoomSeatMode.SEAT) queryUsedSeats()
    }
    fun selectEnd(end: Int) {
        val s = _state.value
        val d = s.detail ?: return
        val b = s.selectedStart ?: return
        if (end !in chaoxingEndOptions(d, b, s.selectedDate, now())) return
        _state.value = invalidate(s).copy(selectedEnd = end)
        if (s.seatMode == RoomSeatMode.SEAT) queryUsedSeats()
    }
    fun searchSeats() { if (!_state.value.querying) queryUsedSeats() }
    private fun queryUsedSeats() {
        if (!_state.value.validInterval(now())) {
            _state.value = legalTimes(invalidate())
            return
        }
        _state.value = invalidate().copy(querying = true)
        val k = key()
        val s = _state.value
        viewModelScope.launch {
            try {
                val d = s.detail ?: return@launch
                if (!d.completeSeats() || d.closed) {
                    throw ChaoxingException("座位集合不完整")
                }
                val used = service.usedSeatNums(k.kind, k.room!!, k.start!!, k.end!!, k.date)
                if (!used.all { num -> d.places.any { it.seatNum == num } }) throw ChaoxingException("座位集合已变化")
                if (current(k)) _state.value = _state.value.copy(usedSeats = used, usedSeatsLoaded = true, querying = false)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (current(k)) _state.value = _state.value.copy(querying = false,
                    usedSeatsLoaded = false, detailError = "查询失败，请重试。")
            }
        }
    }
    fun selectSeat(seatNum: String) {
        val s = _state.value
        if (!service.isReadContextCurrent(detailContext) || s.reserving || s.reservationSucceeded ||
            !s.usedSeatsLoaded || s.querying || !s.hiddenLoaded || s.hiddenBusy ||
            seatNum in s.hiddenIds || seatNum in s.usedSeats || seatNum in s.detail?.disabledSeatNums.orEmpty() ||
            s.detail?.places?.none { it.seatNum == seatNum } != false) return
        _state.value = s.copy(selectedSeat = seatNum.takeUnless { it == s.selectedSeat },
            reservationMessage = null, reservationUnknown = false, interactionRequired = false)
    }
    fun reserveSelected() {
        val s = _state.value
        val k = key()
        if (!s.canConfirm(now()) || !current(k)) return
        _state.value = s.copy(reserving = true, reservationMessage = null)
        viewModelScope.launch {
            var postStarted = false
            try {
                // Read again under the same session and full selection key before POST preflight.
                val rules = service.seatRoomDetail(k.kind, k.room!!, k.date)
                if (!current(k) || !_state.value.copy(detail = rules).canConfirmAfterPreflight(s.selectedSeat, now())) {
                    if (current(k)) _state.value = _state.value.copy(reserving = false, selectedSeat = null,
                        reservationMessage = "座位或时间规则已变化，请重新选择。")
                    return@launch
                }
                val fresh = service.usedSeatNums(k.kind, k.room!!, k.start!!, k.end!!, k.date)
                if (!current(k) || _state.value.selectedSeat != s.selectedSeat || _state.value.hiddenBusy) {
                    if (current(k)) _state.value = _state.value.copy(reserving = false)
                    return@launch
                }
                if (!fresh.all { num -> rules.places.any { it.seatNum == num } }) {
                    throw ChaoxingException("座位集合已变化")
                }
                if (s.selectedSeat in fresh || s.selectedSeat in _state.value.hiddenIds) {
                    _state.value = _state.value.copy(reserving = false, selectedSeat = null,
                        usedSeats = fresh, reservationMessage = "座位状态已变化，请重新选择。")
                    return@launch
                }
                val result = service.submitReservation(k.kind, k.room, k.date, k.start, k.end,
                    s.selectedSeat!!, expectedContext = k.context,
                    selectionCurrent = { current(k) && _state.value.selectedSeat == s.selectedSeat &&
                        !_state.value.hiddenBusy && s.selectedSeat !in _state.value.hiddenIds && _state.value.validInterval(now()) },
                    onPostStarted = { postStarted = true })
                if (!current(k) || _state.value.selectedSeat != s.selectedSeat) return@launch
                _state.value = _state.value.copy(reserving = false,
                    reservationUnknown = result is ChaoxingReservationResult.Unknown,
                    reservationSucceeded = result is ChaoxingReservationResult.Success,
                    interactionRequired = result is ChaoxingReservationResult.InteractionRequired,
                    reservationMessage = when (result) {
                        is ChaoxingReservationResult.Success -> "预约成功，请按时到馆签到。"
                        is ChaoxingReservationResult.Rejected -> result.message
                        is ChaoxingReservationResult.Unknown -> result.message
                        is ChaoxingReservationResult.InteractionRequired -> result.message
                    })
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (current(k)) _state.value = _state.value.copy(reserving = false,
                    reservationUnknown = postStarted,
                    reservationMessage = if (postStarted) unknownChaoxingSubmission().message
                        else "提交前检查失败，未提交，请重试。")
            }
        }
    }

    fun openHiddenSheet() { _state.value = _state.value.copy(showHiddenSheet = true); reloadHidden() }
    fun closeHiddenSheet() { _state.value = _state.value.copy(showHiddenSheet = false) }
    fun clearHiddenNotice() { _state.value = _state.value.copy(undoHidden = null, hiddenError = null) }
    fun clearReservationMessage() { _state.value = _state.value.copy(reservationMessage = null) }
    private fun hiddenContextCurrent(k: ChaoxingQueryKey) = current(k)
    fun reloadHidden() = hiddenOperation(null)
    fun hideSeat(seat: String) = hiddenOperation(seat, hide = true)
    fun restoreHidden(seat: String) = hiddenOperation(seat, hide = false)
    fun restoreAllHidden() = hiddenOperation(null, restoreAll = true)
    private fun hiddenOperation(seat: String?, hide: Boolean = false, restoreAll: Boolean = false) {
        val s = _state.value
        val room = s.detail ?: return
        if (s.hiddenBusy || !service.isReadContextCurrent(detailContext)) return
        if (hide && room.places.none { it.seatNum == seat }) return
        val k = key()
        _state.value = s.copy(hiddenBusy = true, selectedSeat = if (hide && s.selectedSeat == seat) null else s.selectedSeat,
            reserving = if (hide && s.selectedSeat == seat) false else s.reserving, hiddenError = null)
        viewModelScope.launch {
            hiddenLock.withLock {
                try {
                    if (!hiddenContextCurrent(k)) return@withLock
                    val entry = seat?.let { LibraryHiddenSeat(s.kind.hiddenSystem, s.kind.hiddenBuilding,
                        room.roomId.toString(), it, it) }
                    val ok = when {
                        hide -> hiddenStore.hide(entry!!)
                        restoreAll -> hiddenStore.restoreAll(s.kind.hiddenSystem, s.kind.hiddenBuilding, room.roomId.toString())
                        seat != null -> hiddenStore.restore(s.kind.hiddenSystem, s.kind.hiddenBuilding, room.roomId.toString(), seat)
                        else -> true
                    }
                    if (!ok) { error("write failed") }
                    val entries = hiddenStore.entries(s.kind.hiddenSystem, s.kind.hiddenBuilding, room.roomId.toString())
                    if (hiddenContextCurrent(k)) _state.value = _state.value.copy(hiddenEntries = entries,
                        hiddenLoaded = true, hiddenBusy = false, undoHidden = entry.takeIf { hide })
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    if (hiddenContextCurrent(k)) _state.value = _state.value.copy(hiddenBusy = false,
                        hiddenLoaded = false, hiddenError = "隐藏偏好读取或保存失败，请重试。")
                } finally {
                    // A time/mode change invalidates publication, but must release the UI write gate.
                    if (_state.value.detail === room && _state.value.kind == s.kind &&
                        service.isReadContextCurrent(k.context)) {
                        _state.value = _state.value.copy(hiddenBusy = false)
                        if (!current(k)) reloadHidden()
                    }
                }
            }
        }
    }
    companion object {
        fun factory(service: ChaoxingService, initialKind: ChaoxingSeatKind = ChaoxingSeatKind.SEAT,
            hiddenStore: LibraryHiddenSeatsStore = NoOpLibraryHiddenSeatsStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChaoxingSeatViewModel(service, initialKind, hiddenStore) as T
            }
    }
}

private fun dateOptions(clock: Clock = Clock.system(CHAOXING_ZONE)): List<String> {
    val today = LocalDate.now(clock.withZone(CHAOXING_ZONE))
    return listOf(today.toString(), today.plusDays(1).toString())
}
internal fun ChaoxingSeatUiState.afterCloseRoom(): ChaoxingSeatUiState =
    ChaoxingSeatUiState(kind = kind, dates = dates, selectedDate = selectedDate,
        rooms = rooms, officialUrl = officialUrl)
internal fun formatMinute(value: Int): String = "%02d:%02d".format(value / 60, value % 60)
