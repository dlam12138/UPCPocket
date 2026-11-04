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
import com.upcpocket.app.data.chaoxing.ChaoxingTenant
import com.upcpocket.app.data.chaoxing.ChaoxingTransport
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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

    @Test
    fun `reserve list reads official page context before posting the tenant list`() = runTest {
        val forms = mutableListOf<String>()
        val repository = repository { request ->
            when {
                request.url.contains("/front/web/apps/reservepc/index") -> html(
                    """var reserveId = '13492'; var reserveIdEnc = '0123456789abcdef';""",
                )
                request.url.contains("/data/apps/reserve/item/list/time") -> {
                    forms += request.formBody.orEmpty()
                    json(
                        """{"success":true,"data":{"reserveItemList":[
                           {"id":25504,"name":"403","minUserNum":1,"maxUserNum":20,
                            "reserveNum":1,"details":"","reserveIntervalList":[]}
                        ]}}""",
                    )
                }
                else -> error("unexpected ${request.url}")
            }
        }

        val rooms = repository.reserveRooms(ChaoxingTenant.GUZHENKOU, COOKIE)

        assertEquals("403", rooms.rooms.single().name)
        assertTrue(forms.single().contains("reserveIdEnc=0123456789abcdef"))
        assertTrue(forms.single().contains("reserveId=13492"))
        assertTrue(!forms.single().contains("uid="))
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
    ): ChaoxingService = ChaoxingService(
        repository = repository,
        accountGate = account,
        accountReference = account,
        identityVerifier = SchoolAccountIdentityVerifier { _, _, _ -> identity },
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
    }
}
