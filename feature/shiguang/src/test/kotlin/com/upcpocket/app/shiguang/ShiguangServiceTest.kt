package com.upcpocket.app.shiguang

import com.upcpocket.app.account.api.*
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticSink
import com.upcpocket.app.platform.diagnostics.Diagnostics
import com.upcpocket.app.platform.diagnostics.EpochBoundDiagnosticSink
import com.upcpocket.app.upcauth.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShiguangServiceTest {
    private class RecordingSink : DiagnosticSink {
        val events = mutableListOf<DiagnosticEvent>()
        override fun record(event: DiagnosticEvent) { events += event }
    }

    private class EpochSink : EpochBoundDiagnosticSink {
        val events = mutableListOf<DiagnosticEvent>()
        var epoch = 0L
        override fun captureEpoch() = epoch
        override fun record(event: DiagnosticEvent) { events += event }
        override fun record(event: DiagnosticEvent, expectedEpoch: Long?) {
            if (expectedEpoch == epoch) events += event
        }
    }

    private class Account : SchoolAccountReferenceReader {
        override val state = MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)))
        override val accountReference = MutableStateFlow<SchoolAccountReference?>(SchoolAccountReference(SchoolAccountId("syntheticA"), AccountGeneration(1)))
        fun change(id: String, generation: Long, blocked: Boolean = false) {
            state.value = SchoolAccountState(if (blocked) SchoolAccountStatus.UPDATING else SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(generation), cleanupRequired = blocked)
            accountReference.value = if (blocked) null else SchoolAccountReference(SchoolAccountId(id), AccountGeneration(generation))
        }
        fun blockWithoutClearingReference() {
            state.value = SchoolAccountState(SchoolAccountStatus.UPDATING, AccountGeneration(1), cleanupRequired = true)
        }
        fun makeUnavailableWithoutClearingReference() {
            state.value = SchoolAccountState(SchoolAccountStatus.NOT_CONFIGURED, AccountGeneration(0))
        }
    }
    private class Auth(var verified: Boolean = true) : ShiguangAuthenticator {
        var calls = 0
        val generations = mutableListOf<Long>()
        var result: UpcShiguangAuthResult? = null
        override suspend fun authenticate(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long): UpcShiguangAuthResult {
            calls++; generations += expectedCredentialGeneration
            return result ?: UpcShiguangAuthResult.Success(emptyList(), verified)
        }
    }
    private open class Api : ShiguangDataSource {
        var pages = 0; var personal = 0; var expire = false
        override suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean): ShiguangPage {
            pages++; if (mine) personal++
            if (expire) throw ShiguangException(ShiguangFailure.AUTH_REQUIRED)
            return ShiguangPage(page, 2, listOf(ShiguangActivity("1", ShiguangKind.ACTIVITY, keyword.ifEmpty { "合成活动" })))
        }
        override suspend fun detail(session: ShiguangSession, item: ShiguangActivity) = ShiguangDetail(item.title, "合成内容", emptyMap())
        override suspend fun summary(session: ShiguangSession): ShiguangSummary { personal++; return ShiguangSummary(emptyList()) }
    }

    @Test fun readsRecordOneFixedOperationFromStartThroughCompleteOrFailure() = runTest {
        val sink = RecordingSink()
        Diagnostics.install(sink)
        try {
            val service = ShiguangService(Account(), Auth(), Api(), backgroundScope)
            service.activities()
            var query = sink.events.filter { it.module == DiagnosticModule.SHIGUANG && it.action in setOf(
                DiagnosticAction.QUERY_START, DiagnosticAction.QUERY_COMPLETE, DiagnosticAction.QUERY_FAIL,
            ) }
            assertEquals(listOf(DiagnosticAction.QUERY_START, DiagnosticAction.QUERY_COMPLETE), query.map { it.action })
            assertEquals(setOf(DiagnosticDetail.SHIGUANG_ACTIVITIES_QUERY), query.map { it.detail }.toSet())
            assertEquals(1, query.map { it.operationId }.toSet().size)
            assertEquals(DiagnosticPhase.AUTH, query.first().phase)
            assertEquals(DiagnosticPhase.PUBLISH, query.last().phase)

            sink.events.clear()
            val failingApi = object : Api() {
                override suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean): ShiguangPage {
                    throw ShiguangException(ShiguangFailure.NETWORK)
                }
            }
            val failingService = ShiguangService(Account(), Auth(), failingApi, backgroundScope)
            failingService.activities()
            query = sink.events.filter { it.module == DiagnosticModule.SHIGUANG && it.action in setOf(
                DiagnosticAction.QUERY_START, DiagnosticAction.QUERY_COMPLETE, DiagnosticAction.QUERY_FAIL,
            ) }
            assertEquals(listOf(DiagnosticAction.QUERY_START, DiagnosticAction.QUERY_FAIL), query.map { it.action })
            assertEquals(DiagnosticDetail.SHIGUANG_ACTIVITIES_QUERY, query.last().detail)
            assertEquals(DiagnosticReason.NETWORK, query.last().reason)
            assertEquals(DiagnosticResult.FAIL, query.last().result)
            assertEquals(query.first().operationId, query.last().operationId)
        } finally {
            Diagnostics.install(null)
        }
    }

    @Test fun lateReadDiagnosticsAreRejectedAfterDiagnosticEpochChangesWithoutBlockingTheRead() = runTest {
        val sink = EpochSink()
        Diagnostics.install(sink)
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val api = object : Api() {
                override suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean): ShiguangPage {
                    started.complete(Unit)
                    release.await()
                    return super.page(session, page, keyword, mine)
                }
            }
            val service = ShiguangService(Account(), Auth(), api, backgroundScope)
            val pending = launch { service.activities() }
            started.await()
            val startedEvent = sink.events.single { it.action == DiagnosticAction.QUERY_START }
            sink.epoch++

            release.complete(Unit)
            pending.join()

            assertNotNull(service.state.value.activities)
            assertTrue(sink.events.none {
                it.operationId == startedEvent.operationId && it.action == DiagnosticAction.QUERY_COMPLETE
            })
            assertEquals(listOf(DiagnosticAction.QUERY_START), sink.events.filter {
                it.module == DiagnosticModule.SHIGUANG && it.action in setOf(
                    DiagnosticAction.QUERY_START, DiagnosticAction.QUERY_COMPLETE, DiagnosticAction.QUERY_FAIL,
                )
            }.map { it.action })
        } finally {
            Diagnostics.install(null)
        }
    }

    @Test fun constructionDoesNotAuthenticateOrQueryAndPluginHasOnlyHomeEntry() = runTest {
        val auth = Auth(); val api = Api()
        ShiguangService(Account(), auth, api, backgroundScope)
        runCurrent(); assertEquals(0, auth.calls); assertEquals(0, api.pages)
        val plugin = ShiguangPlugin(Account(), auth)
        val registry = PluginUiRegistry(); plugin.contribute(registry)
        val ui = registry.build()
        assertEquals("shiguang.home", ui.entriesFor(UiSurface.HOME_SERVICES).single().route!!.value)
        assertTrue(ui.bottomNav.isEmpty()); assertEquals("石光", ui.entries.single().title)
        assertTrue(PluginUiRegistry().build().entriesFor(UiSurface.HOME_SERVICES).isEmpty())
        assertEquals(0, auth.calls)
    }
    @Test fun falseIdentityCanReadActivitiesButNeverPersonalData() = runTest {
        val auth = Auth(false); val api = Api(); val service = ShiguangService(Account(), auth, api, backgroundScope)
        service.activities(); assertNotNull(service.state.value.activities)
        service.mine(); assertEquals(ShiguangFailure.IDENTITY_UNVERIFIED, service.state.value.error)
        assertNull(service.state.value.summary); assertEquals(0, api.personal); assertEquals(1, auth.calls)
    }
    @Test fun identityMismatchAndFixedAuthFailuresDoNotSendBusinessRequests() = runTest {
        val auth = Auth().apply { result = UpcShiguangAuthResult.Failure(UpcShiguangAuthFailure.IDENTITY_MISMATCH) }
        val api = Api(); val service = ShiguangService(Account(), auth, api, backgroundScope)
        service.mine(); assertEquals(0, api.pages); assertEquals(0, api.personal)
        assertEquals(ShiguangFailure.IDENTITY_MISMATCH, service.state.value.error)
    }
    @Test fun eachManualActionRecoversAtMostOnceAndRetainsSameOwnerResultWithError() = runTest {
        val auth = Auth(); val api = Api(); val service = ShiguangService(Account(), auth, api, backgroundScope)
        service.activities(); val old = service.state.value.activities
        api.expire = true
        service.refresh()
        assertEquals(2, auth.calls); assertEquals(3, api.pages)
        assertEquals(old, service.state.value.activities)
        assertEquals(ShiguangFailure.AUTH_REQUIRED, service.state.value.error)
    }
    @Test fun queuedSelectionWhileBusyDoesNotCreateAnotherRequestAndNextSelectionWorks() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = object : Api() {
            override suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean): ShiguangPage {
                if (keyword == "旧") { started.complete(Unit); release.await() }
                return super.page(session, page, keyword, mine)
            }
        }
        val service = ShiguangService(Account(), Auth(), api, backgroundScope)
        val old = launch { service.activities("旧") }; started.await()
        service.activities("新", 2)
        assertEquals("旧", service.state.value.keyword)
        release.complete(Unit); old.join()
        service.activities("新", 2)
        assertEquals("新", service.state.value.keyword); assertEquals(2, service.state.value.activities!!.page)
        assertEquals("新", service.state.value.activities!!.items.single().title)
    }
    @Test fun rapidTabEventsDoNotSubmitConcurrentOrDuplicateAuthentication() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val auth = object : ShiguangAuthenticator {
            override suspend fun authenticate(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long): UpcShiguangAuthResult {
                calls++; started.complete(Unit); release.await(); return UpcShiguangAuthResult.Success(emptyList(), true)
            }
        }
        val api = Api(); val service = ShiguangService(Account(), auth, api, backgroundScope)
        val first = launch { service.activities() }; started.await()
        service.mine(); service.activities("重复")
        assertEquals(1, calls); assertEquals(ShiguangTab.ACTIVITIES, service.state.value.tab)
        release.complete(Unit); first.join()
        assertEquals(1, api.pages); assertEquals(0, api.personal)
    }
    @Test fun newOwnerWaitsForOldAuthenticationToFinishWithoutConcurrentPasswordSubmissions() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val account = Account()
        var calls = 0; var active = 0; var maxActive = 0
        val auth = object : ShiguangAuthenticator {
            override suspend fun authenticate(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long): UpcShiguangAuthResult {
                calls++; active++; maxActive = maxOf(maxActive, active); started.complete(Unit)
                release.await(); active--; return UpcShiguangAuthResult.Success(emptyList(), true)
            }
        }
        val service = ShiguangService(account, auth, Api(), backgroundScope)
        val old = launch { service.activities() }; started.await()
        service.invalidateForAccountChange(); account.change("syntheticB", 2); runCurrent()
        val fresh = launch { service.activities() }; runCurrent()
        assertEquals(1, calls)
        release.complete(Unit); old.join(); fresh.join()
        assertEquals(2, calls); assertEquals(1, maxActive); assertNotNull(service.state.value.activities)
    }
    @Test fun latePersonalReadIsRejectedAfterAtoBtoAAndPasswordGenerationChange() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val account = Account()
        val api = object : Api() {
            override suspend fun summary(session: ShiguangSession): ShiguangSummary { started.complete(Unit); release.await(); return super.summary(session) }
        }
        val service = ShiguangService(account, Auth(), api, backgroundScope)
        val pending = launch { service.mine() }; started.await()
        service.invalidateForAccountChange(); account.change("syntheticB", 2); runCurrent()
        account.change("syntheticA", 3); runCurrent()
        release.complete(Unit); pending.join()
        assertNull(service.state.value.summary); assertNull(service.state.value.participations)
        assertEquals(0, api.pages)
        account.change("syntheticA", 4, blocked = true); runCurrent()
        assertFalse(service.state.value.accountAvailable); assertFalse(service.state.value.identityVerified)
    }
    @Test fun lateDetailCannotReopenAfterBack() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = object : Api() {
            override suspend fun detail(session: ShiguangSession, item: ShiguangActivity): ShiguangDetail {
                started.complete(Unit); release.await(); return super.detail(session, item)
            }
        }
        val service = ShiguangService(Account(), Auth(), api, backgroundScope)
        service.activities(); val page = service.state.value.activities
        val pending = launch { service.detail(page!!.items.single()) }; started.await()
        service.closeDetail(); release.complete(Unit); pending.join()
        assertNull(service.state.value.selected); assertNull(service.state.value.detail); assertEquals(page, service.state.value.activities)
    }
    @Test fun selectingLoadedTabKeepsItsQueryAndPageAndAccountChangeClearsUiState() = runTest {
        val account = Account(); val auth = Auth(); val api = Api(); val service = ShiguangService(account, auth, api, backgroundScope)
        service.activities("校园", 2)
        service.selectTab(ShiguangTab.MINE)
        service.mine(2)
        val pageCalls = api.pages

        service.selectTab(ShiguangTab.ACTIVITIES)

        assertEquals(ShiguangTab.ACTIVITIES, service.state.value.tab)
        assertEquals("校园", service.state.value.keyword)
        assertEquals(2, service.state.value.activities?.page)
        assertEquals(pageCalls, api.pages)
        assertEquals(1, auth.calls)

        val oldEpoch = service.state.value.accountEpoch
        account.change("syntheticB", 2); runCurrent()
        assertEquals(oldEpoch + 1, service.state.value.accountEpoch)
        assertEquals(ShiguangTab.ACTIVITIES, service.state.value.tab)
        assertEquals("", service.state.value.keyword)
        assertNull(service.state.value.activities)
        assertNull(service.state.value.participations)
        assertNull(service.state.value.summary)
    }

    @Test fun selectTabSynchronouslyRejectsAccountAndGenerationChangesBeforeCollectorRuns() = runTest {
        listOf("syntheticB" to 2L, "syntheticA" to 2L).forEach { (nextId, nextGeneration) ->
            val account = Account(); val auth = Auth(); val api = Api()
            val service = ShiguangService(account, auth, api, backgroundScope)
            service.activities("保留查询", 2)
            service.selectTab(ShiguangTab.MINE)
            service.mine(2)
            service.selectTab(ShiguangTab.ACTIVITIES)
            val oldEpoch = service.state.value.accountEpoch

            // Deliberately do not run the account combine collector before selectTab.
            account.change(nextId, nextGeneration)
            service.selectTab(ShiguangTab.MINE)

            assertEquals(oldEpoch + 1, service.state.value.accountEpoch)
            assertEquals(ShiguangTab.MINE, service.state.value.tab)
            assertEquals(1, service.state.value.participations?.page)
            assertEquals(2, auth.calls)
            assertEquals(nextGeneration, auth.generations.last())
            assertEquals(4, api.pages)
        }
    }

    @Test fun selectTabSynchronouslyClearsCachedPersonalResultsWhenAccountIsBlockedOrUnavailable() = runTest {
        listOf(false, true).forEach { unavailable ->
            val account = Account(); val auth = Auth(); val api = Api()
            val service = ShiguangService(account, auth, api, backgroundScope)
            service.activities("保留查询", 2)
            service.selectTab(ShiguangTab.MINE)
            service.mine(2)
            service.selectTab(ShiguangTab.ACTIVITIES)
            val oldEpoch = service.state.value.accountEpoch
            val oldPageCalls = api.pages

            // Keep the old reference published to exercise the status gate itself.
            if (unavailable) account.makeUnavailableWithoutClearingReference()
            else account.blockWithoutClearingReference()
            service.selectTab(ShiguangTab.MINE)

            assertEquals(oldEpoch + 1, service.state.value.accountEpoch)
            assertFalse(service.state.value.accountAvailable)
            assertFalse(service.state.value.identityVerified)
            assertNull(service.state.value.summary)
            assertNull(service.state.value.participations)
            assertEquals(1, auth.calls)
            assertEquals(oldPageCalls, api.pages)
        }
    }

    @Test fun mineDoesNotReusePersonalCacheWithoutCurrentlyVerifiedSession() = runTest {
        val account = Account(); val auth = Auth(); val api = Api()
        val service = ShiguangService(account, auth, api, backgroundScope)
        service.activities()
        service.selectTab(ShiguangTab.MINE)
        service.mine(2)
        service.selectTab(ShiguangTab.ACTIVITIES)
        val oldPersonalCalls = api.personal
        assertTrue(service.state.value.identityVerified)

        // Expire the session and fail re-authentication, leaving an old state cache but no session.
        api.expire = true
        auth.result = UpcShiguangAuthResult.Failure(UpcShiguangAuthFailure.NETWORK_UNAVAILABLE)
        service.refresh()
        assertEquals(ShiguangFailure.NETWORK, service.state.value.error)
        assertNotNull(service.state.value.summary)
        assertNotNull(service.state.value.participations)
        assertTrue(service.state.value.identityVerified)

        api.expire = false
        auth.result = UpcShiguangAuthResult.Success(emptyList(), identityVerified = false)
        service.selectTab(ShiguangTab.MINE)

        assertEquals(ShiguangFailure.IDENTITY_UNVERIFIED, service.state.value.error)
        assertFalse(service.state.value.identityVerified)
        assertNull(service.state.value.summary)
        assertNull(service.state.value.participations)
        assertEquals(oldPersonalCalls, api.personal)
        assertEquals(3, auth.calls)
    }
}
