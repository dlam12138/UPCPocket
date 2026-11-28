package com.upcpocket.app.data.card

import com.upcpocket.app.platform.diagnostics.*
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CardDiagnosticsTest {
    @After fun detach() { Diagnostics.install(null) }

    @Test fun `interleaved actions keep distinct operation ids and drop old epoch completions`() {
        val recorder = CardDiagnosticRecorder()
        Diagnostics.install(recorder)
        val first = CardDiagnostics.begin(DiagnosticDetail.CARD_HISTORY_PAGE, DiagnosticPhase.RESPONSE)
        val second = CardDiagnostics.begin(DiagnosticDetail.CARD_HISTORY_SUMMARY, DiagnosticPhase.RESPONSE)
        second.phase = DiagnosticPhase.PARSE
        second.finish()
        val secondEvents = recorder.forDetail(DiagnosticDetail.CARD_HISTORY_SUMMARY)
        assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.OK), secondEvents.map { it.result })
        assertEquals(1, secondEvents.map { it.operationId }.distinct().size)
        assertNotEquals(recorder.forDetail(DiagnosticDetail.CARD_HISTORY_PAGE).single().operationId, secondEvents.first().operationId)
        recorder.epoch++
        first.finish()
        assertEquals(listOf(DiagnosticResult.PENDING), recorder.forDetail(DiagnosticDetail.CARD_HISTORY_PAGE).map { it.result })
        CardDiagnostics.begin(DiagnosticDetail.CARD_HISTORY_PAGE, DiagnosticPhase.RESPONSE).finish()
        assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.PENDING, DiagnosticResult.OK),
            recorder.forDetail(DiagnosticDetail.CARD_HISTORY_PAGE).map { it.result })
    }

    @Test fun `diagnostic capture and enqueue failures never escape or create unbound records`() {
        var unbound = 0
        Diagnostics.install(object : EpochBoundDiagnosticSink {
            override fun captureEpoch(): Long = error("synthetic capture failure")
            override fun record(event: DiagnosticEvent) { unbound++ }
            override fun record(event: DiagnosticEvent, expectedEpoch: Long?) { unbound++ }
        })
        CardDiagnostics.begin(DiagnosticDetail.CARD_RECHARGE_PREPARE, DiagnosticPhase.BUSINESS_SESSION).finish()
        assertEquals(0, unbound)
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) = error("synthetic enqueue failure")
        })
        CardDiagnostics.begin(DiagnosticDetail.CARD_ORDER_CREATE, DiagnosticPhase.RESPONSE)
            .fail(IOException("synthetic response canary"), uncertain = true)
    }

    @Test fun `unknown business codes and exception accessors cannot export their text`() {
        val recorder = CardDiagnosticRecorder()
        Diagnostics.install(recorder)
        val canary = "synthetic-account-url-ip-order-canary"
        CardDiagnostics.begin(DiagnosticDetail.CARD_ORDER_CREATE, DiagnosticPhase.RESPONSE)
            .fail(IOException(canary), uncertain = true)
        val opaque = object : IOException() {
            override val message: String get() = error("message must not be inspected")
            override val cause: Throwable get() = error("cause must not be inspected")
        }
        CardDiagnostics.begin(DiagnosticDetail.CARD_HISTORY_PAGE, DiagnosticPhase.PARSE).fail(opaque)
        assertEquals(DiagnosticReason.NETWORK, recorder.terminals(DiagnosticDetail.CARD_ORDER_CREATE).single().reason)
        assertEquals(DiagnosticResult.UNKNOWN, recorder.terminals(DiagnosticDetail.CARD_ORDER_CREATE).single().result)
        assertEquals(DiagnosticReason.SCHEMA, recorder.terminals(DiagnosticDetail.CARD_HISTORY_PAGE).single().reason)
        assertFalse(recorder.events.joinToString().contains(canary))
        assertTrue(recorder.events.all { event -> event.durationMs?.let { it in 0..DiagnosticEvent.MAX_DURATION_MS } == true })
    }
}
