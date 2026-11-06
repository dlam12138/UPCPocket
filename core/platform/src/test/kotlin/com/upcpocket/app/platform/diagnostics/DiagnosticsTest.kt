package com.upcpocket.app.platform.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
