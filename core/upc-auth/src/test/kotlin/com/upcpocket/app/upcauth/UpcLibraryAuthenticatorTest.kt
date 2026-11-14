package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(RestrictedSchoolCredentialUse::class)
class UpcLibraryAuthenticatorTest {

    private val accountId = SchoolAccountId("acct_synthetic_library")
    private val credential = SchoolLoginCredential(
        accountId = accountId,
        credentialGeneration = AccountGeneration(4),
        loginName = "synthetic-user",
        password = "synthetic-password".toCharArray(),
    )

    @Test
    fun `normal library CAS exchanges app token and verifies the returned user`() = runTest {
        val transport = LibraryFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcLibraryAuthEndpoints.ENTRY_URL ->
                    response(302, mapOf("Location" to casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(action = casUrl()))
                request.method == "POST" && request.url == casUrl() -> {
                    assertTrue(request.body.orEmpty().contains("password=synthetic-password"))
                    response(302, mapOf("Location" to callback()))
                }
                request.method == "GET" && request.url == callback() ->
                    response(302, mapOf("Location" to appTokenPage()))
                request.method == "POST" && request.url.startsWith("https://libspace.upc.edu.cn/rest/ssoAuth") -> {
                    assertEquals("synthetic-app-token", request.headers["Authorization"])
                    assertTrue(request.headers["X-hmac-request-key"].orEmpty().matches(Regex("[0-9a-f]{64}")))
                    response(
                        200,
                        mapOf("Content-Type" to "application/json"),
                        """{"status":"success","code":"0","data":{"token":"synthetic-seat-token"}}""",
                    )
                }
                request.method == "GET" && request.url.startsWith("https://libspace.upc.edu.cn/rest/v2/user") -> {
                    assertEquals("synthetic-seat-token", request.headers["Authorization"])
                    response(
                        200,
                        mapOf("Content-Type" to "application/json"),
                        """{"status":"success","code":"0","data":{"id":"synthetic-id","username":"synthetic-user","name":"合成用户"}}""",
                    )
                }
                else -> error("unexpected request ${request.method} ${request.url}")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 4)

        val success = result as UpcLibraryAuthResult.Success
        assertEquals("synthetic-seat-token", success.seatToken)
        assertEquals("synthetic-id", success.user.id)
        assertEquals("synthetic-user", success.user.username)
        assertEquals(2, transport.posts)
    }

    @Test
    fun `wrong password is submitted once and not retried`() = runTest {
        val transport = LibraryFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcLibraryAuthEndpoints.ENTRY_URL ->
                    response(302, mapOf("Location" to casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(action = casUrl()))
                request.method == "POST" -> htmlResponse(
                    formPage(action = casUrl()) +
                        """<div id="errorDiv">用户名或密码错误</div>""",
                )
                else -> error("不得重试密码")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 4)
        assertEquals(
            UpcLibraryAuthFailure.INVALID_CREDENTIALS,
            (result as UpcLibraryAuthResult.Failure).reason,
        )
        assertEquals(1, transport.posts)
    }

    @Test
    fun `captcha never submits the password`() = runTest {
        val transport = LibraryFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcLibraryAuthEndpoints.ENTRY_URL ->
                    response(302, mapOf("Location" to casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(action = casUrl(), captcha = true))
                else -> error("验证码页面不得提交密码")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 4)
        assertEquals(
            UpcLibraryAuthFailure.INTERACTION_REQUIRED,
            (result as UpcLibraryAuthResult.Failure).reason,
        )
        assertEquals(0, transport.posts)
    }

    @Test
    fun `hash router fragment token is accepted`() = runTest {
        val page = "https://libspace.upc.edu.cn/libseat/#/login?token=synthetic-app-token"
        val result = authenticator(successfulTransport("synthetic-user", page))
            .authenticate(accountId, 4)
        assertEquals(
            "synthetic-seat-token",
            (result as UpcLibraryAuthResult.Success).seatToken,
        )
    }

    @Test
    fun `identity mismatch is not saved as a session`() = runTest {
        val transport = successfulTransport(username = "another-user")
        val result = authenticator(transport).authenticate(accountId, 4)
        val failure = result as UpcLibraryAuthResult.Failure
        assertEquals(UpcLibraryAuthFailure.IDENTITY_MISMATCH, failure.reason)
        assertFalse(failure.interactionUrl != null)
    }

    @Test
    fun `external ticket callback is rejected before following`() = runTest {
        val transport = LibraryFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcLibraryAuthEndpoints.ENTRY_URL ->
                    response(302, mapOf("Location" to casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(action = casUrl()))
                request.method == "POST" -> response(
                    302,
                    mapOf(
                        "Location" to
                            "https://evil.invalid/remote/static/casAuth/getServiceByVerifyTicket/casLogin" +
                            "?ticket=ST-synthetic",
                    ),
                )
                else -> error("不得跟随外部回调")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 4)
        assertEquals(
            UpcLibraryAuthFailure.PROTOCOL_ERROR,
            (result as UpcLibraryAuthResult.Failure).reason,
        )
    }

