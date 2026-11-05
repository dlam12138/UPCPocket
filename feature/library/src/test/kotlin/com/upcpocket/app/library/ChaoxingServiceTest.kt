package com.upcpocket.app.library

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountIdentityCheck
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.data.chaoxing.ChaoxingHttpRequest
import com.upcpocket.app.data.chaoxing.ChaoxingHttpResponse
import com.upcpocket.app.data.chaoxing.ChaoxingRepository
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingTransport
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaoxingServiceTest {

    @Test
    fun `matching account publishes cookies and reads native rooms`() = runTest {
        val account = Account()
        val requests = mutableListOf<ChaoxingHttpRequest>()
        val repository = repository { request ->
            requests += request
            when {
                request.url.contains("/front/third/apps/seat/index") -> html(
                    """var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""",
                )
                request.url.contains("/room/list") -> json(
                    """{"success":true,"data":{"totalRow":1,"totalPage":1,"seatRoomList":[
                       {"id":8548,"firstLevelName":"东区图书馆","secondLevelName":"三楼",
                        "thirdLevelName":"A区","capacity":48,
                        "seatSpecialTime":{"wedStartTime":"08:00","wedEndTime":"21:30"}}]}}""",
                )
                else -> error("unexpected ${request.url}")
            }
        }
        val service = service(account, repository, SchoolAccountIdentityCheck.MATCH)

        assertEquals(ChaoxingLoginResult.Success, service.loginWithWebCookies(COOKIE))
        assertEquals(COOKIE, service.session.value?.cookieHeader)
        assertEquals("20260001", service.session.value?.user?.sno)
        val rooms = service.seatRooms(ChaoxingSeatKind.SEAT, "2026-11-04")
        assertEquals(8548, rooms.rooms.single().id)
        assertTrue(requests.all { it.cookieHeader == COOKIE })
    }

    @Test
    fun `identity mismatch never publishes`() = runTest {
        val account = Account()
        val repository = repository { request ->
            assertTrue(request.url.contains("/front/third/apps/seat/index"))
            html("""var userLoginInfo = {"userInfo":{"sno":"20260002"}}||{}""")
        }
        val service = service(account, repository, SchoolAccountIdentityCheck.MISMATCH)

        val result = service.loginWithWebCookies(COOKIE)

        assertEquals(
            ChaoxingAuthFailure.IDENTITY_MISMATCH,
            (result as ChaoxingLoginResult.Failure).reason,
        )
        assertNull(service.session.value)
    }

    @Test
    fun `late identity result after generation change is not published`() = runTest {
        val account = Account()
        val gate = CountDownLatch(1)
        val repository = repository {
            gate.await()
            html("""var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""")
        }
        val service = service(account, repository, SchoolAccountIdentityCheck.MATCH)
        val login = async { service.loginWithWebCookies(COOKIE) }
        yield()
        account.state.value = account.state.value.copy(generation = AccountGeneration(4))
        gate.countDown()

        assertEquals(
            ChaoxingAuthFailure.ACCOUNT_CHANGED,
            (login.await() as ChaoxingLoginResult.Failure).reason,
        )
        assertNull(service.session.value)
    }

    @Test
    fun `invalid cookie header is rejected before any network request`() = runTest {
        var called = false
        val service = service(
            Account(),
            repository {
                called = true
                error("不应请求")
            },
            SchoolAccountIdentityCheck.MATCH,
        )
        val result = service.loginWithWebCookies("oa_uid=1\nSet-Cookie: bad")
        assertEquals(
            ChaoxingAuthFailure.INVALID_SESSION,
            (result as ChaoxingLoginResult.Failure).reason,
        )
        assertTrue(!called)
    }

    @Test
    fun `sign out clears the memory session and web cookies`() = runTest {
        val account = Account()
        var cleared = 0
        val service = ChaoxingService(
            repository = repository {
                html("""var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""")
            },
            accountGate = account,
            accountReference = account,
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                SchoolAccountIdentityCheck.MATCH
            },
            authenticator = noAutoAuth,
            clearCookies = {
                cleared++
                true
            },
        )
        service.loginWithWebCookies(COOKIE)
        assertEquals(COOKIE, service.session.value?.cookieHeader)

        assertTrue(service.signOutForAccountChange())
        assertNull(service.session.value)
        assertTrue(cleared >= 1)
    }

    @Test
    fun `cleanup gate blocks old callback and new login until clear completes`() = runTest {
        val account = Account()
        val clearStarted = CompletableDeferred<Unit>()
        val releaseClear = CompletableDeferred<Unit>()
        var clearCount = 0
        val service = ChaoxingService(
            repository = repository {
                html("""var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""")
            },
            accountGate = account,
            accountReference = account,
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                SchoolAccountIdentityCheck.MATCH
            },
            authenticator = noAutoAuth,
            clearCookies = {
                clearCount++
                clearStarted.complete(Unit)
                releaseClear.await()
                true
            },
        )
        assertEquals(ChaoxingLoginResult.Success, service.loginWithWebCookies(COOKIE))
        val oldPage = requireNotNull(service.beginWebLogin())
        val signOut = async { service.signOut() }
        clearStarted.await()

        assertEquals(
            ChaoxingAuthFailure.CLEANUP_IN_PROGRESS,
            (service.loginWithWebCookies(oldPage, COOKIE) as ChaoxingLoginResult.Failure).reason,
        )
        assertNull(service.beginWebLogin())
        assertNull(service.session.value)

        releaseClear.complete(Unit)
        assertEquals(ChaoxingCleanupResult.Success, signOut.await())
        assertEquals(1, clearCount)
        assertEquals(ChaoxingCleanupState.IDLE, service.cleanupState.value)

        assertEquals(
            ChaoxingAuthFailure.STALE_WEB_ENTRY,
            (service.loginWithWebCookies(oldPage, COOKIE) as ChaoxingLoginResult.Failure).reason,
        )
        val newPage = requireNotNull(service.beginWebLogin())
        assertEquals(ChaoxingLoginResult.Success, service.loginWithWebCookies(newPage, COOKIE))
        assertEquals(COOKIE, service.session.value?.cookieHeader)
    }

    @Test
    fun `concurrent sign-outs share one cleanup and cannot clear a later session`() = runTest {
        val account = Account()
        val clearStarted = CompletableDeferred<Unit>()
        val releaseClear = CompletableDeferred<Unit>()
        var clearCount = 0
        val service = ChaoxingService(
            repository = repository {
                html("""var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""")
            },
            accountGate = account,
            accountReference = account,
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                SchoolAccountIdentityCheck.MATCH
            },
            authenticator = noAutoAuth,
            clearCookies = {
                clearCount++
                clearStarted.complete(Unit)
                releaseClear.await()
                true
            },
        )
        service.loginWithWebCookies(COOKIE)
        val first = launch { service.signOut() }
        clearStarted.await()
        val second = launch { service.signOut() }
        yield()
        releaseClear.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, clearCount)

        val page = requireNotNull(service.beginWebLogin())
        assertEquals(ChaoxingLoginResult.Success, service.loginWithWebCookies(page, COOKIE))
        assertEquals(COOKIE, service.session.value?.cookieHeader)
        assertEquals(ChaoxingCleanupState.IDLE, service.cleanupState.value)
    }

    @Test
    fun `cleanup failure stays visible and blocks login until retry succeeds`() = runTest {
        val account = Account()
        var clearSucceeds = false
        var clearCount = 0
        val service = ChaoxingService(
            repository = repository {
                html("""var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""")
            },
            accountGate = account,
            accountReference = account,
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                SchoolAccountIdentityCheck.MATCH
            },
            authenticator = noAutoAuth,
            clearCookies = {
                clearCount++
                clearSucceeds
            },
        )
        service.loginWithWebCookies(COOKIE)
        val oldPage = requireNotNull(service.beginWebLogin())

        assertTrue(service.signOut() is ChaoxingCleanupResult.Failed)
        assertEquals(ChaoxingCleanupState.FAILED, service.cleanupState.value)
        assertNull(service.session.value)
        assertNull(service.beginWebLogin())
        assertEquals(
            ChaoxingAuthFailure.COOKIE_CLEANUP_FAILED,
            (service.loginWithWebCookies(oldPage, COOKIE) as ChaoxingLoginResult.Failure).reason,
        )
        assertEquals(1, clearCount)

        clearSucceeds = true
        assertTrue(service.retryCookieCleanup())
        assertEquals(ChaoxingCleanupState.IDLE, service.cleanupState.value)
        val page = requireNotNull(service.beginWebLogin())
        assertEquals(ChaoxingLoginResult.Success, service.loginWithWebCookies(page, COOKIE))
        assertEquals(2, clearCount)
    }

    @Test
    fun `account change reports a failed cookie cleanup instead of success`() = runTest {
        val account = Account()
        val service = ChaoxingService(
            repository = repository {
                html("""var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""")
            },
            accountGate = account,
            accountReference = account,
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                SchoolAccountIdentityCheck.MATCH
            },
            authenticator = noAutoAuth,
            clearCookies = { false },
        )
        service.loginWithWebCookies(COOKIE)

        assertFalse(service.signOutForAccountChange())
        assertEquals(ChaoxingCleanupState.FAILED, service.cleanupState.value)
        assertNull(service.session.value)
    }

    @Test
    fun `saved account auto login publishes and invalidates an old web entry`() = runTest {
        val account = Account()
        val service = service(
            account,
            repository {
                html("""var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""")
            },
            SchoolAccountIdentityCheck.MATCH,
            ChaoxingAccountAuthenticator { _, _ ->
                ChaoxingAccountAuthResult.Success(COOKIE)
            },
        )
        val oldEntry = requireNotNull(service.beginWebLogin())

        assertEquals(ChaoxingLoginResult.Success, service.loginWithSavedAccount())
        assertEquals(COOKIE, service.session.value?.cookieHeader)
        assertEquals(
            ChaoxingAuthFailure.STALE_WEB_ENTRY,
            (service.loginWithWebCookies(oldEntry, "oa_uid=old") as ChaoxingLoginResult.Failure).reason,
        )
    }

    @Test
    fun `auto interaction required keeps the existing session`() = runTest {
        val account = Account()
        val service = service(
            account,
            repository {
                html("""var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""")
            },
            SchoolAccountIdentityCheck.MATCH,
            ChaoxingAccountAuthenticator { _, _ ->
                ChaoxingAccountAuthResult.Failure(
                    ChaoxingAuthFailure.INTERACTION_REQUIRED,
                    "https://cas.upc.edu.cn/cas/login?service=synthetic",
                )
            },
        )
        assertEquals(ChaoxingLoginResult.Success, service.loginWithWebCookies(COOKIE))

        val result = service.loginWithSavedAccount()
        assertEquals(
            ChaoxingAuthFailure.INTERACTION_REQUIRED,
            (result as ChaoxingLoginResult.Failure).reason,
        )
        assertEquals(COOKIE, service.session.value?.cookieHeader)
    }

    @Test
    fun `late auto result after generation change is not published`() = runTest {
        val account = Account()
        val gate = CompletableDeferred<ChaoxingAccountAuthResult>()
        val service = service(
            account,
            repository {
                html("""var userLoginInfo = {"userInfo":{"sno":"20260001"}}||{}""")
            },
            SchoolAccountIdentityCheck.MATCH,
            ChaoxingAccountAuthenticator { _, _ -> gate.await() },
        )
        val login = async { service.loginWithSavedAccount() }
        yield()
        account.state.value = account.state.value.copy(generation = AccountGeneration(4))
        gate.complete(ChaoxingAccountAuthResult.Success(COOKIE))

        assertEquals(
            ChaoxingAuthFailure.ACCOUNT_CHANGED,
            (login.await() as ChaoxingLoginResult.Failure).reason,
        )
        assertNull(service.session.value)
    }

    @Test
    fun `auto identity mismatch never publishes`() = runTest {
        val service = service(
            Account(),
            repository {
                html("""var userLoginInfo = {"userInfo":{"sno":"20260002"}}||{}""")
            },
            SchoolAccountIdentityCheck.MISMATCH,
            ChaoxingAccountAuthenticator { _, _ ->
                ChaoxingAccountAuthResult.Success(COOKIE)
            },
        )

        val result = service.loginWithSavedAccount()
        assertEquals(
            ChaoxingAuthFailure.IDENTITY_MISMATCH,
            (result as ChaoxingLoginResult.Failure).reason,
        )
        assertNull(service.session.value)
    }

    @Test
    fun `seat and seatengine used-seat queries keep their real parameter difference`() = runTest {
        val urls = mutableListOf<String>()
        val repository = repository { request ->
            urls += request.url
            json("""{"success":true,"data":{"seatReserves":[]}}""")
        }

        repository.usedSeatNums(
            ChaoxingSeatKind.SEAT,
            COOKIE,
            8548,
            600,
            660,
            "2026-11-04",
        )
        repository.usedSeatNums(
            ChaoxingSeatKind.SEATENGINE,
            COOKIE,
            12817,
            600,
            660,
            "2026-11-04",
        )

        assertTrue(urls[0].contains("fidEnc="))
        assertTrue(!urls[0].contains("seatId="))
        assertTrue(urls[1].contains("seatId=4258"))
        assertTrue(!urls[1].contains("fidEnc="))
    }

    private fun repository(
        block: (ChaoxingHttpRequest) -> ChaoxingHttpResponse,
    ): ChaoxingRepository = ChaoxingRepository(
        ChaoxingTransport { request -> block(request) },
    )

    private fun json(body: String): ChaoxingHttpResponse = ChaoxingHttpResponse(200, body)

    private fun html(body: String): ChaoxingHttpResponse =
        ChaoxingHttpResponse(200, "<html><script>$body</script></html>")

    private fun service(
        account: Account,
        repository: ChaoxingRepository,
        identity: SchoolAccountIdentityCheck,
        authenticator: ChaoxingAccountAuthenticator = noAutoAuth,
    ): ChaoxingService = ChaoxingService(
        repository = repository,
        accountGate = account,
        accountReference = account,
        identityVerifier = SchoolAccountIdentityVerifier { _, _, _ -> identity },
        authenticator = authenticator,
        clearCookies = { true },
    )

    private class Account : SchoolAccountReferenceReader {
        override val state = MutableStateFlow(
            SchoolAccountState(
                status = SchoolAccountStatus.SAVED_UNVERIFIED,
                generation = AccountGeneration(3),
            ),
        )
        override val accountReference = MutableStateFlow(
            SchoolAccountReference(
                accountId = com.upcpocket.app.account.api.SchoolAccountId("acct-synthetic"),
                credentialGeneration = AccountGeneration(3),
            ),
        )
    }

    private companion object {
        const val COOKIE = "oa_uid=synthetic; oa_deptid=e32f8fbde51f9dba"
        val noAutoAuth = ChaoxingAccountAuthenticator { _, _ ->
            ChaoxingAccountAuthResult.Failure(ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE)
        }
    }
}
