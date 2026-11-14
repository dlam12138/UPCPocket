package com.upcpocket.app.upcauth

import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource
import com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeEvidence
import com.upcpocket.app.platform.diagnostics.DiagnosticChallengeType
import com.upcpocket.app.platform.diagnostics.DiagnosticCredentialState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticNavigationFrame
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticServiceParameter
import com.upcpocket.app.platform.diagnostics.Diagnostics

/** Fixed-schema auth events. Diagnostics remains best effort and cannot fail a business request. */
internal fun recordUpcAuthDiagnostic(
    operation: DiagnosticOperation,
    module: DiagnosticModule,
    action: DiagnosticAction = DiagnosticAction.AUTH_STAGE,
    result: DiagnosticResult,
    phase: DiagnosticPhase,
    detail: DiagnosticDetail? = null,
    reason: DiagnosticReason? = null,
    httpStatus: Int? = null,
    itemCount: Int? = null,
    passwordSubmitted: Int? = null,
    credentialState: DiagnosticCredentialState? = null,
    challengeType: DiagnosticChallengeType? = null,
    challengeEvidence: DiagnosticChallengeEvidence? = null,
    serviceParameter: DiagnosticServiceParameter? = null,
    callbackState: DiagnosticCallbackState? = null,
    navigationFrame: DiagnosticNavigationFrame? = null,
    bindingSource: DiagnosticBindingSource? = null,
) {
    runCatching {
        Diagnostics.record(
            module = module,
            action = action,
            result = result,
            reason = reason,
            httpStatus = httpStatus,
            itemCount = itemCount,
            operation = operation,
            phase = phase,
            detail = detail,
            passwordSubmitted = passwordSubmitted,
            credentialState = credentialState,
            challengeType = challengeType,
            challengeEvidence = challengeEvidence,
            serviceParameter = serviceParameter,
            callbackState = callbackState,
            navigationFrame = navigationFrame,
            bindingSource = bindingSource,
        )
    }
}
