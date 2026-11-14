package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource
import com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticNavigationFrame
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.io.IOException
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Implemented by the host's restricted auth adapter, never by a password-reading feature. */
fun interface CardHistoryAuthenticator {
    suspend fun authenticate(identity: SchoolStudentIdentity): String
}

private fun recordCardInteractiveDiagnostic(
    operation: DiagnosticOperation,
    action: DiagnosticAction,
    result: DiagnosticResult,
    phase: DiagnosticPhase,
    detail: DiagnosticDetail,
    reason: DiagnosticReason? = null,
    callbackState: DiagnosticCallbackState? = null,
    navigationFrame: DiagnosticNavigationFrame? = null,
    bindingSource: DiagnosticBindingSource? = null,
) {
    runCatching {
        Diagnostics.record(
            module = DiagnosticModule.CARD,
            action = action,
            result = result,
            reason = reason,
            phase = phase,
            detail = detail,
            operation = operation,
            callbackState = callbackState,
            navigationFrame = navigationFrame,
            bindingSource = bindingSource,
        )
    }
}

enum class CardHistoryFailure {
    ACCOUNT_UNAVAILABLE, ACCOUNT_CHANGED, LOGIN_REQUIRED, NETWORK, PROTOCOL, AUTHENTICATION,
    INTERACTION_REQUIRED, INVALID_CREDENTIALS, ACCESS_DENIED, UPSTREAM,
}
class CardHistoryException(val reason: CardHistoryFailure) : IOException(reason.name)
data class CardHistorySummary(val incomeFen: Long, val expenseFen: Long)
data class BoundCardHistoryPage(val owner: SchoolStudentIdentity, val month: YearMonth?, val current: Int, val data: CardHistoryPage)

