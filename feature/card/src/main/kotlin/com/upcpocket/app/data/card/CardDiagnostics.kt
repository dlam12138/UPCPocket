package com.upcpocket.app.data.card

import com.upcpocket.app.data.cardhistory.CardHistoryException
import com.upcpocket.app.data.cardhistory.CardHistoryFailure
import com.upcpocket.app.data.cardrecharge.RechargeFailure
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportSnapshot
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.io.IOException
import java.util.concurrent.CancellationException

/** One local business action, never a stored order identifier or an authentication operation. */
internal class CardDiagnostics private constructor(
    private val operation: DiagnosticOperation?,
    private val detail: DiagnosticDetail,
    var phase: DiagnosticPhase,
) {
    private val startedNanos = System.nanoTime()
    private var finished = false
    private var transportSnapshot: DiagnosticTransportSnapshot? = null

    fun transport(snapshot: DiagnosticTransportSnapshot) {
        transportSnapshot = snapshot
    }

    fun currentTransport(): DiagnosticTransportSnapshot? = transportSnapshot

    fun clearTransport() {
        transportSnapshot = null
    }

    fun start(phase: DiagnosticPhase = this.phase) {
        this.phase = phase
        mark(detail, DiagnosticResult.PENDING)
    }

    fun mark(
        detail: DiagnosticDetail,
        result: DiagnosticResult,
        phase: DiagnosticPhase = this.phase,
        reason: DiagnosticReason? = null,
    ) {
        runCatching {
            // If beginning an operation failed, do not bypass its epoch with an unbound event.
            val active = operation ?: return
            Diagnostics.record(
                module = DiagnosticModule.CARD,
                action = DiagnosticAction.OPERATION_STAGE,
                result = result,
                reason = reason,
                phase = phase,
                detail = detail,
                operation = active,
                durationMs = ((System.nanoTime() - startedNanos).coerceAtLeast(0) / 1_000_000)
                    .coerceAtMost(DiagnosticEvent.MAX_DURATION_MS),
                transport = transportSnapshot.takeIf { result != DiagnosticResult.PENDING },
            )
        }
    }

    fun finish(result: DiagnosticResult = DiagnosticResult.OK, reason: DiagnosticReason? = null) {
        if (finished) return
        finished = true
        mark(detail, result, reason = reason)
    }

    fun fail(error: Throwable, uncertain: Boolean = false) {
        val failure = classify(error, phase)
        finish(if (uncertain) DiagnosticResult.UNKNOWN else failure.first, failure.second)
    }

    fun failedStep(detail: DiagnosticDetail, error: Throwable) {
        val failure = classify(error, phase)
        mark(detail, failure.first, reason = failure.second)
    }

    companion object {
        fun begin(detail: DiagnosticDetail, phase: DiagnosticPhase, pending: Boolean = true): CardDiagnostics =
            CardDiagnostics(runCatching { Diagnostics.beginOperation() }.getOrNull(), detail, phase)
                .also { if (pending) it.start() }

        /** Closed mappings only. Business codes and exception text never become diagnostic values. */
        private fun classify(error: Throwable, phase: DiagnosticPhase): Pair<DiagnosticResult, DiagnosticReason?> {
            val reason = when (error) {
                is CardHistoryException -> when (error.reason) {
                    CardHistoryFailure.ACCOUNT_UNAVAILABLE, CardHistoryFailure.ACCOUNT_CHANGED -> DiagnosticReason.ACCOUNT
                    CardHistoryFailure.LOGIN_REQUIRED, CardHistoryFailure.AUTHENTICATION,
                    CardHistoryFailure.INVALID_CREDENTIALS -> DiagnosticReason.AUTH
                    CardHistoryFailure.INTERACTION_REQUIRED -> DiagnosticReason.INTERACTION
                    CardHistoryFailure.ACCESS_DENIED -> DiagnosticReason.POLICY
                    CardHistoryFailure.NETWORK -> DiagnosticReason.NETWORK
                    CardHistoryFailure.PROTOCOL -> DiagnosticReason.SCHEMA
                    CardHistoryFailure.UPSTREAM -> DiagnosticReason.HTTP
                }
                is RechargeFailure -> when (error.stage) {
                    "P-ACCOUNT" -> DiagnosticReason.ACCOUNT
                    "P-SESSION", "P-LOGIN" -> DiagnosticReason.AUTH
                    "P-NETWORK" -> DiagnosticReason.NETWORK
                    "P-HTTP" -> DiagnosticReason.HTTP
                    "P-PENDING-STORE" -> DiagnosticReason.STORAGE
                    "P-PENDING", "P-AMOUNT", "P-CARD-BLOCKED", "P-CARD-OWNER", "P-CARD-TYPE",
                    "P-ORDER-OWNER", "P-ORIGIN", "P-WX-ORIGIN", "P-WX-URI", "P-WX-CALLBACK" -> DiagnosticReason.POLICY
                    "P-SIZE", "P-JSON", "P-RESULT", "P-DATA", "P-URL", "P-API-REDIRECT",
                    "P-CONFIG", "P-CARDS", "P-CARD-ACCOUNT", "P-FEE-CONFIG", "P-SIGN-DOWNLOAD",
                    "P-SIGN-FORMAT", "P-ORDER-REDIRECT", "P-ORDER-INFO", "P-PAY-METHOD",
                    "P-PAY-RESPONSE", "P-WX-REDIRECT", "P-WX-FORM", "P-WX-SCRIPT", "P-STATUS" -> DiagnosticReason.SCHEMA
                    "P-ORDER-UNKNOWN" -> DiagnosticReason.UNKNOWN
                    else -> DiagnosticReason.UNKNOWN
                }
                is SecurityException -> DiagnosticReason.POLICY
                is IOException -> if (phase in setOf(DiagnosticPhase.PARSE, DiagnosticPhase.PERSONAL_VERIFY)) {
                    DiagnosticReason.SCHEMA
                } else DiagnosticReason.NETWORK
                else -> DiagnosticReason.UNKNOWN
            }
            val result = when {
                error is CancellationException -> DiagnosticResult.CANCELLED
                reason in setOf(DiagnosticReason.ACCOUNT, DiagnosticReason.AUTH, DiagnosticReason.INTERACTION,
                    DiagnosticReason.POLICY, DiagnosticReason.STORAGE) -> DiagnosticResult.BLOCKED
                else -> DiagnosticResult.FAIL
            }
            return result to reason
        }
    }
}
