package com.upcpocket.app.serviceportal

import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.upcauth.ServicePortalAuthenticator
import com.upcpocket.app.upcauth.UpcServicePortalAuthFailure
import com.upcpocket.app.upcauth.UpcServicePortalAuthResult
import com.upcpocket.app.upcauth.UpcServicePortalCookie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The plugin owns only an in-memory business session and the current visible result. */
class ServicePortalService internal constructor(
    private val account: SchoolAccountReferenceReader,
    private val authenticator: ServicePortalAuthenticator,
    private val api: ServicePortalDataSource = ServicePortalApi(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val lock = Any()
    private var owner: ServicePortalOwner? = currentOwner()
    private var businessEpoch = 0L
    private var selectionVersion = 0L
    private var activeToken: ServicePortalToken? = null
    private var activeJob: Job? = null
    private var session: ServicePortalSession? = null
    private var cachedAuthFailure: CachedAuthFailure? = null
    private val mutableState = MutableStateFlow(ServicePortalState(accountAvailable = owner != null))
    internal val state: StateFlow<ServicePortalState> = mutableState.asStateFlow()

    init {
        // Watching local account state is safe at construction time; it never starts authentication.
        scope.launch {
            combine(account.state, account.accountReference) { status, reference ->
                validOwner(status, reference)?.let(::ServicePortalOwner)
            }.distinctUntilChanged().collect { next ->
                synchronized(lock) {
                    if (next != owner) resetForOwner(next)
                }
            }
        }
    }

    /** No WebView is used in this flow. Clear in-memory requests and auth state before shared cookies. */
    fun invalidateForAccountChange(): Boolean = synchronized(lock) {
        resetForOwner(currentOwner())
        true
    }

    fun search(rawKeyword: String = state.value.keyword) {
        searchInternal(rawKeyword, clearCachedAuthFailure = true)
    }

    /** Explicit user retry: clear this owner's memoized auth failure and reload the visible data. */
    fun loginOrRetry() {
        val current = synchronized(lock) {
            val activeOwner = synchronizeOwner()
            val stateNow = mutableState.value
            if (stateNow.busy || stateNow.accountBlocked || activeOwner == null) return
            if (cachedAuthFailure?.let {
                    it.owner == activeOwner && it.businessEpoch == businessEpoch
                } == true
            ) {
                cachedAuthFailure = null
            }
            mutableState.value = stateNow.copy(
                loginStatus = ServicePortalLoginStatus.WORKING,
                error = null,
                authenticationNotice = null,
                refreshFailed = false,
            )
            val retryState = mutableState.value
            if (retryState.selected != null && retryState.catalog?.source == ServicePortalScope.PUBLIC) {
                // A public guide never authenticates. Return to the directory and let this same
                // bounded read authenticate before loading the account catalog.
                activeJob?.cancel()
                activeJob = null
                selectionVersion++
                val directoryState = retryState.copy(
                    selected = null,
                    guide = null,
                    busy = true,
                )
                mutableState.value = directoryState
                startLocked(
                    activeOwner,
                    Read.Search(directoryState.keyword, directoryState.searchPage, directoryState.pageSize),
                    retainSameQuery = true,
                )
                return
            }
            retryState
        }
        when {
            current.selected != null -> refreshGuide()
            current.tab == ServicePortalTab.SERVICES ->
                searchInternal(current.keyword, clearCachedAuthFailure = false)
            current.tab == ServicePortalTab.TODOS ->
                startPersonal(
                    Read.Todos(current.todos?.page ?: 1, current.pageSize),
                    ServicePortalTab.TODOS,
                    clearCachedAuthFailure = false,
                )
            else -> startPersonal(
                Read.Applications(current.applications?.page ?: 1, current.pageSize),
                ServicePortalTab.APPLICATIONS,
                clearCachedAuthFailure = false,
            )
        }
    }

    private fun searchInternal(rawKeyword: String, clearCachedAuthFailure: Boolean) {
        val keyword = try {
            ServicePortalProtocol.normalizeSearchKeyword(rawKeyword)
        } catch (failure: ServicePortalException) {
            synchronized(lock) {
                synchronizeOwner()
                if (mutableState.value.busy) return
                activeJob?.cancel()
                activeJob = null
                selectionVersion++
                mutableState.value = mutableState.value.copy(
                    keyword = rawKeyword,
                    catalog = null,
                    results = null,
                    selected = null,
                    guide = null,
                    searchPage = 1,
                    busy = false,
                    error = failure.reason,
                    refreshFailed = false,
                    authenticationNotice = null,
                )
            }
            return
        }
        synchronized(lock) {
            val current = synchronizeOwner()
            if (mutableState.value.busy) return
            if (clearCachedAuthFailure) cachedAuthFailure = null
            val sameQuery = keyword == mutableState.value.keyword
            val page = if (sameQuery) mutableState.value.searchPage else 1
            val next = mutableState.value.copy(
                tab = ServicePortalTab.SERVICES,
                keyword = keyword,
                searchPage = page,
                catalog = if (sameQuery) mutableState.value.catalog else null,
                results = if (sameQuery) mutableState.value.results else null,
                selected = null,
                guide = null,
                busy = true,
                error = null,
                refreshFailed = false,
                authenticationNotice = null,
            )
            mutableState.value = next
            startLocked(current, Read.Search(keyword, page, next.pageSize), retainSameQuery = sameQuery)
        }
    }

    fun pageSearch(page: Int) = synchronized(lock) {
        synchronizeOwner()
        if (mutableState.value.busy) return
        val catalog = mutableState.value.catalog ?: return
        try {
            val results = ServicePortalProtocol.search(catalog, mutableState.value.keyword, page, mutableState.value.pageSize)
            mutableState.value = mutableState.value.copy(searchPage = page, results = results, error = null, refreshFailed = false)
        } catch (failure: ServicePortalException) {
            mutableState.value = mutableState.value.copy(error = failure.reason)
        }
    }

    internal fun selectTab(tab: ServicePortalTab) {
        var loadTab = false
        synchronized(lock) {
            synchronizeOwner()
            val current = mutableState.value
            if (current.tab == tab || current.selected != null) return
            // Invalidate the old read now, including A→B→A transitions. Its network call may
            // finish, but it cannot recover authentication or publish into the selected tab.
            selectionVersion++
            mutableState.value = current.copy(tab = tab, error = null, refreshFailed = false, authenticationNotice = null)
            loadTab = !current.busy
        }
        if (loadTab) loadTabIfMissing(tab)
    }

    internal fun openGuide(item: ServicePortalItem) {
        synchronized(lock) {
            synchronizeOwner()
            val catalog = mutableState.value.catalog ?: return
            if (catalog.items.none { it.rowKey == item.rowKey && it.name == item.name }) return
            if (mutableState.value.busy) return
            val sameItem = mutableState.value.selected?.let { sameRow(it, item) } == true && mutableState.value.guide != null
            mutableState.value = mutableState.value.copy(
                tab = ServicePortalTab.SERVICES,
                selected = item,
                guide = if (sameItem) mutableState.value.guide else null,
                busy = !sameItem,
                error = null,
                refreshFailed = false,
                authenticationNotice = null,
            )
            if (!sameItem) {
                cachedAuthFailure = null
                startLocked(owner, Read.Guide(item, catalog.source), retainSameQuery = true)
            }
        }
    }

    fun refreshGuide() {
        synchronized(lock) {
            synchronizeOwner()
            val item = mutableState.value.selected ?: return
            if (mutableState.value.busy) return
            val source = mutableState.value.catalog?.source ?: ServicePortalScope.PUBLIC
            cachedAuthFailure = null
            mutableState.value = mutableState.value.copy(busy = true, error = null, refreshFailed = false, authenticationNotice = null)
            startLocked(owner, Read.Guide(item, source), retainSameQuery = true)
        }
    }

    fun closeGuide() = synchronized(lock) {
        synchronizeOwner()
        activeJob?.cancel()
        activeJob = null
        selectionVersion++
        mutableState.value = mutableState.value.copy(
            selected = null,
            guide = null,
            busy = false,
            error = null,
            refreshFailed = false,
            authenticationNotice = null,
        )
    }

    fun todos(page: Int = state.value.todos?.page ?: 1, pageSize: Int = state.value.pageSize) {
        startPersonal(Read.Todos(page, pageSize), ServicePortalTab.TODOS, clearCachedAuthFailure = true)
    }

    fun applications(page: Int = state.value.applications?.page ?: 1, pageSize: Int = state.value.pageSize) {
        startPersonal(Read.Applications(page, pageSize), ServicePortalTab.APPLICATIONS, clearCachedAuthFailure = true)
    }

    fun nextTodosPage() {
        val page = synchronized(lock) { synchronizeOwner(); state.value.todos } ?: return
        if (page.hasMore && page.page < MAX_PAGE) todos(page.page + 1, page.pageSize)
    }

    fun previousTodosPage() {
        val page = synchronized(lock) { synchronizeOwner(); state.value.todos } ?: return
        if (page.page > 1) todos(page.page - 1, page.pageSize)
    }

    fun nextApplicationsPage() {
        val page = synchronized(lock) { synchronizeOwner(); state.value.applications } ?: return
        if (page.hasMore && page.page < MAX_PAGE) applications(page.page + 1, page.pageSize)
    }

    fun previousApplicationsPage() {
        val page = synchronized(lock) { synchronizeOwner(); state.value.applications } ?: return
        if (page.page > 1) applications(page.page - 1, page.pageSize)
    }

    private fun startPersonal(read: Read, tab: ServicePortalTab, clearCachedAuthFailure: Boolean) {
        synchronized(lock) {
            val current = synchronizeOwner()
            if (mutableState.value.busy) return
            if (clearCachedAuthFailure) cachedAuthFailure = null
            val page = when (read) {
                is Read.Todos -> mutableState.value.todos?.takeIf { it.page == read.page }
                is Read.Applications -> mutableState.value.applications?.takeIf { it.page == read.page }
                else -> null
            }
            mutableState.value = mutableState.value.copy(
                tab = tab,
                todos = if (read is Read.Todos && page == null) null else mutableState.value.todos,
                applications = if (read is Read.Applications && page == null) null else mutableState.value.applications,
                busy = true,
                error = null,
                refreshFailed = false,
                authenticationNotice = null,
            )
            startLocked(current, read, retainSameQuery = page != null)
        }
    }

    private fun startLocked(current: ServicePortalOwner?, read: Read, retainSameQuery: Boolean) {
        selectionVersion++
        val token = ServicePortalToken(current, businessEpoch, selectionVersion)
        activeToken = token
        activeJob = null
        val job = scope.launch {
            try {
                when (read) {
                    is Read.Search -> {
                        val result = readCatalog(token)
                        val requestedPage = ServicePortalProtocol.search(result.catalog, read.keyword, read.page, read.pageSize)
                        val effectivePage = if (requestedPage.pageInRange) {
                            read.page
                        } else {
                            maxOf(1, ((requestedPage.total.toLong() + read.pageSize - 1) / read.pageSize).toInt())
                        }
                        val page = if (effectivePage == read.page) requestedPage else {
                            ServicePortalProtocol.search(result.catalog, read.keyword, effectivePage, read.pageSize)
                        }
                        publish(token, read) { it.copy(
                            catalog = result.catalog,
                            results = page,
                            searchPage = effectivePage,
                            busy = false,
                            error = null,
                            refreshFailed = false,
                            authenticationNotice = result.authenticationNotice,
                        ) }
                    }
                    is Read.Guide -> {
                        val result = readGuide(token, read.item, read.scope)
                        publish(token, read) { it.copy(
                            guide = result.guide,
                            selected = read.item,
                            busy = false,
                            error = null,
                            refreshFailed = false,
                            authenticationNotice = result.authenticationNotice,
                        ) }
                    }
                    is Read.Todos -> {
                        val result = readPersonal(token) { active -> api.todos(active, read.page, read.pageSize) }
                        publish(token, read) { it.copy(todos = result, busy = false, error = null, refreshFailed = false) }
                    }
                    is Read.Applications -> {
                        val result = readPersonal(token) { active -> api.applications(active, read.page, read.pageSize) }
                        publish(token, read) { it.copy(applications = result, busy = false, error = null, refreshFailed = false) }
                    }
                }
            } catch (cancel: CancellationException) {
                publish(token, read) { it.copy(busy = false) }
                throw cancel
            } catch (failure: ServicePortalException) {
                publish(token, read) { currentState ->
                    currentState.copy(
                        busy = false,
                        error = failure.reason,
                        refreshFailed = retainSameQuery && hasDataFor(read, currentState),
                    )
                }
            } catch (_: Exception) {
                publish(token, read) { currentState ->
                    currentState.copy(
                        busy = false,
                        error = ServicePortalFailure.PROTOCOL,
                        refreshFailed = retainSameQuery && hasDataFor(read, currentState),
                    )
                }
            } finally {
                var reloadTab: ServicePortalTab? = null
                synchronized(lock) {
                    if (activeToken === token) {
                        activeToken = null
                        activeJob = null
                        val currentState = mutableState.value
                        if (validBusiness(token) && currentState.busy) {
                            mutableState.value = currentState.copy(busy = false)
                            if (token.selectionVersion != selectionVersion || currentState.tab != read.tab) {
                                reloadTab = currentState.tab
                            }
                        }
                    }
                }
                reloadTab?.let(::loadTabIfMissing)
            }
        }
        if (activeToken === token) activeJob = job
    }

    private fun hasDataFor(read: Read, value: ServicePortalState): Boolean = when (read) {
        is Read.Search -> value.catalog != null && value.keyword == read.keyword
        is Read.Guide -> value.guide != null && value.selected?.let { sameRow(it, read.item) } == true
        is Read.Todos -> value.todos != null
        is Read.Applications -> value.applications != null
    }

    private suspend fun readCatalog(token: ServicePortalToken): CatalogRead {
        val activeOwner = token.owner
        if (activeOwner == null) {
            ensure(token)
            return CatalogRead(api.catalog(null), null)
        }
        val reused = existingSession(activeOwner)
        var active = reused
        var authNotice: ServicePortalFailure? = null
        if (active == null) {
            try {
                active = authenticate(activeOwner, token)
            } catch (failure: ServicePortalException) {
                if (!failure.reason.allowsPublicFallback()) throw failure
                authNotice = failure.reason
            }
        }
        if (active != null) {
            try {
                ensure(token)
                return CatalogRead(api.catalog(active), null)
            } catch (failure: ServicePortalException) {
                if (!failure.reason.isAuthenticationFailure()) throw failure
                ensure(token)
                clearSession(activeOwner, token)
                if (failure.reason == ServicePortalFailure.AUTH_REQUIRED && reused != null) {
                    try {
                        active = authenticate(activeOwner, token)
                        ensure(token)
                        return CatalogRead(api.catalog(active), null)
                    } catch (recovery: ServicePortalException) {
                        setLoginStatus(token, ServicePortalLoginStatus.FAILED)
                        if (!recovery.reason.allowsPublicFallback()) throw recovery
                        authNotice = recovery.reason
                    }
                } else {
                    setLoginStatus(token, ServicePortalLoginStatus.FAILED)
                    authNotice = failure.reason
                }
            }
        }
        ensure(token)
        return CatalogRead(api.catalog(null), authNotice ?: ServicePortalFailure.ACCOUNT_UNAVAILABLE)
    }

    private suspend fun readGuide(
        token: ServicePortalToken,
        item: ServicePortalItem,
        source: ServicePortalScope,
    ): GuideRead {
        if (source == ServicePortalScope.PUBLIC) {
            ensure(token)
            return GuideRead(api.guide(null, item), null)
        }
        val activeOwner = token.owner ?: throw ServicePortalException(ServicePortalFailure.ACCOUNT_UNAVAILABLE)
        val reused = existingSession(activeOwner)
        var active = reused
        var authNotice: ServicePortalFailure? = null
        if (active == null) {
            try {
                active = authenticate(activeOwner, token)
            } catch (failure: ServicePortalException) {
                if (!failure.reason.allowsPublicFallback()) throw failure
                authNotice = failure.reason
            }
        }
        if (active != null) {
            try {
                ensure(token)
                return GuideRead(api.guide(active, item), null)
            } catch (failure: ServicePortalException) {
                if (!failure.reason.isAuthenticationFailure()) throw failure
                ensure(token)
                clearSession(activeOwner, token)
                if (failure.reason == ServicePortalFailure.AUTH_REQUIRED && reused != null) {
                    try {
                        active = authenticate(activeOwner, token)
                        ensure(token)
                        return GuideRead(api.guide(active, item), null)
                    } catch (recovery: ServicePortalException) {
                        setLoginStatus(token, ServicePortalLoginStatus.FAILED)
                        if (!recovery.reason.allowsPublicFallback()) throw recovery
                        authNotice = recovery.reason
                    }
                } else {
                    setLoginStatus(token, ServicePortalLoginStatus.FAILED)
                    authNotice = failure.reason
                }
            }
        }
        ensure(token)
        return GuideRead(api.guide(null, item), authNotice ?: ServicePortalFailure.ACCOUNT_UNAVAILABLE)
    }

    private suspend fun <T> readPersonal(
        token: ServicePortalToken,
        request: suspend (ServicePortalSession) -> T,
    ): T {
        val activeOwner = token.owner ?: throw ServicePortalException(ServicePortalFailure.ACCOUNT_UNAVAILABLE)
        val reused = existingSession(activeOwner)
        val activeWasReused = reused != null
        var active = reused ?: authenticate(activeOwner, token)
        try {
            ensure(token)
            return request(active)
        } catch (failure: ServicePortalException) {
            if (failure.reason != ServicePortalFailure.AUTH_REQUIRED) throw failure
            ensure(token)
            clearSession(activeOwner, token)
            if (!activeWasReused) {
                setLoginStatus(token, ServicePortalLoginStatus.FAILED)
                throw failure
            }
            active = authenticate(activeOwner, token)
            ensure(token)
            return request(active)
        }
    }

    private suspend fun authenticate(owner: ServicePortalOwner, token: ServicePortalToken): ServicePortalSession {
        ensure(token)
        val rememberedFailure = synchronized(lock) {
            synchronizeOwner()
            if (!valid(token)) throw ServicePortalException(ServicePortalFailure.ACCOUNT_CHANGED)
            cachedAuthFailure?.takeIf {
                it.owner == owner && it.businessEpoch == businessEpoch
            }?.failure
        }
        rememberedFailure?.let { throw ServicePortalException(it) }
        setLoginStatus(token, ServicePortalLoginStatus.WORKING)
        val result = authenticator.authenticate(owner.reference.accountId, owner.reference.credentialGeneration.value)
        // Authentication is account scoped, so a successful in-flight login may be reused by
        // the newly selected tab. The old business read still fails ensure(token) before its next
        // request because that check also includes selectionVersion.
        if (!validBusinessOwner(token, owner)) {
            throw ServicePortalException(ServicePortalFailure.ACCOUNT_CHANGED)
        }
        return when (result) {
            is UpcServicePortalAuthResult.Failure -> {
                if (result.reason == UpcServicePortalAuthFailure.IDENTITY_MISMATCH &&
                    !clearSessionForAuthResult(owner, token)
                ) {
                    throw ServicePortalException(ServicePortalFailure.ACCOUNT_CHANGED)
                }
                val failure = result.reason.toFeatureFailure()
                rememberAuthFailure(owner, token, failure)
                setLoginStatus(token, ServicePortalLoginStatus.FAILED)
                throw ServicePortalException(failure)
            }
            is UpcServicePortalAuthResult.Success -> {
                if (result.binding != owner.reference) {
                    if (!clearSessionForAuthResult(owner, token)) {
                        throw ServicePortalException(ServicePortalFailure.ACCOUNT_CHANGED)
                    }
                    rememberAuthFailure(owner, token, ServicePortalFailure.IDENTITY_MISMATCH)
                    setLoginStatus(token, ServicePortalLoginStatus.FAILED)
                    throw ServicePortalException(ServicePortalFailure.IDENTITY_MISMATCH)
                }
                if (result.cookies.isEmpty() || result.cookies.any { it.domain != UpcServicePortalCookie.HOST }) {
                    if (!clearSessionForAuthResult(owner, token)) {
                        throw ServicePortalException(ServicePortalFailure.ACCOUNT_CHANGED)
                    }
                    rememberAuthFailure(owner, token, ServicePortalFailure.PROTOCOL)
                    setLoginStatus(token, ServicePortalLoginStatus.FAILED)
                    throw ServicePortalException(ServicePortalFailure.PROTOCOL)
                }
                val created = ServicePortalSession(result.cookies, owner)
                synchronized(lock) {
                    if (!validBusiness(token)) {
                        created.clear()
                        throw ServicePortalException(ServicePortalFailure.ACCOUNT_CHANGED)
                    }
                    session?.clear()
                    session = created
                    cachedAuthFailure = null
                    mutableState.value = mutableState.value.copy(loginStatus = ServicePortalLoginStatus.SUCCEEDED)
                }
                created
            }
        }
    }

    private fun existingSession(owner: ServicePortalOwner): ServicePortalSession? = synchronized(lock) {
        if (synchronizeOwner() == owner) session?.takeIf { it.owner == owner } else null
    }

    /** An auth result may clear shared auth only while its captured business epoch still owns it. */
    private fun clearSessionForAuthResult(owner: ServicePortalOwner, token: ServicePortalToken): Boolean =
        synchronized(lock) {
            synchronizeOwner()
            if (token.businessEpoch != businessEpoch || token.owner != owner || this.owner != owner) {
                return@synchronized false
            }
            session?.clear()
            session = null
            cachedAuthFailure = null
            authenticator.clearSession()
            true
        }

    private fun clearSession(owner: ServicePortalOwner, token: ServicePortalToken) = synchronized(lock) {
        synchronizeOwner()
        if (!valid(token)) throw ServicePortalException(ServicePortalFailure.ACCOUNT_CHANGED)
        if (this.owner == owner) {
            session?.clear()
            session = null
            cachedAuthFailure = null
            authenticator.clearSession()
        }
    }

    private fun rememberAuthFailure(owner: ServicePortalOwner, token: ServicePortalToken, failure: ServicePortalFailure) {
        synchronized(lock) {
            if (validBusinessOwner(token, owner)) {
                cachedAuthFailure = CachedAuthFailure(owner, token.businessEpoch, failure)
                mutableState.value = mutableState.value.copy(loginStatus = ServicePortalLoginStatus.FAILED)
            }
        }
    }

    private fun setLoginStatus(token: ServicePortalToken, status: ServicePortalLoginStatus) {
        synchronized(lock) {
            synchronizeOwner()
            if (token.businessEpoch == businessEpoch && token.owner == owner) {
                mutableState.value = mutableState.value.copy(loginStatus = status)
            }
        }
    }

    private fun validBusinessOwner(token: ServicePortalToken, expectedOwner: ServicePortalOwner): Boolean =
        synchronized(lock) {
            synchronizeOwner()
            token.businessEpoch == businessEpoch && token.owner == expectedOwner && owner == expectedOwner
        }

    private fun validBusiness(token: ServicePortalToken): Boolean = synchronized(lock) {
        synchronizeOwner()
        token.businessEpoch == businessEpoch && token.owner == owner
    }

    private fun ensure(token: ServicePortalToken) {
        if (!synchronized(lock) { valid(token) }) throw ServicePortalException(ServicePortalFailure.ACCOUNT_CHANGED)
    }

    private fun publish(
        token: ServicePortalToken,
        read: Read,
        update: (ServicePortalState) -> ServicePortalState,
    ) = synchronized(lock) {
        if (valid(token) && mutableState.value.tab == read.tab) mutableState.value = update(mutableState.value)
    }

    private fun loadTabIfMissing(tab: ServicePortalTab) = synchronized(lock) {
        synchronizeOwner()
        val current = mutableState.value
        if (current.tab != tab || current.busy || current.selected != null) return@synchronized
        when (tab) {
            ServicePortalTab.SERVICES -> if (current.catalog == null || current.results == null) {
                searchInternal(current.keyword, clearCachedAuthFailure = false)
            }
            ServicePortalTab.TODOS -> if (current.todos == null) {
                startPersonal(Read.Todos(1, current.pageSize), ServicePortalTab.TODOS, clearCachedAuthFailure = false)
            }
            ServicePortalTab.APPLICATIONS -> if (current.applications == null) {
                startPersonal(Read.Applications(1, current.pageSize), ServicePortalTab.APPLICATIONS, clearCachedAuthFailure = false)
            }
        }
    }

    private fun valid(token: ServicePortalToken): Boolean =
        validBusiness(token) &&
            token.selectionVersion == selectionVersion &&
            token.owner == currentOwner()

    private fun synchronizeOwner(): ServicePortalOwner? {
        val latest = currentOwner()
        if (latest != owner) resetForOwner(latest)
        else {
            val blocked = account.state.value.blocksPersonalSessions
            if (mutableState.value.accountBlocked != blocked) {
                mutableState.value = mutableState.value.copy(accountBlocked = blocked)
            }
        }
        return owner
    }

    private fun currentOwner(): ServicePortalOwner? =
        validOwner(account.state.value, account.accountReference.value)?.let(::ServicePortalOwner)

    private fun validOwner(
        status: com.upcpocket.app.account.api.SchoolAccountState,
        reference: SchoolAccountReference?,
    ): SchoolAccountReference? = reference?.takeIf {
        !status.blocksPersonalSessions && status.isCredentialSaved && status.generation == it.credentialGeneration
    }

    private fun resetForOwner(next: ServicePortalOwner?) {
        activeJob?.cancel()
        activeToken = null
        activeJob = null
        businessEpoch++
        selectionVersion++
        session?.clear()
        session = null
        cachedAuthFailure = null
        authenticator.clearSession()
        owner = next
        mutableState.value = ServicePortalState(
            accountEpoch = mutableState.value.accountEpoch + 1,
            accountAvailable = next != null,
            accountBlocked = account.state.value.blocksPersonalSessions,
            loginStatus = if (next == null) ServicePortalLoginStatus.NO_ACCOUNT
            else ServicePortalLoginStatus.IDLE,
        )
    }

    private fun sameRow(first: ServicePortalItem, second: ServicePortalItem): Boolean =
        first.rowKey == second.rowKey && first.name == second.name && first.department == second.department

    private sealed interface Read {
        data class Search(val keyword: String, val page: Int, val pageSize: Int) : Read
        data class Guide(val item: ServicePortalItem, val scope: ServicePortalScope) : Read
        data class Todos(val page: Int, val pageSize: Int) : Read
        data class Applications(val page: Int, val pageSize: Int) : Read
    }

    private val Read.tab: ServicePortalTab
        get() = when (this) {
            is Read.Search, is Read.Guide -> ServicePortalTab.SERVICES
            is Read.Todos -> ServicePortalTab.TODOS
            is Read.Applications -> ServicePortalTab.APPLICATIONS
        }

    private data class CatalogRead(val catalog: ServicePortalCatalog, val authenticationNotice: ServicePortalFailure?)
    private data class GuideRead(val guide: ServicePortalGuide, val authenticationNotice: ServicePortalFailure?)
    private data class CachedAuthFailure(
        val owner: ServicePortalOwner,
        val businessEpoch: Long,
        val failure: ServicePortalFailure,
    )

    private fun ServicePortalFailure.allowsPublicFallback(): Boolean = when (this) {
        ServicePortalFailure.ACCOUNT_UNAVAILABLE,
        ServicePortalFailure.INVALID_CREDENTIALS,
        ServicePortalFailure.INTERACTION_REQUIRED,
        ServicePortalFailure.AUTH_REQUIRED,
        ServicePortalFailure.IDENTITY_MISMATCH -> true
        else -> false
    }

    private fun ServicePortalFailure.isAuthenticationFailure(): Boolean =
        this == ServicePortalFailure.AUTH_REQUIRED || this == ServicePortalFailure.INTERACTION_REQUIRED

    private fun UpcServicePortalAuthFailure.toFeatureFailure(): ServicePortalFailure = when (this) {
        UpcServicePortalAuthFailure.INVALID_CREDENTIALS -> ServicePortalFailure.INVALID_CREDENTIALS
        UpcServicePortalAuthFailure.INTERACTION_REQUIRED -> ServicePortalFailure.INTERACTION_REQUIRED
        UpcServicePortalAuthFailure.NETWORK_UNAVAILABLE -> ServicePortalFailure.NETWORK
        UpcServicePortalAuthFailure.TLS_ERROR -> ServicePortalFailure.TLS
        UpcServicePortalAuthFailure.UPSTREAM_ERROR -> ServicePortalFailure.UPSTREAM
        UpcServicePortalAuthFailure.PROTOCOL_ERROR -> ServicePortalFailure.PROTOCOL
        UpcServicePortalAuthFailure.ACCOUNT_UNAVAILABLE -> ServicePortalFailure.ACCOUNT_UNAVAILABLE
        UpcServicePortalAuthFailure.ACCOUNT_CHANGED -> ServicePortalFailure.ACCOUNT_CHANGED
        UpcServicePortalAuthFailure.ACCESS_DENIED -> ServicePortalFailure.ACCESS_DENIED
        UpcServicePortalAuthFailure.IDENTITY_MISMATCH -> ServicePortalFailure.IDENTITY_MISMATCH
        UpcServicePortalAuthFailure.BUSY -> ServicePortalFailure.BUSY
    }

    private companion object {
        const val MAX_PAGE = 50
    }
}
