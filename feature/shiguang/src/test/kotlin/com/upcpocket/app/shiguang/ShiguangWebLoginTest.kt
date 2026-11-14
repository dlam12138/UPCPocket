package com.upcpocket.app.shiguang

import com.upcpocket.app.account.api.*
import com.upcpocket.app.upcauth.*
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShiguangWebLoginTest {
    private class Account : SchoolAccountReferenceReader {
        override val state = MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)))
        override val accountReference = MutableStateFlow<SchoolAccountReference?>(SchoolAccountReference(SchoolAccountId("synthetic-A"), AccountGeneration(1)))
        fun change() {
            state.value = SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(2))
            accountReference.value = SchoolAccountReference(SchoolAccountId("synthetic-B"), AccountGeneration(2))
        }
    }
    private class Auth : ShiguangAuthenticator, ShiguangInteractiveAuthenticator {
        var nativeCalls = 0; var webCalls = 0
        var native: UpcShiguangAuthResult = UpcShiguangAuthResult.Failure(UpcShiguangAuthFailure.INTERACTION_REQUIRED)
        var web: UpcShiguangAuthResult = UpcShiguangAuthResult.Success(emptyList(), true)
        var wait: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override val entryUrl = UpcShiguangWebLoginPolicy.entryUrl
        override fun inspectWebNavigation(url: String, method: String, isMainFrame: Boolean, kind: ShiguangWebRequestKind, operation: DiagnosticOperation?) = UpcShiguangWebLoginPolicy.inspect(url, method, isMainFrame, kind)
        override suspend fun authenticate(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long): UpcShiguangAuthResult { nativeCalls++; return native }
        override suspend fun completeWebLoginResult(expectedAccountId: SchoolAccountId, expectedCredentialGeneration: Long, callbackUrl: String, operation: DiagnosticOperation?): UpcShiguangAuthResult {
            webCalls++; started.complete(Unit); wait?.await(); return web
        }
    }
    private class Api : ShiguangDataSource {
        val reads = mutableListOf<String>()
        var expire = false
        override suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean): ShiguangPage {
            reads += "page:$page:$keyword:$mine"
            if (expire) throw ShiguangException(ShiguangFailure.AUTH_REQUIRED)
            return ShiguangPage(page, 9, emptyList())
        }
        override suspend fun summary(session: ShiguangSession): ShiguangSummary { reads += "summary"; return ShiguangSummary(emptyList()) }
        override suspend fun detail(session: ShiguangSession, item: ShiguangActivity): ShiguangDetail { reads += "detail:${item.id}"; return ShiguangDetail(item.title, null, emptyMap()) }
    }
    @Test fun manualLoginWorksWithoutNativeSubmissionAndDuplicateCallbackIsNotExchanged() = runTest {
        val auth = Auth(); val api = Api(); val service = ShiguangService(Account(), auth, api, backgroundScope, auth)
        val attempt = service.beginInteractiveLogin()
        service.completeInteractiveLogin(attempt.id, CALLBACK)
        assertEquals(0, auth.nativeCalls); assertEquals(1, auth.webCalls)
        assertEquals(listOf("page:1::false"), api.reads)
        assertNull(service.state.value.interactiveAttempt)
        assertFalse(service.state.value.interactionRequired)
        try { service.completeInteractiveLogin(attempt.id, CALLBACK); fail() } catch (_: ShiguangException) { }
        assertEquals(1, auth.webCalls)
    }
    @Test fun challengeResumesExactSearchAndPageOnce() = runTest {
        val auth = Auth(); val api = Api(); val service = ShiguangService(Account(), auth, api, backgroundScope, auth)
        service.activities("合成筛选", 7)
        assertTrue(service.state.value.interactionRequired)
        val attempt = service.beginInteractiveLogin()
        service.completeInteractiveLogin(attempt.id, CALLBACK)
        assertEquals(1, auth.nativeCalls); assertEquals(1, auth.webCalls)
        assertEquals(listOf("page:7:合成筛选:false"), api.reads)
        assertEquals(7, service.state.value.activities!!.page)
    }
    @Test fun challengeResumesMineAndDetailWithoutChangingSelection() = runTest {
        val auth = Auth(); val api = Api(); val service = ShiguangService(Account(), auth, api, backgroundScope, auth)
        service.mine(4)
        service.completeInteractiveLogin(service.beginInteractiveLogin().id, CALLBACK)
        assertEquals(listOf("summary", "page:4::true"), api.reads)
        service.invalidateForAccountChange()
        val item = ShiguangActivity("456", ShiguangKind.ACTIVITY, "合成详情")
        service.detail(item)
        service.completeInteractiveLogin(service.beginInteractiveLogin().id, CALLBACK)
        assertEquals("detail:456", api.reads.last())
        assertEquals(item, service.state.value.selected)
        assertEquals(item.title, service.state.value.detail!!.title)
    }
    @Test fun detailChallengeCanUseScreenLoginActionAndResumeThatDetail() = runTest {
        val auth = Auth(); val api = Api(); val service = ShiguangService(Account(), auth, api, backgroundScope, auth)
        val item = ShiguangActivity("789", ShiguangKind.ACTIVITY, "合成详情验证")

        service.detail(item)
        assertEquals(ShiguangFailure.INTERACTION_REQUIRED, service.state.value.error)
        assertEquals(item, service.state.value.selected)

        service.openInteractiveLogin()
        val attempt = service.state.value.interactiveAttempt
        assertNotNull(attempt)
        assertEquals(item, service.state.value.selected)
        service.completeInteractiveLogin(attempt!!.id, CALLBACK)

        assertEquals("detail:789", api.reads.single())
        assertEquals(item, service.state.value.selected)
        assertEquals(item.title, service.state.value.detail?.title)
        assertNull(service.state.value.error)
    }
    @Test fun expiredReadAfterWebDoesNotSubmitNativePasswordRecovery() = runTest {
        val auth = Auth(); val api = Api().apply { expire = true }; val service = ShiguangService(Account(), auth, api, backgroundScope, auth)
        try { service.completeInteractiveLogin(service.beginInteractiveLogin().id, CALLBACK); fail() } catch (e: ShiguangException) { assertEquals(ShiguangFailure.AUTH_REQUIRED, e.reason) }
        assertEquals(0, auth.nativeCalls); assertEquals(1, auth.webCalls); assertEquals(1, api.reads.size)
        assertEquals(ShiguangFailure.AUTH_REQUIRED, service.state.value.error)
        assertNull(service.state.value.interactiveAttempt)
    }
    @Test fun cancelDuringExchangeRejectsLateSessionAndRead() = runTest {
        val auth = Auth().apply { wait = CompletableDeferred() }; val api = Api(); val service = ShiguangService(Account(), auth, api, backgroundScope, auth)
        val attempt = service.beginInteractiveLogin()
        val job = launch { try { service.completeInteractiveLogin(attempt.id, CALLBACK); fail() } catch (_: ShiguangException) { } }
        auth.started.await(); service.cancelInteractiveLogin(attempt.id)
        auth.wait!!.complete(Unit); job.join()
        assertTrue(api.reads.isEmpty()); assertFalse(service.state.value.identityVerified); assertFalse(service.state.value.busy)
        assertNull(service.state.value.interactiveAttempt)
    }
    @Test fun accountChangeDuringExchangeRejectsLateSessionAndPersonalRead() = runTest {
        val account = Account(); val auth = Auth().apply { wait = CompletableDeferred() }; val api = Api()
        val service = ShiguangService(account, auth, api, backgroundScope, auth)
        service.mine(2)
        val attempt = service.beginInteractiveLogin()
        val job = launch { try { service.completeInteractiveLogin(attempt.id, CALLBACK); fail() } catch (_: ShiguangException) { } }
        auth.started.await(); account.change(); runCurrent()
        auth.wait!!.complete(Unit); job.join()
        assertTrue(api.reads.isEmpty()); assertNull(service.state.value.summary); assertNull(service.state.value.participations)
    }
    @Test fun failedBrowserDestructionRetainsCleanupBarrierUntilRealRetrySucceeds() = runTest {
        val auth = Auth(); val service = ShiguangService(Account(), auth, Api(), backgroundScope, auth)
        val attempt = service.beginInteractiveLogin()
        var stopped = false; var retries = 0
        val lease = object : ShiguangWebViewLease {
            override fun dispose() { }
            override suspend fun disposeAndAwait(): Boolean { retries++; return stopped }
        }
        assertNotNull(service.registerInteractiveWebView(attempt.id, lease))
        assertFalse(service.invalidateForAccountChange())
        try { service.beginInteractiveLogin(); fail() } catch (e: ShiguangException) { assertEquals(ShiguangFailure.CLEANUP_PENDING, e.reason) }
        stopped = true
        assertTrue(service.invalidateForAccountChange()); assertEquals(2, retries)
        assertNotNull(service.beginInteractiveLogin())
    }
    @Test fun identityMismatchFromWebClearsExistingPersonalResultsAndSession() = runTest {
        val auth = Auth().apply { native = UpcShiguangAuthResult.Success(emptyList(), true) }; val api = Api()
        val service = ShiguangService(Account(), auth, api, backgroundScope, auth)
        service.mine(); assertNotNull(service.state.value.summary)
        auth.web = UpcShiguangAuthResult.Failure(UpcShiguangAuthFailure.IDENTITY_MISMATCH)
        try { service.completeInteractiveLogin(service.beginInteractiveLogin().id, CALLBACK); fail() } catch (_: ShiguangException) { }
        assertNull(service.state.value.summary); assertNull(service.state.value.participations); assertFalse(service.state.value.identityVerified)
        assertEquals(2, api.reads.size)
        auth.native = UpcShiguangAuthResult.Failure(UpcShiguangAuthFailure.INTERACTION_REQUIRED)
        service.activities(); assertEquals(2, auth.nativeCalls)
    }
    @Test fun reloadFailureAndDelayedOldReleaseCannotDropCurrentBrowserOwnership() {
        val a = Any(); val b = Any(); val destroyed = mutableListOf<Any>(); var failDestroy = true; var unregisters = 0
        val ownership = ShiguangWebViewOwnership<Any> { if (it === a && failDestroy) error("synthetic"); destroyed += it }
        assertTrue(ownership.attach(a)); ownership.setRegistration(AutoCloseable { unregisters++ })
        try { ownership.releaseForReload(); fail() } catch (_: IllegalStateException) { }
        assertFalse(ownership.attach(b)); assertTrue(destroyed.contains(b)); assertEquals(0, unregisters)
        failDestroy = false; ownership.releaseForReload(); assertEquals(1, unregisters)
        assertTrue(ownership.attach(b)); ownership.setRegistration(AutoCloseable { unregisters++ })
        ownership.release(a); assertEquals(1, unregisters)
        ownership.close(); assertEquals(2, unregisters)
    }
    private companion object { const val CALLBACK = "https://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-synthetic" }
}
