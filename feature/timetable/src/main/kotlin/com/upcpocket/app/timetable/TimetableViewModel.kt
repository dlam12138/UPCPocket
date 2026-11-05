package com.upcpocket.app.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TimetableViewModel(
    private val service: TimetableService,
) : ViewModel() {

    val state = service.state
    private var operation: Job? = null

    init {
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

    fun adoptInteractiveCookies(cookieHeader: String, onAccepted: (Boolean) -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch {
            val accepted = service.adoptInteractiveCookies(cookieHeader)
            if (accepted) service.refresh()
            onAccepted(accepted)
        }
    }

    private fun run(block: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch { block() }
    }
}