    @Test
    fun `account mismatch does not start network`() = runTest {
        val transport = LibraryFakeTransport { error("账号不一致时不得联网") }
        val other = SchoolAccountId("acct_another_library")
        val result = authenticator(transport).authenticate(other, 4)
        assertEquals(
            UpcLibraryAuthFailure.ACCOUNT_CHANGED,
            (result as UpcLibraryAuthResult.Failure).reason,
        )
    }

    @Test
    fun `tls and network failures stay distinct`() = runTest {
        val tls = LibraryFakeTransport { throw UpcAuthTransportException(UpcAuthTransportFailure.TLS) }
        assertEquals(
            UpcLibraryAuthFailure.TLS_ERROR,
            (authenticator(tls).authenticate(accountId, 4) as UpcLibraryAuthResult.Failure).reason,
        )
        val network = LibraryFakeTransport {
            throw UpcAuthTransportException(UpcAuthTransportFailure.NETWORK)
        }
        assertEquals(
            UpcLibraryAuthFailure.NETWORK_UNAVAILABLE,
            (authenticator(network).authenticate(accountId, 4) as UpcLibraryAuthResult.Failure).reason,
        )
    }

    private fun successfulTransport(
        username: String,
        appTokenPage: String = appTokenPage(),
    ): LibraryFakeTransport = LibraryFakeTransport { request ->
        when {
            request.method == "GET" && request.url == UpcLibraryAuthEndpoints.ENTRY_URL ->
                response(302, mapOf("Location" to casUrl()))
            request.method == "GET" && request.url == casUrl() ->
                htmlResponse(formPage(action = casUrl()))
            request.method == "POST" && request.url == casUrl() ->
                response(302, mapOf("Location" to callback()))
            request.method == "GET" && request.url == callback() ->
                response(302, mapOf("Location" to appTokenPage))
            request.method == "POST" && request.url.startsWith("https://libspace.upc.edu.cn/rest/ssoAuth") ->
                response(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    """{"status":"success","code":"0","data":{"token":"synthetic-seat-token"}}""",
                )
            request.method == "GET" && request.url.startsWith("https://libspace.upc.edu.cn/rest/v2/user") ->
                response(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    """{"status":"success","code":"0","data":{"id":"synthetic-id","username":"$username","name":"合成用户"}}""",
                )
            else -> error("unexpected request")
        }
    }

    private fun authenticator(transport: UpcAuthTransport): UpcLibraryAuthenticator =
        UpcLibraryAuthenticator(
            credentials = object : SchoolCredentialAccess {
                override suspend fun <T> withLoginCredential(
                    block: suspend (SchoolLoginCredential) -> T,
                ): SchoolCredentialUseResult<T> {
                    val copy = SchoolLoginCredential(
                        accountId = credential.accountId,
                        credentialGeneration = credential.credentialGeneration,
                        loginName = credential.loginName,
                        password = "synthetic-password".toCharArray(),
                    )
                    return try {
                        SchoolCredentialUseResult.Success(block(copy))
                    } finally {
                        copy.close()
                    }
                }
            },
            transport = transport,
        )

    private fun casUrl(): String =
        "https://cas.upc.edu.cn/cas/login?service=" +
            URLEncoder.encode(
                UpcLibraryAuthEndpoints.SERVICE,
                StandardCharsets.UTF_8.name(),
            )

    private fun callback(): String =
        "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin" +
            "?ticket=ST-synthetic-ticket123456"

    private fun appTokenPage(): String =
        "https://libspace.upc.edu.cn/libseat/?token=synthetic-app-token"

    private fun formPage(action: String, captcha: Boolean = false): String = """
        <html><body>
        <form action="$action" method="post">
          <input name="username" value="">
          <input name="password" type="password" value="">
          <input name="execution" value="synthetic-execution">
          <input name="type" value="username_password">
          <input name="_eventId" value="submit">
        </form>
        <div id="captchaParent" style="${if (captcha) "display:block" else "display:none"}">
          ${if (captcha) "" else "<input type=\"hidden\" name=\"captcha\">"}
        </div>
        <!-- <input name="captcha"> -->
        <script>
          const captchaSelector = "input[name='captcha']";
          config.captcha = null; config.captcha = false; config.captcha = undefined;
        </script>
        </body></html>
    """.trimIndent()

    private fun htmlResponse(body: String): UpcAuthHttpResponse = response(
        200,
        mapOf("Content-Type" to "text/html; charset=utf-8"),
        body,
    )

    private fun response(
        status: Int,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): UpcAuthHttpResponse = UpcAuthHttpResponse(
        status = status,
        headers = headers.mapKeys { it.key.lowercase() }.mapValues { listOf(it.value) },
        body = body.toByteArray(),
    )
}

private class LibraryFakeTransport(
    private val handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse,
) : UpcAuthTransport {
    var posts = 0

    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
        if (request.method == "POST") posts++
        return handler(request)
    }
}
