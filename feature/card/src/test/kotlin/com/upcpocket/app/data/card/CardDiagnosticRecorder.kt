package com.upcpocket.app.data.card

import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.EpochBoundDiagnosticSink
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic tests only; deliberately implements the same epoch gate as the host store. */
internal class CardDiagnosticRecorder : EpochBoundDiagnosticSink {
    val events = CopyOnWriteArrayList<DiagnosticEvent>()
    @Volatile var epoch = 1L
    override fun captureEpoch() = epoch
    override fun record(event: DiagnosticEvent) { events += event }
    override fun record(event: DiagnosticEvent, expectedEpoch: Long?) {
        if (expectedEpoch == epoch) events += event
    }
    fun forDetail(detail: DiagnosticDetail) = events.filter {
        it.action == DiagnosticAction.OPERATION_STAGE && it.detail == detail
    }
    fun terminals(detail: DiagnosticDetail) = forDetail(detail).filter { it.result != DiagnosticResult.PENDING }
}