/** Only fixed HTTPS, read-only endpoints; a session is checked against the current account generation. */
class CardHistoryClient(
    private val account: SchoolAccountStudentIdentityReader,
    private val authenticator: CardHistoryAuthenticator,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val interactiveAuthenticator: CardHistoryInteractiveAuthenticator? = null,
) {
    private class Session(val owner: SchoolStudentIdentity, val token: String)
    private class InteractiveState(
        val id: Long,
        val owner: SchoolStudentIdentity,
        val epoch: Long,
        val operation: DiagnosticOperation,
    ) {
        var consumed = false
        var cancelled = false
    }
    private class ActiveWebView(val attemptId: Long, val lease: CardHistoryWebViewLease)

    @Volatile private var session: Session? = null
    private val epoch = AtomicLong()
    private val stateLock = Any()
    private val attemptIds = AtomicLong()
    private var interactiveState: InteractiveState? = null
    private var activeWebView: ActiveWebView? = null
    val studentIdentity get() = account.studentIdentity
    val accountState get() = account.state
    val supportsInteractiveLogin get() = interactiveAuthenticator != null
    fun hasSession(): Boolean = session?.let { isCurrent(it.owner) } == true

    fun clearSession() {
        invalidateState()?.dispose()
    }

    /** Account invalidation is a barrier: the active browser is destroyed before cookie cleanup. */
    suspend fun invalidateForAccountChange(): Boolean {
        val lease = invalidateState() ?: return true
        val stopped = runCatching { lease.disposeAndAwait() }.getOrDefault(false)
        if (stopped) synchronized(stateLock) {
            if (activeWebView?.lease === lease) activeWebView = null
        }
        return stopped
    }

    private fun invalidateState(): CardHistoryWebViewLease? = synchronized(stateLock) {
        epoch.incrementAndGet()
        session = null
        interactiveState?.cancelled = true
        interactiveState = null
        activeWebView?.lease
    }

    fun beginInteractiveLogin(): CardHistoryInteractiveAttempt {
        val bridge = interactiveAuthenticator
            ?: throw CardHistoryException(CardHistoryFailure.INTERACTION_REQUIRED)
        val owner = identity()
        val generation = epoch.get()
        val entryUrl = bridge.entryUrl
        val operation = Diagnostics.beginOperation()
        recordCardInteractiveDiagnostic(
            operation, DiagnosticAction.AUTH_START, DiagnosticResult.PENDING,
            DiagnosticPhase.WEBVIEW_NAVIGATION, DiagnosticDetail.AUTH_REQUIRED,
            bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
        )
        if (bridge.inspectNavigation(entryUrl, "GET", true, operation) != CardHistoryWebNavigation.Allow) {
            recordCardInteractiveDiagnostic(
                operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.BLOCKED,
                DiagnosticPhase.WEBVIEW_NAVIGATION, DiagnosticDetail.WEBVIEW_NAVIGATION_BLOCKED,
                reason = DiagnosticReason.POLICY,
                navigationFrame = com.upcpocket.app.platform.diagnostics.DiagnosticNavigationFrame.MAIN,
                bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
            )
            throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        }
        val previousLease = synchronized(stateLock) { activeWebView?.lease }
        previousLease?.dispose()
        val id = attemptIds.incrementAndGet()
        val next = InteractiveState(id, owner, generation, operation)
        synchronized(stateLock) {
            if (activeWebView != null) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
            if (epoch.get() != generation || !isCurrent(owner)) {
                throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
            }
            interactiveState?.cancelled = true
            interactiveState = next
        }
        return CardHistoryInteractiveAttempt(id, entryUrl)
    }

    fun inspectInteractiveNavigation(url: String, method: String, isMainFrame: Boolean): CardHistoryWebNavigation {
        val operation = synchronized(stateLock) { interactiveState?.operation }
        return interactiveAuthenticator?.inspectNavigation(url, method, isMainFrame, operation)
            ?: CardHistoryWebNavigation.Block
    }

    fun isInteractiveLoginCurrent(attemptId: Long): Boolean = synchronized(stateLock) {
        interactiveState?.let { it.id == attemptId && !it.cancelled } == true
    }

    /** The returned handle unregisters only; the client owns immediate destruction on invalidation. */
    fun registerInteractiveWebView(attemptId: Long, lease: CardHistoryWebViewLease): AutoCloseable? {
        val accepted = synchronized(stateLock) {
            if (interactiveState?.id != attemptId || interactiveState?.cancelled == true || activeWebView != null) {
                false
            } else {
                activeWebView = ActiveWebView(attemptId, lease)
                true
            }
        }
        if (!accepted) {
            lease.dispose()
            return null
        }
        return AutoCloseable {
            synchronized(stateLock) {
                if (activeWebView?.attemptId == attemptId && activeWebView?.lease === lease) {
                    activeWebView = null
                }
            }
        }
    }

    fun cancelInteractiveLogin(attemptId: Long) {
        synchronized(stateLock) {
            val active = interactiveState
            if (active?.id != attemptId) return
            active.cancelled = true
            interactiveState = null
        }
        synchronized(stateLock) { activeWebView?.takeIf { it.attemptId == attemptId }?.lease }?.dispose()
    }

    /** Captured results are single-use, then go through the same identity check and session commit as native login. */
    suspend fun completeInteractiveLogin(attemptId: Long, resultUrl: String): Unit = withContext(Dispatchers.IO) {
        val bridge = interactiveAuthenticator
            ?: throw CardHistoryException(CardHistoryFailure.INTERACTION_REQUIRED)
        val initial = synchronized(stateLock) {
            interactiveState?.takeIf { it.id == attemptId && !it.cancelled && !it.consumed }
                ?: throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
        }
        val decision = bridge.inspectNavigation(resultUrl, "GET", true, initial.operation)
        if (decision !is CardHistoryWebNavigation.Complete || decision.url != resultUrl) {
            recordCardInteractiveDiagnostic(
                initial.operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL,
                DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_REJECTED,
                reason = DiagnosticReason.POLICY, callbackState = DiagnosticCallbackState.REJECTED,
                bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
            )
            throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        }
        val state = synchronized(stateLock) {
            val active = interactiveState
                ?: throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
            if (active !== initial || active.id != attemptId || active.cancelled || active.consumed) {
                recordCardInteractiveDiagnostic(
                    initial.operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.BLOCKED,
                    DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_REJECTED,
                    reason = DiagnosticReason.STALE, callbackState = DiagnosticCallbackState.STALE,
                    bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
                )
                throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
            }
            active.consumed = true
            active
        }
        recordCardInteractiveDiagnostic(
            state.operation, DiagnosticAction.AUTH_STAGE, DiagnosticResult.OK,
            DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_CONSUMED,
            callbackState = DiagnosticCallbackState.CONSUMED,
            bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
        )
        checkInteractiveCurrent(state)
        val token = bridge.completeResult(state.owner, resultUrl, state.operation)
        checkInteractiveCurrent(state)
        verifyAndCommit(state.owner, state.epoch, token, state)
    }

    private fun identity(): SchoolStudentIdentity = account.studentIdentity.value
        ?.takeUnless { account.state.value.blocksPersonalSessions }
        ?: throw CardHistoryException(CardHistoryFailure.ACCOUNT_UNAVAILABLE)
    fun isCurrent(owner: SchoolStudentIdentity): Boolean =
        !account.state.value.blocksPersonalSessions && account.studentIdentity.value == owner

    suspend fun login(): Unit = withContext(Dispatchers.IO) {
        val owner = identity()
        val generation = epoch.get()
        val token = authenticator.authenticate(owner)
        verifyAndCommit(owner, generation, token)
    }

    private suspend fun verifyAndCommit(
        owner: SchoolStudentIdentity,
        generation: Long,
        token: String,
        interactive: InteractiveState? = null,
    ) {
        val candidate = Session(owner, token)
        CardHistoryProtocol.verifyIdentity(get("/berserker-base/user?synAccessSource=h5", candidate), owner.studentId)
        synchronized(stateLock) {
            if (!isCurrent(owner) || epoch.get() != generation ||
                (interactive != null && (interactiveState !== interactive || interactive.cancelled))
            ) {
                throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
            }
            session = candidate
            if (interactive != null) interactiveState = null
        }
    }

    private fun checkInteractiveCurrent(state: InteractiveState) {
        val valid = synchronized(stateLock) {
            interactiveState === state && !state.cancelled && epoch.get() == state.epoch
        }
        if (!valid || !isCurrent(state.owner)) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
    }

    suspend fun page(month: YearMonth? = null, current: Int = 1, size: Int = 50): BoundCardHistoryPage = withContext(Dispatchers.IO) {
        val active = requireSession()
        val generation = epoch.get()
        val body = get("/berserker-search/search/personal/turnover?" + query(month, current, size), active)
        val actual = (CardHistoryProtocol.data(body)["current"] as? JsonPrimitive)?.intOrNull
        if (actual != null && actual != current) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        val parsed = CardHistoryProtocol.parseRows(body)
        checkCurrent(active, generation)
        BoundCardHistoryPage(active.owner, month, current, parsed)
    }

    /** Explicit user action only. Failure never returns a partial list as a complete history. */
    suspend fun all(month: YearMonth? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): BoundCardHistoryPage {
        val owner = requireSession().owner
        val generation = epoch.get()
        val records = linkedMapOf<String, CardHistoryRow>()
        var current = 1
        do {
            val result = page(month, current)
            if (result.owner != owner || epoch.get() != generation) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
            val previous = records.size
            result.data.rows.forEach { records[it.id] = it }
            if (current > 1 && records.size == previous) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
            onProgress(records.size, result.data.total)
            if (current >= result.data.pages) {
                if (!isCurrent(owner) || epoch.get() != generation) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
                if (records.size != result.data.total) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
                return result.copy(data = result.data.copy(rows = records.values.toList()))
            }
            if (result.data.rows.isEmpty()) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
            current++
            delay(250)
        } while (true)
    }

    suspend fun summary(month: YearMonth? = null): CardHistorySummary = withContext(Dispatchers.IO) {
        val active = requireSession()
        val generation = epoch.get()
        val data = CardHistoryProtocol.data(get("/berserker-search/statistics/turnover/count?" + query(month, 1, 50), active))
        fun amount(key: String): Long = CardHistoryProtocol.fen(data[key])?.takeIf { it >= 0 }
            ?: throw CardHistoryException(CardHistoryFailure.PROTOCOL)
        val result = CardHistorySummary(amount("income"), amount("expenses"))
        checkCurrent(active, generation)
        result
    }

    private fun requireSession(): Session {
        val active = session ?: throw CardHistoryException(CardHistoryFailure.LOGIN_REQUIRED)
        if (!isCurrent(active.owner)) { clearSession(); throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED) }
        return active
    }
    private fun checkCurrent(active: Session, generation: Long) {
        if (!isCurrent(active.owner) || epoch.get() != generation) throw CardHistoryException(CardHistoryFailure.ACCOUNT_CHANGED)
    }
    private fun get(path: String, active: Session): String {
        val token = active.token
        if (token.isBlank() || token.length > 16384 || token.any { it <= ' ' || it == '\u007f' })
            throw CardHistoryException(CardHistoryFailure.AUTHENTICATION)
        val request = Request.Builder().url(CardHistoryProtocol.BASE + path)
            .header("Accept", "application/json").header("synAccessSource", "h5")
            .header("synjones-auth", "bearer $token").build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    if (session === active) clearSession()
                    throw CardHistoryException(CardHistoryFailure.LOGIN_REQUIRED)
                }
                if (response.code != 200) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
                val body = response.body ?: throw CardHistoryException(CardHistoryFailure.PROTOCOL)
                val output = java.io.ByteArrayOutputStream()
                body.byteStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 2 * 1024 * 1024) throw CardHistoryException(CardHistoryFailure.PROTOCOL)
                        output.write(buffer, 0, count)
                    }
                }
                val text = output.toString("UTF-8")
                val code = runCatching {
                    (Json.parseToJsonElement(text).jsonObject["code"] as? JsonPrimitive)?.intOrNull
                }.getOrNull()
                if (code == 401 || code == 403) {
                    if (session === active) clearSession()
                    throw CardHistoryException(CardHistoryFailure.LOGIN_REQUIRED)
                }
                return text
            }
        } catch (known: CardHistoryException) { throw known }
        catch (_: IOException) { throw CardHistoryException(CardHistoryFailure.NETWORK) }
    }

    companion object {
        internal fun query(month: YearMonth?, current: Int, size: Int): String {
            require(current > 0 && size in 1..100)
            val fields = linkedMapOf("size" to "$size", "current" to "$current", "synAccessSource" to "h5")
            if (month != null) { fields["timeFrom"] = month.atDay(1).toString(); fields["timeTo"] = month.atEndOfMonth().toString() }
            return fields.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        }
    }
}
