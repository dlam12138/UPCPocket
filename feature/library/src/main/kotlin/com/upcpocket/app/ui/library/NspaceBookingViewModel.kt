package com.upcpocket.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.data.nspace.NspaceBookingRequest
import com.upcpocket.app.data.nspace.NspaceBookingRules
import com.upcpocket.app.data.nspace.NspaceException
import com.upcpocket.app.data.nspace.NspaceFormField
import com.upcpocket.app.data.nspace.NspaceRoomDetail
import com.upcpocket.app.data.nspace.NspaceSessionExpiredException
import com.upcpocket.app.data.nspace.NspaceUseType
import com.upcpocket.app.library.NspaceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NspaceBookingState(
    val detail: NspaceRoomDetail? = null,
    val useTypes: List<NspaceUseType> = emptyList(),
    val fields: List<NspaceFormField> = emptyList(),
    val selectedUseType: String? = null,
    val start: Int? = null,
    val end: Int? = null,
    val values: Map<String, String> = emptyMap(),
    val busy: Boolean = true,
    val submitting: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
) {
    val eligibilityReason: String?
        get() {
            val detail = detail ?: return null
            return NspaceBookingRules.nativeEligibilityReason(detail, fields, useTypes)
        }

    val startOptions: List<Int>
        get() = detail?.let { NspaceBookingRules.startOptions(it) }.orEmpty()

    val endOptions: List<Int>
        get() = detail?.let { start?.let { s -> NspaceBookingRules.endOptions(it, s) } }.orEmpty()
}

class NspaceBookingViewModel(
    private val service: NspaceService,
    private val roomId: String,
    private val date: String,
) : ViewModel() {
    private val _state = MutableStateFlow(NspaceBookingState())
    val state: StateFlow<NspaceBookingState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = NspaceBookingState()
        viewModelScope.launch {
            try {
                val detail = service.roomDetail(roomId, date, seatNo = "")
                val types = service.useTypes()
                val fields = service.requiredFields()
                val values = NspaceBookingRules.initialValues(fields)
                val start = NspaceBookingRules.startOptions(detail).firstOrNull()
                val end = start?.let { NspaceBookingRules.endOptions(detail, it).firstOrNull() }
                _state.value = NspaceBookingState(
                    detail = detail,
                    useTypes = types,
                    fields = fields,
                    selectedUseType = types.firstOrNull()?.id,
                    start = start,
                    end = end,
                    values = values,
                    busy = false,
                )
            } catch (expired: NspaceSessionExpiredException) {
                _state.value = NspaceBookingState(
                    busy = false,
                    error = "空间预约会话已失效，请先登录。",
                )
            } catch (failure: NspaceException) {
                _state.value = NspaceBookingState(
                    busy = false,
                    error = "预约规则读取失败：${failure.code}",
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = NspaceBookingState(
                    busy = false,
                    error = "预约规则读取失败；请重试。",
                )
            }
        }
    }

    fun selectUseType(id: String) {
        _state.value = _state.value.copy(selectedUseType = id)
    }

    fun selectStart(start: Int) {
        val detail = _state.value.detail ?: return
        val end = NspaceBookingRules.endOptions(detail, start).firstOrNull()
        _state.value = _state.value.copy(start = start, end = end)
    }

    fun selectEnd(end: Int) {
        _state.value = _state.value.copy(end = end)
    }

    fun updateField(field: String, value: String) {
        _state.value = _state.value.copy(values = _state.value.values + (field to value))
    }

    fun submit() {
        val current = _state.value
        val detail = current.detail ?: return
        if (current.submitting || current.busy) return
        current.eligibilityReason?.let {
            _state.value = current.copy(error = it)
            return
        }
        val start = current.start
        val end = current.end
        if (start == null || end == null) {
            _state.value = current.copy(error = "请选择开始和结束时间。")
            return
        }
        NspaceBookingRules.validate(detail, start, end)?.let {
            _state.value = current.copy(error = it)
            return
        }
        val useType = current.selectedUseType
        if (useType.isNullOrBlank()) {
            _state.value = current.copy(error = "请选择使用类型。")
            return
        }
        val missing = current.fields
            .filter { it.required && current.values[it.field].isNullOrBlank() }
            .map { it.label }
        if (missing.isNotEmpty()) {
            _state.value = current.copy(error = "请填写：${missing.joinToString("、")}")
            return
        }
        val split = NspaceBookingRules.splitValues(current.values)
        val request = NspaceBookingRequest(
            begin = start,
            end = end,
            onDate = date,
            roomId = roomId,
            useType = useType,
            participants = "",
            filePath = "",
            source = "WEB",
            seatNo = "",
            teamId = 0,
            theme = split.theme,
            phone = split.phone,
            outline = split.outline,
            extraField = split.extraField,
        )
        _state.value = current.copy(submitting = true, error = null)
        viewModelScope.launch {
            try {
                service.book(request)
                _state.value = _state.value.copy(
                    submitting = false,
                    success = true,
                )
            } catch (expired: NspaceSessionExpiredException) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = "空间预约会话已失效，请重新登录。",
                )
            } catch (failure: NspaceException) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = "提交失败：${failure.code}；未把请求当作成功。",
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = "提交失败；未把请求当作成功。",
                )
            }
        }
    }

    companion object {
        fun factory(
            service: NspaceService,
            roomId: String,
            date: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NspaceBookingViewModel(service, roomId, date) as T
        }
    }
}
