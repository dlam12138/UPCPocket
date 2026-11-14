package com.upcpocket.app.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TimetableViewModel(
    private val service: TimetableService,
) : ViewModel() {

    val state = service.state
    private var operation: Job? = null
    private var started = false

    /**
     * Pager 预加载只允许建立页面/ViewModel，不允许在这里登录或查询课表。
     * 页面成为稳定活动页后才调用一次；重复进入 tab 不重新 open/refresh。
     */
    fun start() {
        if (started) return
        started = true
        operation = viewModelScope.launch {
            service.open()
            service.refreshToday()
        }
    }

    fun refresh() = run { service.refresh() }

    fun refreshToday() {
        viewModelScope.launch { service.refreshToday() }
    }

    fun selectTerm(term: TimetableTerm) = run { service.selectTerm(term) }

    fun selectWeek(week: Int) = run { service.selectWeek(week) }

    fun beginInteractiveLogin(onReady: (String?) -> Unit) {
        viewModelScope.launch {
            onReady(service.beginInteractiveLogin())
        }
    }

    fun cancelInteractiveLogin() {
        viewModelScope.launch { service.cancelInteractiveLogin() }
    }

    fun adoptInteractiveCookies(
        cookieHeader: String,
        diagnosticOperation: DiagnosticOperation,
        onAccepted: (Boolean) -> Unit,
    ) {
        operation?.cancel()
        operation = viewModelScope.launch {
            val accepted = service.adoptInteractiveCookies(
                cookieHeader = cookieHeader,
                operationId = diagnosticOperation.id,
                diagnosticOperation = diagnosticOperation,
            )
            onAccepted(accepted)
        }
    }

    private fun run(block: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch { block() }
    }
}
