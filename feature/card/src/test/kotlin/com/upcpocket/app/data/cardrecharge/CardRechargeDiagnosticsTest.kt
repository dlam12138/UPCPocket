package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.data.card.CardDiagnosticRecorder
import com.upcpocket.app.data.card.CardDiagnostics
import com.upcpocket.app.data.cardhistory.CardHistoryException
import com.upcpocket.app.data.cardhistory.CardHistoryFailure
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class CardRechargeDiagnosticsTest {
    private val recorder = CardDiagnosticRecorder()

    @Before fun attach() { Diagnostics.install(recorder) }
    @After fun detach() { Diagnostics.install(null) }

    @Test
    fun `service disabled is recorded as a fixed failure without response text`() {
        val canary = "private-response-body-canary"
        CardDiagnostics.begin(DiagnosticDetail.CARD_HISTORY_PAGE, DiagnosticPhase.RESPONSE)
            .fail(CardHistoryException(CardHistoryFailure.MODULE_DISABLED))

        val terminal = recorder.terminals(DiagnosticDetail.CARD_HISTORY_PAGE).single()
        assertEquals(DiagnosticResult.FAIL, terminal.result)
        assertEquals(DiagnosticReason.HTTP, terminal.reason)
        assertFalse(recorder.events.joinToString().contains(canary))
    }
}
