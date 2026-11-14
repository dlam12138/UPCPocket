package com.upcpocket.app.shiguang

import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.upcauth.ShiguangAuthenticator
import com.upcpocket.app.upcauth.ShiguangInteractiveAuthenticator
import com.upcpocket.app.upcauth.ShiguangWebRequestKind
import com.upcpocket.app.upcauth.UpcShiguangWebNavigation
import com.upcpocket.app.upcauth.UpcShiguangAuthFailure
import com.upcpocket.app.upcauth.UpcShiguangAuthResult
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource
import com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Memory-only account-bound session/results. Construction and account observation do not network. */
class ShiguangService internal constructor(
    private val account: SchoolAccountReferenceReader,
    private val authenticator: ShiguangAuthenticator,
    private val api: ShiguangDataSource = ShiguangApi(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val interactiveAuthenticator: ShiguangInteractiveAuthenticator? = null,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(ShiguangState())
    val state: StateFlow<ShiguangState> = mutable.asStateFlow()
    private var owner: SchoolAccountReference? = null
    private var accountEpoch = 0L
    private var session: ShiguangSession? = null
    private var businessEpoch = 0L
    private var selectionEpoch = 0L
    private val authenticationMutex = Mutex()
    private sealed interface PendingRead {
        data class Activities(val keyword: String, val page: Int) : PendingRead
        data class Mine(val page: Int) : PendingRead
        data class Detail(val item: ShiguangActivity) : PendingRead
    }
    private class Interactive(
        val attempt: ShiguangInteractiveAttempt,
        val token: Token,
        val read: PendingRead,
        val operation: DiagnosticOperation,
    ) { var claimed = false }
    private class Browser(val attemptId: Long, val lease: ShiguangWebViewLease)
    private var pendingRead: PendingRead? = null
    private var interactive: Interactive? = null
    private var browser: Browser? = null
    private var attemptIds = 0L
    val supportsInteractiveLogin get() = interactiveAuthenticator != null

    init {
        scope.launch { combine(account.state, account.accountReference) { status, ref ->
            ref.takeUnless { status.blocksPersonalSessions || !status.isCredentialSaved || status.generation != ref?.credentialGeneration }
        }.collect { ref -> synchronized(lock) { if (ref != owner) reset(ref) } } }
    }
    private fun currentOwner(): SchoolAccountReference? {
        val status = account.state.value
        return account.accountReference.value?.takeIf { !status.blocksPersonalSessions && status.isCredentialSaved && status.generation == it.credentialGeneration }
    }
    private fun reset(ref: SchoolAccountReference?) {
        businessEpoch++; selectionEpoch++; session?.clear(); session = null; owner = ref
        accountEpoch++
        interactive = null; pendingRead = null
        browser?.lease?.dispose()
        mutable.value = ShiguangState(accountEpoch = accountEpoch, accountAvailable = ref != null)
    }
    suspend fun invalidateForAccountChange(): Boolean {
        val active = synchronized(lock) { reset(null); browser }
        if (active == null) return true
        val stopped = try { active.lease.disposeAndAwait() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
        if (stopped) synchronized(lock) { if (browser === active) browser = null }
        return stopped
    }

    fun openInteractiveLogin() {
        try { beginInteractiveLogin() } catch (failure: ShiguangException) {
            synchronized(lock) { mutable.value = mutable.value.copy(error = failure.reason) }
        }
    }
    fun beginInteractiveLogin(): ShiguangInteractiveAttempt = synchronized(lock) {
        val bridge = interactiveAuthenticator ?: throw ShiguangException(ShiguangFailure.INTERACTION_REQUIRED)
        val ref = currentOwner()
        if (ref != owner) reset(ref)
        if (ref == null) throw ShiguangException(ShiguangFailure.ACCOUNT_UNAVAILABLE)
        if (mutable.value.busy || interactive != null) throw ShiguangException(ShiguangFailure.PROTOCOL)
        browser?.lease?.dispose()
        if (browser != null) throw ShiguangException(ShiguangFailure.CLEANUP_PENDING)
        val operation = Diagnostics.beginOperation()
        recordShiguangWebEvent(
            operation, DiagnosticAction.AUTH_START, DiagnosticResult.PENDING,
            DiagnosticPhase.WEBVIEW_NAVIGATION, DiagnosticDetail.AUTH_REQUIRED,
            bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
        )
        if (bridge.inspectWebNavigation(bridge.entryUrl, "GET", true, ShiguangWebRequestKind.NAVIGATION, operation) != UpcShiguangWebNavigation.Allow) {
            recordShiguangWebEvent(
                operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.BLOCKED,
                DiagnosticPhase.WEBVIEW_NAVIGATION, DiagnosticDetail.WEBVIEW_NAVIGATION_BLOCKED,
                reason = DiagnosticReason.POLICY,
                navigationFrame = com.upcpocket.app.platform.diagnostics.DiagnosticNavigationFrame.MAIN,
            )
            throw ShiguangException(ShiguangFailure.PROTOCOL)
        }
        selectionEpoch++
        val attempt = ShiguangInteractiveAttempt(++attemptIds, bridge.entryUrl)
        val read = pendingRead ?: currentRead()
        val token = Token(ref, businessEpoch, selectionEpoch)
        interactive = Interactive(attempt, token, read, operation)
        recordShiguangQueryEvent(
            token, operation, DiagnosticAction.QUERY_START, DiagnosticResult.PENDING,
            DiagnosticPhase.AUTH, queryDetail(read),
        )
        mutable.value = mutable.value.copy(interactiveAttempt = attempt)
        attempt
    }
    fun inspectInteractiveNavigation(url: String, method: String, mainFrame: Boolean, kind: ShiguangWebRequestKind) =
        interactiveAuthenticator?.inspectWebNavigation(
            url, method, mainFrame, kind, synchronized(lock) { interactive?.operation },
        ) ?: UpcShiguangWebNavigation.Block
    fun isInteractiveLoginCurrent(id: Long): Boolean = synchronized(lock) { interactive?.let { it.attempt.id == id && valid(it.token) } == true }
    fun registerInteractiveWebView(id: Long, lease: ShiguangWebViewLease): AutoCloseable? {
        val accepted = synchronized(lock) {
            if (browser != null || !isInteractiveLoginCurrent(id)) false else { browser = Browser(id, lease); true }
        }
        if (!accepted) { lease.dispose(); return null }
        return AutoCloseable { synchronized(lock) { if (browser?.attemptId == id && browser?.lease === lease) browser = null } }
    }
    fun cancelInteractiveLogin(id: Long) {
        val lease = synchronized(lock) {
            if (interactive?.attempt?.id != id) return
            interactive?.let { active ->
                recordShiguangQueryEvent(
                    active.token, active.operation,
                    DiagnosticAction.QUERY_FAIL, DiagnosticResult.CANCELLED,
                    DiagnosticPhase.AUTH, queryDetail(active.read),
                )
            }
            interactive = null; selectionEpoch++
            mutable.value = mutable.value.copy(interactiveAttempt = null, busy = false)
            browser?.takeIf { it.attemptId == id }?.lease
        }
        lease?.dispose()
    }
    suspend fun completeInteractiveLogin(id: Long, callbackUrl: String) {
        val bridge = interactiveAuthenticator ?: throw ShiguangException(ShiguangFailure.INTERACTION_REQUIRED)
        val active = synchronized(lock) {
            val candidate = interactive ?: throw ShiguangException(ShiguangFailure.ACCOUNT_CHANGED)
            if (candidate.attempt.id != id || candidate.claimed || !valid(candidate.token)) {
                recordShiguangWebEvent(
                    candidate.operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.BLOCKED,
                    DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_REJECTED,
                    reason = DiagnosticReason.STALE, callbackState = DiagnosticCallbackState.STALE,
                    bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
                )
                throw ShiguangException(ShiguangFailure.ACCOUNT_CHANGED)
            }
            val decision = bridge.inspectWebNavigation(callbackUrl, "GET", true,
                ShiguangWebRequestKind.NAVIGATION, candidate.operation)
            if (decision !is UpcShiguangWebNavigation.Complete || decision.callbackUrl != callbackUrl) {
                recordShiguangQueryEvent(
                    candidate.token, candidate.operation,
                    DiagnosticAction.QUERY_FAIL, DiagnosticResult.FAIL,
                    DiagnosticPhase.WEBVIEW_CALLBACK, queryDetail(candidate.read), DiagnosticReason.POLICY,
                )
                recordShiguangWebEvent(
                    candidate.operation, DiagnosticAction.AUTH_STOP, DiagnosticResult.FAIL,
                    DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_REJECTED,
                    reason = DiagnosticReason.POLICY, callbackState = DiagnosticCallbackState.REJECTED,
                    bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
                )
                throw ShiguangException(ShiguangFailure.PROTOCOL)
            }
            candidate.claimed = true
            recordShiguangWebEvent(
                candidate.operation, DiagnosticAction.AUTH_STAGE, DiagnosticResult.PENDING,
                DiagnosticPhase.WEBVIEW_CALLBACK, DiagnosticDetail.WEBVIEW_CALLBACK_CONSUMED,
                callbackState = DiagnosticCallbackState.CONSUMED,
                bindingSource = DiagnosticBindingSource.VERIFIED_WEB,
            )
            mutable.value = mutable.value.copy(busy = true)
            candidate
        }
        val token = active.token
        var businessRequestStarted = false
        try {
            val result = authenticationMutex.withLock {
                ensure(token)
                bridge.completeWebLoginResult(
                    token.owner.accountId, token.owner.credentialGeneration.value, callbackUrl, active.operation,
                )
            }
            ensure(token)
            val session = acceptResult(result, token)
            // No password recovery after a completed manual login; resume this exact read once.
            execute(session, token, active.read) { businessRequestStarted = true }
            recordShiguangQueryEvent(
                token, active.operation, DiagnosticAction.QUERY_COMPLETE, DiagnosticResult.OK,
                DiagnosticPhase.PUBLISH, queryDetail(active.read),
            )
            synchronized(lock) { if (interactive === active && valid(token)) {
                interactive = null
                mutable.value = mutable.value.copy(interactiveAttempt = null, interactionRequired = false)
            } }
        } catch (cancel: CancellationException) {
            cancelInteractiveLogin(id); throw cancel
        } catch (failure: ShiguangException) {
            recordShiguangQueryEvent(
                token, active.operation, DiagnosticAction.QUERY_FAIL, DiagnosticResult.FAIL,
                if (businessRequestStarted) DiagnosticPhase.RESPONSE else DiagnosticPhase.AUTH,
                queryDetail(active.read), failure.reason.toDiagnosticReason(),
            )
            clearMismatchedIdentity(token, failure.reason)
            publish(token) { it.copy(busy = false, error = failure.reason, interactiveAttempt = null,
                interactionRequired = it.interactionRequired || failure.reason == ShiguangFailure.INTERACTION_REQUIRED) }
            synchronized(lock) { if (interactive === active) interactive = null }
            throw failure
        } catch (failure: Exception) {
            recordShiguangQueryEvent(
                token, active.operation, DiagnosticAction.QUERY_FAIL, DiagnosticResult.FAIL,
                if (businessRequestStarted) DiagnosticPhase.RESPONSE else DiagnosticPhase.AUTH,
                queryDetail(active.read), DiagnosticReason.UNKNOWN,
            )
            throw failure
        }
    }
    private fun currentRead(): PendingRead = mutable.value.let { s ->
        s.selected?.let { PendingRead.Detail(it) } ?: if (s.tab == ShiguangTab.MINE) PendingRead.Mine(s.participations?.page ?: 1)
        else PendingRead.Activities(s.keyword, s.activities?.page ?: 1)
    }

    fun closeDetail() = synchronized(lock) {
        selectionEpoch++; pendingRead = null
        mutable.value = mutable.value.copy(selected = null, detail = null, busy = false, error = null)
    }
    suspend fun activities(keyword: String = state.value.keyword, page: Int = 1) {
        require(keyword.length <= 40 && (keyword.isEmpty() || keyword.isNotBlank()) && page in 1..5000)
        run(ShiguangTab.ACTIVITIES, keyword, null, PendingRead.Activities(keyword, page))
    }
    suspend fun selectTab(tab: ShiguangTab) {
        val load = synchronized(lock) {
            val ref = currentOwner()
            if (ref != owner) reset(ref)
            val current = mutable.value
            if (ref == null || !current.accountAvailable || current.tab == tab || current.busy || interactive != null) return

            val hasVerifiedIdentity = current.identityVerified && session?.identityVerified == true
            if (tab == ShiguangTab.MINE && !hasVerifiedIdentity &&
                (current.identityVerified || current.summary != null || current.participations != null)
            ) {
                mutable.value = current.copy(summary = null, participations = null, identityVerified = false)
            }
            val currentState = mutable.value
            val cached = when (tab) {
                ShiguangTab.ACTIVITIES -> currentState.activities != null
                ShiguangTab.MINE -> currentState.identityVerified && session?.identityVerified == true &&
                    currentState.summary != null && currentState.participations != null
            }
            if (cached) {
                selectionEpoch++
                pendingRead = null
                mutable.value = currentState.copy(tab = tab, selected = null, detail = null, error = null)
                null
            } else tab
        }
        when (load) {
            ShiguangTab.ACTIVITIES -> activities(state.value.keyword, 1)
            ShiguangTab.MINE -> mine(state.value.participations?.page ?: 1)
            null -> Unit
        }
    }
    suspend fun mine(page: Int = 1) {
        require(page in 1..500)
        run(ShiguangTab.MINE, state.value.keyword, null, PendingRead.Mine(page))
    }
    suspend fun detail(item: ShiguangActivity) {
        run(state.value.tab, state.value.keyword, item, PendingRead.Detail(item))
    }
    suspend fun refresh() {
        val s = state.value
        when { s.selected != null -> detail(s.selected); s.tab == ShiguangTab.MINE -> mine(s.participations?.page ?: 1)
            else -> activities(s.keyword, s.activities?.page ?: 1) }
    }
    private data class Token(val owner: SchoolAccountReference, val business: Long, val selection: Long)
    private fun valid(token: Token): Boolean = owner == token.owner && currentOwner() == token.owner && businessEpoch == token.business && selectionEpoch == token.selection
    private fun ensure(token: Token) = synchronized(lock) { if (!valid(token)) throw ShiguangException(ShiguangFailure.ACCOUNT_CHANGED) }
    private fun publish(token: Token, update: (ShiguangState) -> ShiguangState) = synchronized(lock) {
        if (valid(token)) mutable.value = update(mutable.value)
    }
    private fun acceptResult(result: UpcShiguangAuthResult, token: Token): ShiguangSession {
        val active = when (result) {
            is UpcShiguangAuthResult.Success -> ShiguangSession(result.cookies, result.identityVerified)
            is UpcShiguangAuthResult.Failure -> throw ShiguangException(result.reason.featureFailure())
        }
        synchronized(lock) {
            if (!valid(token)) { active.clear(); throw ShiguangException(ShiguangFailure.ACCOUNT_CHANGED) }
            session?.clear(); session = active
            mutable.value = mutable.value.copy(identityVerified = active.identityVerified, interactionRequired = false,
                summary = if (active.identityVerified) mutable.value.summary else null,
                participations = if (active.identityVerified) mutable.value.participations else null)
        }
        return active
    }
    private suspend fun execute(
        active: ShiguangSession,
        token: Token,
        read: PendingRead,
        onBusinessRequest: () -> Unit = {},
    ) {
        ensure(token)
        when (read) {
            is PendingRead.Activities -> {
                onBusinessRequest()
                val result = api.page(active, read.page, read.keyword, false)
                publish(token) { it.copy(activities = result, busy = false, error = null) }
            }
            is PendingRead.Mine -> {
                if (!active.identityVerified) throw ShiguangException(ShiguangFailure.IDENTITY_UNVERIFIED)
                onBusinessRequest()
                val summary = api.summary(active); ensure(token)
                val result = api.page(active, read.page, "", true)
                publish(token) { it.copy(summary = summary, participations = result, busy = false, error = null) }
            }
            is PendingRead.Detail -> {
                onBusinessRequest()
                val result = api.detail(active, read.item)
                publish(token) { it.copy(detail = result, busy = false, error = null) }
            }
        }
    }
    private fun clearMismatchedIdentity(token: Token, reason: ShiguangFailure) = synchronized(lock) {
        if (reason == ShiguangFailure.IDENTITY_MISMATCH && valid(token)) {
            session?.clear(); session = null
            mutable.value = mutable.value.copy(summary = null, participations = null, identityVerified = false)
        }
    }
    private suspend fun run(tab: ShiguangTab, keyword: String, selected: ShiguangActivity?, read: PendingRead) {
        val token = synchronized(lock) {
            val ref = currentOwner()
            if (ref != owner) reset(ref)
            if (ref == null) {
                mutable.value = mutable.value.copy(error = ShiguangFailure.ACCOUNT_UNAVAILABLE)
                return
            }
            // Ignore duplicate queued UI actions before Compose can render disabled controls.
            if (mutable.value.busy || interactive != null) return
            selectionEpoch++; pendingRead = read
            val changedQuery = keyword != mutable.value.keyword
            mutable.value = mutable.value.copy(tab = tab, keyword = keyword, selected = selected,
                detail = if (selected != null && selected == mutable.value.selected) mutable.value.detail else null,
                activities = if (changedQuery) null else mutable.value.activities, busy = true, error = null)
            Token(ref, businessEpoch, selectionEpoch)
        }
        val operation = Diagnostics.beginOperation()
        val detail = queryDetail(read)
        recordShiguangQueryEvent(
            token, operation, DiagnosticAction.QUERY_START, DiagnosticResult.PENDING,
            DiagnosticPhase.AUTH, detail,
        )
        var authenticated = false
        var businessRequestStarted = false
        suspend fun authenticate(): ShiguangSession = authenticationMutex.withLock {
            authenticated = true
            ensure(token)
            val result = authenticator.authenticate(token.owner.accountId, token.owner.credentialGeneration.value)
            ensure(token)
            acceptResult(result, token)
        }
        try {
            val active = synchronized(lock) { session } ?: authenticate()
            try { execute(active, token, read) { businessRequestStarted = true } } catch (failure: ShiguangException) {
                if (failure.reason != ShiguangFailure.AUTH_REQUIRED || authenticated) throw failure
                ensure(token)
                synchronized(lock) { session?.clear(); session = null }
                execute(authenticate(), token, read) { businessRequestStarted = true }
            }
            recordShiguangQueryEvent(
                token, operation, DiagnosticAction.QUERY_COMPLETE, DiagnosticResult.OK,
                DiagnosticPhase.PUBLISH, detail,
            )
        } catch (cancel: CancellationException) {
            recordShiguangQueryEvent(
                token, operation, DiagnosticAction.QUERY_FAIL, DiagnosticResult.CANCELLED,
                if (businessRequestStarted) DiagnosticPhase.RESPONSE else DiagnosticPhase.AUTH,
                detail,
            )
            publish(token) { it.copy(busy = false) }; throw cancel
        } catch (failure: ShiguangException) {
            recordShiguangQueryEvent(
                token, operation, DiagnosticAction.QUERY_FAIL, DiagnosticResult.FAIL,
                if (businessRequestStarted) DiagnosticPhase.RESPONSE else DiagnosticPhase.AUTH,
                detail, failure.reason.toDiagnosticReason(),
            )
            clearMismatchedIdentity(token, failure.reason)
            publish(token) { it.copy(busy = false, error = failure.reason, interactionRequired = it.interactionRequired || failure.reason == ShiguangFailure.INTERACTION_REQUIRED) }
        } catch (failure: Exception) {
            recordShiguangQueryEvent(
                token, operation, DiagnosticAction.QUERY_FAIL, DiagnosticResult.FAIL,
                if (businessRequestStarted) DiagnosticPhase.RESPONSE else DiagnosticPhase.AUTH,
                detail, DiagnosticReason.UNKNOWN,
            )
            throw failure
        }
    }

    private fun recordShiguangQueryEvent(
        token: Token,
        operation: DiagnosticOperation,
        action: DiagnosticAction,
        result: DiagnosticResult,
        phase: DiagnosticPhase,
        detail: DiagnosticDetail,
        reason: DiagnosticReason? = null,
    ) = synchronized(lock) {
        if (!valid(token)) return@synchronized
        runCatching {
            Diagnostics.record(
                module = DiagnosticModule.SHIGUANG,
                action = action,
                result = result,
                reason = reason,
                operation = operation,
                phase = phase,
                detail = detail,
            )
        }
    }

    private fun queryDetail(read: PendingRead): DiagnosticDetail = when (read) {
        is PendingRead.Activities -> DiagnosticDetail.SHIGUANG_ACTIVITIES_QUERY
        is PendingRead.Mine -> DiagnosticDetail.SHIGUANG_MINE_QUERY
        is PendingRead.Detail -> DiagnosticDetail.SHIGUANG_DETAIL_QUERY
    }
}

private fun recordShiguangWebEvent(
    operation: DiagnosticOperation,
    action: DiagnosticAction,
    result: DiagnosticResult,
    phase: DiagnosticPhase,
    detail: DiagnosticDetail,
    reason: DiagnosticReason? = null,
    callbackState: DiagnosticCallbackState? = null,
    navigationFrame: com.upcpocket.app.platform.diagnostics.DiagnosticNavigationFrame? = null,
    bindingSource: DiagnosticBindingSource? = null,
) {
    runCatching {
        Diagnostics.record(
            module = DiagnosticModule.SHIGUANG,
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

private fun UpcShiguangAuthFailure.featureFailure(): ShiguangFailure = when (this) {
    UpcShiguangAuthFailure.INVALID_CREDENTIALS -> ShiguangFailure.INVALID_CREDENTIALS
    UpcShiguangAuthFailure.INTERACTION_REQUIRED -> ShiguangFailure.INTERACTION_REQUIRED
    UpcShiguangAuthFailure.NETWORK_UNAVAILABLE -> ShiguangFailure.NETWORK
    UpcShiguangAuthFailure.TLS_ERROR -> ShiguangFailure.TLS
    UpcShiguangAuthFailure.UPSTREAM_ERROR -> ShiguangFailure.UPSTREAM
    UpcShiguangAuthFailure.PROTOCOL_ERROR -> ShiguangFailure.PROTOCOL
    UpcShiguangAuthFailure.ACCOUNT_UNAVAILABLE -> ShiguangFailure.ACCOUNT_UNAVAILABLE
    UpcShiguangAuthFailure.ACCOUNT_CHANGED -> ShiguangFailure.ACCOUNT_CHANGED
    UpcShiguangAuthFailure.ACCESS_DENIED -> ShiguangFailure.ACCESS_DENIED
    UpcShiguangAuthFailure.IDENTITY_MISMATCH -> ShiguangFailure.IDENTITY_MISMATCH
}

private fun ShiguangFailure.toDiagnosticReason(): DiagnosticReason = when (this) {
    ShiguangFailure.NETWORK -> DiagnosticReason.NETWORK
    ShiguangFailure.TLS -> DiagnosticReason.TLS
    ShiguangFailure.ACCESS_DENIED -> DiagnosticReason.POLICY
    ShiguangFailure.AUTH_REQUIRED,
    ShiguangFailure.INVALID_CREDENTIALS,
    ShiguangFailure.IDENTITY_MISMATCH,
    -> DiagnosticReason.AUTH
    ShiguangFailure.INTERACTION_REQUIRED -> DiagnosticReason.INTERACTION
    ShiguangFailure.ACCOUNT_UNAVAILABLE,
    ShiguangFailure.ACCOUNT_CHANGED,
    ShiguangFailure.IDENTITY_UNVERIFIED,
    ShiguangFailure.CLEANUP_PENDING,
    -> DiagnosticReason.ACCOUNT
    ShiguangFailure.PROTOCOL,
    ShiguangFailure.UPSTREAM,
    -> DiagnosticReason.UNKNOWN
}
