package com.upcpocket.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.data.nspace.NspaceException
import com.upcpocket.app.data.nspace.NspaceRoom
import com.upcpocket.app.data.nspace.NspaceRoomType
import com.upcpocket.app.data.nspace.NspaceSession
import com.upcpocket.app.data.nspace.NspaceSessionExpiredException
import com.upcpocket.app.data.nspace.NspaceVenue
import com.upcpocket.app.library.NspaceAuthFailure
import com.upcpocket.app.library.NspaceLoginResult
import com.upcpocket.app.library.NspaceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NspaceUiState(
    val session: NspaceSession? = null,
    val venues: List<NspaceVenue> = emptyList(),
    val roomTypes: List<NspaceRoomType> = emptyList(),
    val dates: List<String> = emptyList(),
    val rooms: List<NspaceRoom> = emptyList(),
    val selectedVenueId: String? = null,
    val selectedRoomTypeId: String? = null,
    val selectedDate: String? = null,
    val page: Int = 1,
    val totalPage: Int = 1,
    val totalCount: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val sessionExpired: Boolean = false,
)

class NspaceViewModel(
    private val service: NspaceService,
) : ViewModel() {
    private val _state = MutableStateFlow(NspaceUiState())
    val state: StateFlow<NspaceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            service.session.collect { session ->
                _state.value = _state.value.copy(session = session)
            }
        }
    }

    fun login() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, sessionExpired = false)
        viewModelScope.launch {
            when (val result = service.loginWithSavedAccount()) {
                NspaceLoginResult.Success -> loadCatalog()
                is NspaceLoginResult.Failure -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = nspaceMessage(result.reason),
                        sessionExpired = result.reason == NspaceAuthFailure.SESSION_EXPIRED,
                    )
                }
            }
        }
    }

    fun selectVenue(id: String) {
        if (id == _state.value.selectedVenueId || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                selectedVenueId = id,
                selectedRoomTypeId = null,
                selectedDate = null,
                roomTypes = emptyList(),
                dates = emptyList(),
                rooms = emptyList(),
                error = null,
            )
            loadTypesAndRooms()
        }
    }

    fun selectRoomType(id: String) {
        if (id == _state.value.selectedRoomTypeId || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                selectedRoomTypeId = id,
                selectedDate = null,
                dates = emptyList(),
                rooms = emptyList(),
                error = null,
            )
            loadDatesAndRooms()
        }
    }

    fun selectDate(date: String) {
        if (date == _state.value.selectedDate || _state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(selectedDate = date, page = 1, error = null)
            loadPage(1)
        }
    }

    fun previousPage() {
        val current = _state.value
        if (current.busy || current.page <= 1) return
        loadPage(current.page - 1)
    }

    fun nextPage() {
        val current = _state.value
        if (current.busy || current.page >= current.totalPage) return
        loadPage(current.page + 1)
    }

    fun refresh() {
        if (_state.value.busy) return
        viewModelScope.launch { loadCatalog() }
    }

    private suspend fun loadCatalog() {
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val venues = service.venues()
            if (venues.isEmpty()) {
                _state.value = _state.value.copy(
                    busy = false,
                    venues = emptyList(),
                    error = "学校返回空馆区列表；没有把它当作可预约。",
                )
                return
            }
            val venue = venues.first()
            val types = service.roomTypes(venue.id)
            val type = types.firstOrNull { it.enabled } ?: types.firstOrNull()
            val dates = if (type == null) emptyList() else {
                service.openDates(venue.id, type.id)
            }
            val date = dates.firstOrNull()
            val page = if (type == null || date == null) null else {
                service.rooms(venue.id, type.id, date, 1)
            }
            _state.value = _state.value.copy(
                busy = false,
                session = service.session.value,
                venues = venues,
                selectedVenueId = venue.id,
                roomTypes = types,
                selectedRoomTypeId = type?.id,
                dates = dates,
                selectedDate = date,
                rooms = page?.rooms.orEmpty(),
                page = page?.currentPage ?: 1,
                totalPage = page?.totalPage ?: 1,
                totalCount = page?.totalCount ?: 0,
                error = if (type == null) "学校未返回可用的空间类型。" else null,
            )
        } catch (expired: NspaceSessionExpiredException) {
            _state.value = _state.value.copy(
                busy = false,
                session = null,
                sessionExpired = true,
                error = "空间预约会话已失效，请重新登录。",
            )
        } catch (failure: NspaceException) {
            _state.value = _state.value.copy(
                busy = false,
                error = "空间预约返回异常：${failure.code}",
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                busy = false,
                error = "空间预约读取失败；请重试或打开官方页面。",
            )
        }
    }

    private suspend fun loadTypesAndRooms() {
        val current = _state.value
        val venue = current.selectedVenueId ?: return
        _state.value = current.copy(busy = true, error = null)
        try {
            val types = service.roomTypes(venue)
            val type = types.firstOrNull { it.enabled } ?: types.firstOrNull()
            val dates = if (type == null) emptyList() else service.openDates(venue, type.id)
            val date = dates.firstOrNull()
            val page = if (type == null || date == null) null else {
                service.rooms(venue, type.id, date, 1)
            }
            _state.value = _state.value.copy(
                busy = false,
                roomTypes = types,
                selectedRoomTypeId = type?.id,
                dates = dates,
                selectedDate = date,
                rooms = page?.rooms.orEmpty(),
                page = page?.currentPage ?: 1,
                totalPage = page?.totalPage ?: 1,
                totalCount = page?.totalCount ?: 0,
                error = if (type == null) "学校未返回可用的空间类型。" else null,
            )
        } catch (expired: NspaceSessionExpiredException) {
            sessionExpired(expired)
        } catch (failure: NspaceException) {
            _state.value = _state.value.copy(busy = false, error = "空间预约返回异常：${failure.code}")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.value = _state.value.copy(busy = false, error = "空间预约读取失败；请重试。")
        }
    }

    private suspend fun loadDatesAndRooms() {
        val current = _state.value
        val venue = current.selectedVenueId ?: return
        val type = current.selectedRoomTypeId ?: return
        _state.value = current.copy(busy = true, error = null)
        try {
            val dates = service.openDates(venue, type)
            val date = dates.firstOrNull()
            val page = if (date == null) null else service.rooms(venue, type, date, 1)
            _state.value = _state.value.copy(
                busy = false,
                dates = dates,
                selectedDate = date,
                rooms = page?.rooms.orEmpty(),
                page = page?.currentPage ?: 1,
                totalPage = page?.totalPage ?: 1,
                totalCount = page?.totalCount ?: 0,
                error = if (date == null) "学校未返回开放日期。" else null,
            )
        } catch (expired: NspaceSessionExpiredException) {
            sessionExpired(expired)
        } catch (failure: NspaceException) {
            _state.value = _state.value.copy(busy = false, error = "空间预约返回异常：${failure.code}")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.value = _state.value.copy(busy = false, error = "空间预约读取失败；请重试。")
        }
    }

    private fun loadPage(page: Int) {
        val current = _state.value
        val venue = current.selectedVenueId ?: return
        val type = current.selectedRoomTypeId ?: return
        val date = current.selectedDate ?: return
        viewModelScope.launch {
            _state.value = current.copy(busy = true, error = null)
            try {
                val result = service.rooms(venue, type, date, page)
                _state.value = _state.value.copy(
                    busy = false,
                    rooms = result.rooms,
                    page = result.currentPage,
                    totalPage = result.totalPage,
                    totalCount = result.totalCount,
                )
            } catch (expired: NspaceSessionExpiredException) {
                sessionExpired(expired)
            } catch (failure: NspaceException) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = "空间预约返回异常：${failure.code}",
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(busy = false, error = "分页读取失败；请重试。")
            }
        }
    }

    private fun sessionExpired(expired: NspaceSessionExpiredException) {
        _state.value = _state.value.copy(
            busy = false,
            session = null,
            sessionExpired = true,
            error = "空间预约会话已失效，请重新登录。",
        )
    }

    companion object {
        fun factory(service: NspaceService): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NspaceViewModel(service) as T
            }
    }
}

