package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation

/** Narrow host bridge for the card feature's manual, in-memory school login WebView. */
interface CardHistoryInteractiveAuthenticator {
    val entryUrl: String

    fun inspectNavigation(url: String, method: String, isMainFrame: Boolean): CardHistoryWebNavigation

    fun inspectNavigation(
        url: String,
        method: String,
        isMainFrame: Boolean,
        operation: DiagnosticOperation?,
    ): CardHistoryWebNavigation = inspectNavigation(url, method, isMainFrame)

    /** Returns an unpersisted token only after validating it against the current saved credential. */
    suspend fun completeResult(identity: SchoolStudentIdentity, resultUrl: String): String

    suspend fun completeResult(
        identity: SchoolStudentIdentity,
        resultUrl: String,
        operation: DiagnosticOperation?,
    ): String = completeResult(identity, resultUrl)
}

sealed class CardHistoryWebNavigation {
    data object Allow : CardHistoryWebNavigation()
    data object Block : CardHistoryWebNavigation()

    class Upgrade(val url: String) : CardHistoryWebNavigation() {
        override fun toString() = "CardHistoryWebNavigation.Upgrade([REDACTED])"
    }

    class Complete(val url: String) : CardHistoryWebNavigation() {
        override fun toString() = "CardHistoryWebNavigation.Complete([REDACTED])"
    }
}

/** Attempt ids are process-local only; account identity and result URLs stay inside the client. */
class CardHistoryInteractiveAttempt internal constructor(
    val id: Long,
    val entryUrl: String,
) {
    override fun toString() = "CardHistoryInteractiveAttempt(id=$id, entryUrl=[REDACTED])"
}

/** UI-owned WebView lifetime; account invalidation awaits this before it clears shared cookies. */
interface CardHistoryWebViewLease {
    fun dispose()

    suspend fun disposeAndAwait(): Boolean
}
