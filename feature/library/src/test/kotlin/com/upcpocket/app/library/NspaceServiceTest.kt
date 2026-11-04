package com.upcpocket.app.library

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountIdentityCheck
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.data.nspace.NspaceRepository
import com.upcpocket.app.data.nspace.NspaceRequest
import com.upcpocket.app.data.nspace.NspaceResponse
import com.upcpocket.app.data.nspace.NspaceTransport
import com.upcpocket.app.data.nspace.NspaceUser
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NspaceServiceTest {

    @Test
    fun `matching account publishes the nspace token and queries use that token`() = runTest {
        val account = Account()
        val tokens = mutableListOf<String>()
        val repository = NspaceRepository(
            NspaceTransport { request ->
                tokens += request.token
                assertEquals("synthetic-nspace-token", request.token)
                NspaceResponse(
                    200,
                    """{"status":true,"code":200,"data":[{"id":"v1","name":"唐岛湾"}]}""",
                )
            },
        )
        val service = service(account, repository, SchoolAccountIdentityCheck.MATCH)

        assertEquals(NspaceLoginResult.Success, service.loginWithSavedAccount())
        assertEquals("synthetic-nspace-token", service.session.value?.token)
        assertEquals("v1", service.venues().single().id)
        assertEquals(listOf("synthetic-nspace-token"), tokens)
    }

    @Test
    fun `identity mismatch never publishes or queries`() = runTest {
        val account = Account()
        var queried = false
        val repository = NspaceRepository(
            NspaceTransport {
                queried = true
                error("身份不一致时不得查询")
            },
        )
        val service = service(account, repository, SchoolAccountIdentityCheck.MISMATCH)

        val result = service.loginWithSavedAccount()

        assertEquals(NspaceAuthFailure.IDENTITY_MISMATCH, (result as NspaceLoginResult.Failure).reason)
        assertNull(service.session.value)
        assertTrue(!queried)
    }

    @Test
    fun `sign out clears only the nspace memory session`() = runTest {
        val account = Account()
        val repository = NspaceRepository(
            NspaceTransport {
                NspaceResponse(200, """{"status":true,"code":200,"data":[]}""")
            },
        )
        val service = service(account, repository, SchoolAccountIdentityCheck.MATCH)
        service.loginWithSavedAccount()
        assertEquals("synthetic-nspace-token", service.session.value?.token)

        service.signOutForAccountChange()
        assertNull(service.session.value)
    }

    @Test
    fun `late result after account generation change is not published`() = runTest {
        val account = Account()
        val service = NspaceService(
            repository = NspaceRepository(
                NspaceTransport {
                    error("账号已变化时不得查询")
                },
            ),
            accountGate = account,
            accountReference = account,
            identityVerifier = SchoolAccountIdentityVerifier { _, _, _ ->
                SchoolAccountIdentityCheck.MATCH
            },
            authenticator = NspaceAccountAuthenticator { _, _ ->
                account.state.value = account.state.value.copy(
                    generation = AccountGeneration(4),
                )
                NspaceAccountAuthResult.Success(
                    token = "stale-token",
                    user = NspaceUser("synthetic-id", "synthetic-user", "合成用户"),
                )
            },
        )

        val result = service.loginWithSavedAccount()

        assertEquals(NspaceAuthFailure.ACCOUNT_CHANGED, (result as NspaceLoginResult.Failure).reason)
        assertNull(service.session.value)
    }

    @Test
    fun `room request keeps page and page size in a JSON body`() = runTest {
        var body: String? = null
        val repository = NspaceRepository(
            NspaceTransport { request ->
                body = request.body
                NspaceResponse(
                    200,
                    """{"status":true,"code":200,"data":{"currentPage":2,"totalPage":2,
                       "totalCount":7,"pageList":[]}}""",
                )
            },
        )

        val page = repository.rooms(
            token = "synthetic-nspace-token",
            venueId = "v1",
            roomTypeId = "t1",
            date = "2026-11-04",
            page = 2,
        )

        assertEquals(2, page.currentPage)
        assertTrue(body.orEmpty().contains("\"currentPage\":2"))
        assertTrue(body.orEmpty().contains("\"pageSize\":6"))
        assertTrue(body.orEmpty().contains("\"selectDate\":\"2026-11-04\""))
    }

    @Test
    fun `cancel calls the exact fixed path and requires a success envelope`() = runTest {
        var requestPath: String? = null
        var requestMethod: String? = null
        val repository = NspaceRepository(
            NspaceTransport { request ->
                requestPath = request.path
                requestMethod = request.method
                NspaceResponse(200, """{"status":true,"code":200,"data":null}""")
            },
        )

        repository.cancelReservation("synthetic-token", "id/1")

        assertEquals("GET", requestMethod)
        assertEquals("/spa/static/api/user/cancelBook/id%2F1", requestPath)
    }

    @Test
    fun `cancel requires a live session and sends the exact cancel path`() = runTest {
        val account = Account()
        var path: String? = null
        val repository = NspaceRepository(
            NspaceTransport { request ->
                path = request.path
                NspaceResponse(200, """{"status":true,"code":200,"data":null}""")
            },
        )
        val service = service(account, repository, SchoolAccountIdentityCheck.MATCH)
        service.loginWithSavedAccount()

        service.cancelReservation("reservation-1")
        assertEquals("/spa/static/api/user/cancelBook/reservation-1", path)

        service.signOut()
        val failed = runCatching { service.cancelReservation("reservation-2") }
        assertTrue(failed.exceptionOrNull() is com.upcpocket.app.data.nspace.NspaceSessionExpiredException)
    }

    @Test
    fun `native booking posts the exact public frontend path and fields`() = runTest {
        val account = Account()
        var path: String? = null
        var body: String? = null
        val repository = NspaceRepository(
            NspaceTransport { request ->
                path = request.path
                body = request.body
                NspaceResponse(200, """{"status":true,"code":200,"data":null}""")
            },
        )
        val service = service(account, repository, SchoolAccountIdentityCheck.MATCH)
        service.loginWithSavedAccount()
        service.book(
            com.upcpocket.app.data.nspace.NspaceBookingRequest(
                begin = 480,
                end = 540,
                onDate = "2026-11-05",
                roomId = "room-1",
                useType = "1",
                participants = "",
                filePath = "",
                source = "WEB",
                seatNo = "",
                teamId = 0,
                theme = "讨论",
                phone = null,
                outline = null,
                extraField = mapOf("college" to "计算机"),
            ),
        )

        assertEquals("/spa/static/api/book/saveReservation", path)
        assertTrue(body.orEmpty().contains("\"begin\":480"))
        assertTrue(body.orEmpty().contains("\"end\":540"))
        assertTrue(body.orEmpty().contains("\"roomId\":\"room-1\""))
        assertTrue(body.orEmpty().contains("\"theme\":\"讨论\""))
        assertTrue(body.orEmpty().contains("\"college\":\"计算机\""))
    }

    @Test
    fun `manual web token is exchanged and published only for the current account`() = runTest {
        val account = Account()
        val service = service(
            account,
            NspaceRepository(NspaceTransport { error("不应查询") }),
            SchoolAccountIdentityCheck.MATCH,
        ) { _, _, appToken ->
            assertEquals("synthetic-app-token", appToken)
            NspaceAccountAuthResult.Success(
                token = "synthetic-business-token",
                user = NspaceUser("synthetic-id", "synthetic-user", "合成用户"),
            )
        }

        val result = service.loginWithWebToken("synthetic-app-token")

        assertEquals(NspaceLoginResult.Success, result)
        assertEquals("synthetic-business-token", service.session.value?.token)
    }

    @Test
    fun `manual web token identity mismatch never publishes`() = runTest {
        val account = Account()
        val service = service(
            account,
            NspaceRepository(NspaceTransport { error("不应查询") }),
            SchoolAccountIdentityCheck.MISMATCH,
        ) { _, _, _ ->
            NspaceAccountAuthResult.Success(
                token = "synthetic-business-token",
                user = NspaceUser("other", "other-user", "别人"),
            )
        }

        val result = service.loginWithWebToken("synthetic-app-token")

        assertEquals(
            NspaceAuthFailure.IDENTITY_MISMATCH,
            (result as NspaceLoginResult.Failure).reason,
        )
        assertNull(service.session.value)
    }

    @Test
    fun `late manual web callback after sign out is not published`() = runTest {
        val account = Account()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val service = service(
            account,
            NspaceRepository(NspaceTransport { error("不应查询") }),
            SchoolAccountIdentityCheck.MATCH,
        ) { _, _, _ ->
            gate.await()
            NspaceAccountAuthResult.Success(
                token = "late-token",
                user = NspaceUser("synthetic-id", "synthetic-user", "合成用户"),
            )
        }
        val login = async {
            service.loginWithWebToken("synthetic-app-token")
        }
        kotlinx.coroutines.yield()
        service.signOut()
        gate.complete(Unit)

        assertEquals(
            NspaceAuthFailure.ACCOUNT_CHANGED,
            (login.await() as NspaceLoginResult.Failure).reason,
        )
        assertNull(service.session.value)
    }

    @Test
    fun `manual web login is unavailable without an exchanger`() = runTest {
        val service = service(
            Account(),
            NspaceRepository(NspaceTransport { error("不应查询") }),
            SchoolAccountIdentityCheck.MATCH,
        )
        val result = service.loginWithWebToken("synthetic-app-token")
        assertEquals(
            NspaceAuthFailure.PROTOCOL_ERROR,
            (result as NspaceLoginResult.Failure).reason,
        )
    }

    private fun service(
        account: Account,
        repository: NspaceRepository,
        identity: SchoolAccountIdentityCheck,
        webExchanger: NspaceWebTokenExchanger? = null,
    ): NspaceService = NspaceService(
        repository = repository,
        accountGate = account,
        accountReference = account,
        identityVerifier = SchoolAccountIdentityVerifier { _, _, _ -> identity },
        authenticator = NspaceAccountAuthenticator { _, _ ->
            NspaceAccountAuthResult.Success(
                token = "synthetic-nspace-token",
                user = NspaceUser("synthetic-id", "synthetic-user", "合成用户"),
            )
        },
        webTokenExchanger = webExchanger,
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
}