private fun nspaceMessage(reason: NspaceAuthFailure): String = when (reason) {
    NspaceAuthFailure.INVALID_CREDENTIALS -> "学校认证未通过；请检查学校账号，不会自动重试。"
    NspaceAuthFailure.INTERACTION_REQUIRED ->
        "学校要求验证码或二次验证；请使用官方空间预约页面完成登录。"
    NspaceAuthFailure.NETWORK_UNAVAILABLE -> "网络不可用；未建立空间预约会话。"
    NspaceAuthFailure.TLS_ERROR -> "TLS 校验失败；未绕过证书。"
    NspaceAuthFailure.UPSTREAM_ERROR -> "学校返回异常；未把失败当作空列表。"
    NspaceAuthFailure.PROTOCOL_ERROR -> "空间预约响应不符合已验证契约。"
    NspaceAuthFailure.ACCOUNT_UNAVAILABLE -> "没有可用于空间预约的已保存学校账号。"
    NspaceAuthFailure.ACCOUNT_CHANGED -> "账号已变化；本次登录结果未发布。"
    NspaceAuthFailure.ACCESS_DENIED -> "学校拒绝了本次空间预约认证。"
    NspaceAuthFailure.IDENTITY_MISMATCH -> "学校返回的本人与当前账号不一致。"
    NspaceAuthFailure.SESSION_EXPIRED -> "空间预约会话已失效。"
}
