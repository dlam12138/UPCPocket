package com.upcpocket.app.platform.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticsTest {

    @After
    fun tearDown() {
        Diagnostics.install(null)
    }

    @Test
    fun fixedEventAcceptsOnlyBoundedNumericFields() {
        val event = DiagnosticEvent(
            module = DiagnosticModule.TIMETABLE,
            action = DiagnosticAction.WEBVIEW_VIEWPORT,
            result = DiagnosticResult.OK,
            reason = DiagnosticReason.VIEWPORT,
            destination = DiagnosticDestination.CAS_LOGIN,
            httpStatus = 200,
            durationMs = 120,
            widthDp = 412,
            heightDp = 839,
            operationId = "a1b2c3d4",
        )
        assertEquals(412, event.widthDp)
        assertEquals(200, event.httpStatus)
    }

    @Test
    fun fixedPhaseAndDetailAreBoundedEnums() {
        val event = DiagnosticEvent(
            module = DiagnosticModule.TIMETABLE,
            action = DiagnosticAction.QUERY_FAIL,
            result = DiagnosticResult.FAIL,
            phase = DiagnosticPhase.PARSE,
            detail = DiagnosticDetail.WEEK_PARSE_ORDER,
            responseBytes = 2048,
            itemCount = 7,
        )
        assertEquals(DiagnosticPhase.PARSE, event.phase)
        assertEquals(DiagnosticDetail.WEEK_PARSE_ORDER, event.detail)
        assertEquals(2048, event.responseBytes)
        assertEquals(7, event.itemCount)
        assertFalse(
            runCatching {
                DiagnosticEvent(
                    DiagnosticModule.TIMETABLE,
                    DiagnosticAction.QUERY_FAIL,
                    DiagnosticResult.FAIL,
                    responseBytes = -1,
                )
            }.isSuccess,
        )
        assertFalse(
            runCatching {
                DiagnosticEvent(
                    DiagnosticModule.TIMETABLE,
                    DiagnosticAction.QUERY_FAIL,
                    DiagnosticResult.FAIL,
                    itemCount = DiagnosticEvent.MAX_ITEM_COUNT + 1,
                )
            }.isSuccess,
        )
    }

    @Test
    fun rejectsInvalidNumericRangesAndOperationIds() {
        assertFalse(
            runCatching {
                DiagnosticEvent(
                    DiagnosticModule.TIMETABLE,
                    DiagnosticAction.WEBVIEW_VIEWPORT,
                    DiagnosticResult.OK,
                    httpStatus = 99,
                )
            }.isSuccess,
        )
        assertFalse(
            runCatching {
                DiagnosticEvent(
                    DiagnosticModule.TIMETABLE,
                    DiagnosticAction.WEBVIEW_VIEWPORT,
                    DiagnosticResult.OK,
                    durationMs = -1,
                )
            }.isSuccess,
        )
        assertFalse(
            runCatching {
                DiagnosticEvent(
                    DiagnosticModule.TIMETABLE,
                    DiagnosticAction.WEBVIEW_VIEWPORT,
                    DiagnosticResult.OK,
                    operationId = "not-hex!",
                )
            }.isSuccess,
        )
    }

    @Test
    fun rejectsFreeTextFramesAndTooManyFrames() {
        assertFalse(
            runCatching {
                DiagnosticEvent(
                    DiagnosticModule.TIMETABLE,
                    DiagnosticAction.WEBVIEW_MAIN_FRAME_ERROR,
                    DiagnosticResult.FAIL,
                    frames = listOf("password=PASSWORD_canary\nnext"),
                )
            }.isSuccess,
        )
        assertFalse(
            runCatching {
                DiagnosticEvent(
                    DiagnosticModule.TIMETABLE,
                    DiagnosticAction.WEBVIEW_MAIN_FRAME_ERROR,
                    DiagnosticResult.FAIL,
                    frames = List(DiagnosticEvent.MAX_FRAMES + 1) {
                        "com.upcpocket.app.timetable.TimetableService.open"
                    },
                )
            }.isSuccess,
        )
    }

    @Test
    fun exceptionClassificationKeepsOnlyBoundedAppFramesAndNeverMessage() {
        val failure = RuntimeException("PASSWORD_canary https://example.test/?token=secret")
        failure.stackTrace = arrayOf(
            StackTraceElement("com.upcpocket.app.timetable.TimetableService", "open", "TimetableService.kt", 42),
            StackTraceElement("java.net.Socket", "read", "Socket.java", 1),
            StackTraceElement("com.upcpocket.app.timetable.TimetableService", "refresh", "TimetableService.kt", 77),
        )
        assertEquals(DiagnosticExceptionType.OTHER, Diagnostics.classify(failure))
        assertEquals(
            listOf(
                "com.upcpocket.app.timetable.TimetableService.open",
                "com.upcpocket.app.timetable.TimetableService.refresh",
            ),
            Diagnostics.appFrames(failure),
        )
        assertFalse(Diagnostics.appFrames(failure).joinToString().contains("PASSWORD"))
    }

    @Test
    fun recordDropsInvalidEventsAndNeverThrows() {
        val seen = mutableListOf<DiagnosticEvent>()
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) {
                seen += event
            }
        })
        assertFalse(
            Diagnostics.record(
                module = DiagnosticModule.TIMETABLE,
                action = DiagnosticAction.WEBVIEW_VIEWPORT,
                result = DiagnosticResult.OK,
                httpStatus = 999,
            ),
        )
        assertTrue(seen.isEmpty())
        assertTrue(
            Diagnostics.record(
                module = DiagnosticModule.TIMETABLE,
                action = DiagnosticAction.WEBVIEW_VIEWPORT,
                result = DiagnosticResult.OK,
                widthDp = 412,
                heightDp = 839,
            ),
        )
        assertEquals(1, seen.size)
    }

    @Test
    fun noSinkIsANoop() {
        Diagnostics.install(null)
        assertFalse(
            Diagnostics.record(
                module = DiagnosticModule.APP,
                action = DiagnosticAction.WIDGET_LAYOUT,
                result = DiagnosticResult.OK,
            ),
        )
    }

    @Test
    fun schemaFiveTransportAcceptsUnknownIntervalsAndRejectsEveryNumericBoundary() {
        val snapshot = DiagnosticTransportSnapshot(DiagnosticTransportStage.CALL_START, 0)
        assertEquals(5, DiagnosticEvent.SCHEMA_VERSION)
        assertNull(snapshot.dnsMs)
        assertNull(snapshot.dnsIpv4Count)
        assertNull(snapshot.dnsIpv6Count)
        assertNull(snapshot.connectionReused)
        assertNull(DiagnosticEvent(DiagnosticModule.CARD, DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK).transport)
        for (invalid in listOf(-1L, DiagnosticTransportSnapshot.MAX_DURATION_MS + 1)) {
            val replacements: List<() -> DiagnosticTransportSnapshot> = listOf(
                { snapshot.copy(totalMs = invalid) },
                { snapshot.copy(dnsMs = invalid) },
                { snapshot.copy(connectMs = invalid) },
                { snapshot.copy(tlsMs = invalid) },
                { snapshot.copy(headersMs = invalid) },
                { snapshot.copy(bodyMs = invalid) },
            )
            replacements.forEach { make -> assertThrows(IllegalArgumentException::class.java) { make() } }
        }
        for (invalid in listOf(-1, DiagnosticTransportSnapshot.MAX_COUNT + 1)) {
            val replacements: List<() -> DiagnosticTransportSnapshot> = listOf(
                { snapshot.copy(dnsQueryCount = invalid) },
                { snapshot.copy(dnsIpv4Count = invalid) },
                { snapshot.copy(dnsIpv6Count = invalid) },
                { snapshot.copy(connectAttemptCount = invalid) },
                { snapshot.copy(tlsAttemptCount = invalid) },
                { snapshot.copy(requestHeadersCount = invalid) },
            )
            replacements.forEach { make -> assertThrows(IllegalArgumentException::class.java) { make() } }
        }
        for (invalid in listOf(-1, 2)) {
            assertThrows(IllegalArgumentException::class.java) { snapshot.copy(connectionReused = invalid) }
        }
        assertEquals(600_000L, snapshot.copy(totalMs = 600_000L, dnsMs = 600_000L,
            connectMs = 600_000L, tlsMs = 600_000L, headersMs = 600_000L, bodyMs = 600_000L,
            dnsQueryCount = 64, dnsIpv4Count = 64, dnsIpv6Count = 64,
            connectAttemptCount = 64, tlsAttemptCount = 64, requestHeadersCount = 64,
            connectionReused = 1).totalMs)
    }

    @Test
    fun transportFacadePreservesTheImmutableSnapshotAndSinkFailuresAreDropped() {
        val snapshot = DiagnosticTransportSnapshot(DiagnosticTransportStage.DNS, 12,
            error = DiagnosticTransportError.DNS, dnsQueryCount = 1, dnsMs = 12)
        var recorded: DiagnosticEvent? = null
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) { recorded = event }
        })
        assertTrue(Diagnostics.record(DiagnosticModule.CARD, DiagnosticAction.AUTH_STOP,
            DiagnosticResult.FAIL, transport = snapshot))
        assertSame(snapshot, recorded?.transport)
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) { error("synthetic secret sink canary") }
        })
        assertFalse(Diagnostics.record(DiagnosticModule.CARD, DiagnosticAction.AUTH_STOP,
            DiagnosticResult.FAIL, transport = snapshot))
    }

    @Test
    fun failedEpochCaptureDisablesTheWholeOperationWithoutAnUnboundFallback() {
        var writes = 0
        Diagnostics.install(object : EpochBoundDiagnosticSink {
            override fun captureEpoch(): Long = error("synthetic secret epoch canary")
            override fun record(event: DiagnosticEvent) { writes++ }
            override fun record(event: DiagnosticEvent, expectedEpoch: Long?) { writes++ }
        })
        val operation = Diagnostics.beginOperation()
        assertTrue(operation.id.matches(Regex("[0-9a-f]{8}")))
        assertFalse(Diagnostics.record(DiagnosticModule.CARD, DiagnosticAction.AUTH_STOP,
            DiagnosticResult.FAIL, operation = operation))
        assertEquals(0, writes)
        // Restoring a healthy sink does not revive a work item whose capture failed.
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) { writes++ }
        })
        assertFalse(Diagnostics.record(DiagnosticModule.CARD, DiagnosticAction.AUTH_STOP,
            DiagnosticResult.FAIL, operation = operation))
        assertEquals(0, writes)
    }

    @Test
    fun transportRecordKeepsTheCapturedEpochAcrossAClear() {
        var currentEpoch = 1L
        val writes = mutableListOf<DiagnosticEvent>()
        Diagnostics.install(object : EpochBoundDiagnosticSink {
            override fun captureEpoch(): Long = currentEpoch
            override fun record(event: DiagnosticEvent) = error("must use epoch-bound overload")
            override fun record(event: DiagnosticEvent, expectedEpoch: Long?) {
                if (expectedEpoch == currentEpoch) writes += event
            }
        })
        val previous = Diagnostics.beginOperation()
        currentEpoch = 2L
        val snapshot = DiagnosticTransportSnapshot(DiagnosticTransportStage.RESPONSE_BODY, 30)
        Diagnostics.record(DiagnosticModule.CARD, DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK,
            operation = previous, transport = snapshot)
        assertTrue(writes.isEmpty())
        val current = Diagnostics.beginOperation()
        Diagnostics.record(DiagnosticModule.CARD, DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK,
            operation = current, transport = snapshot)
        assertEquals(1, writes.size)
        assertEquals(current.id, writes.single().operationId)
    }
}
