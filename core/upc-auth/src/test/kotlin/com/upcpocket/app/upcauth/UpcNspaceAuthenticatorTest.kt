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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(RestrictedSchoolCredentialUse::class)
class UpcNspaceAuthenticatorTest {

    private val accountId = SchoolAccountId("acct_synthetic_nspace")
    private val credential = SchoolLoginCredential(
        accountId = accountId,
        credentialGeneration = AccountGeneration(7),
        loginName = "synthetic-user",
        password = "synthetic-password".toCharArray(),
    )

    @Test
    fun `normal nspace CAS exchanges the separate app token and verifies user`() = runTest {
        val transport = NspaceFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcNspaceAuthEndpoints.ENTRY_URL ->
                    response(302, mapOf("Location" to casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(casUrl()))
                request.method == "POST" && request.url == casUrl() -> {
                    assertTrue(request.body.orEmpty().contains("password=synthetic-password"))
                    response(302, mapOf("Location" to callback()))
                }
                request.method == "GET" && request.url == callback() ->
                    response(302, mapOf("Location" to appTokenPage()))
                request.method == "GET" &&
                    request.url.startsWith(UpcNspaceAuthEndpoints.REMOTE_CAS_LOGIN_URL) -> {
                    assertTrue(request.url.endsWith("token=synthetic-nspace-app-token"))
                    response(
                        200,
                        mapOf("Content-Type" to "application/json"),
                        """{"status":true,"code":200,"data":{"token":"synthetic-nspace-token",
                           "userInfo":{"id":"synthetic-id","username":"synthetic-user","name":"合成用户"}}}""",
                    )
                }
                else -> error("unexpected request ${request.method} ${request.url}")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 7)

        val success = result as UpcNspaceAuthResult.Success
        assertEquals("synthetic-nspace-token", success.token)
        assertEquals("synthetic-user", success.user.username)
        assertEquals("合成用户", success.user.name)
        assertEquals(1, transport.posts)
    }

    @Test
    fun `hash fragment token is accepted and nspace token is not a seat token`() = runTest {
        val page = "https://libspace.upc.edu.cn/nspace/#/login?token=synthetic-nspace-app-token"
        val result = authenticator(successfulTransport(page = page)).authenticate(accountId, 7)
        assertEquals(
            "synthetic-nspace-token",
            (result as UpcNspaceAuthResult.Success).token,
        )
    }

    @Test
    fun `captcha does not submit password`() = runTest {
        val transport = NspaceFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcNspaceAuthEndpoints.ENTRY_URL ->
                    response(302, mapOf("Location" to casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(casUrl(), captcha = true))
                else -> error("验证码页面不得提交密码")
            }
        }
        val result = authenticator(transport).authenticate(accountId, 7)
        assertEquals(
            UpcLibraryAuthFailure.INTERACTION_REQUIRED,
            (result as UpcNspaceAuthResult.Failure).reason,
        )
        assertEquals(0, transport.posts)
    }

    @Test
    fun `identity mismatch is rejected`() = runTest {
        val result = authenticator(successfulTransport(username = "another-user"))
            .authenticate(accountId, 7)
        assertEquals(
            UpcLibraryAuthFailure.IDENTITY_MISMATCH,
            (result as UpcNspaceAuthResult.Failure).reason,
        )
    }

    @Test
    fun `nspace login code 401 is an expired session not an empty list`() = runTest {
        val transport = baseTransport {
            response(
                200,
                mapOf("Content-Type" to "application/json"),
                """{"status":false,"code":401,"message":"expired"}""",
            )
        }
        val result = authenticator(transport).authenticate(accountId, 7)
        assertEquals(
            UpcLibraryAuthFailure.ACCESS_DENIED,
            (result as UpcNspaceAuthResult.Failure).reason,
        )
    }

    @Test
    fun `external callback is rejected`() = runTest {
        val transport = NspaceFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcNspaceAuthEndpoints.ENTRY_URL ->
                    response(302, mapOf("Location" to casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(casUrl()))
                request.method == "POST" -> response(
                    302,
                    mapOf("Location" to "https://evil.invalid/nspace/?token=synthetic"),
                )
                else -> error("不得跟随外部回调")
            }
        }
        val result = authenticator(transport).authenticate(accountId, 7)
        assertEquals(
            UpcLibraryAuthFailure.PROTOCOL_ERROR,
            (result as UpcNspaceAuthResult.Failure).reason,
        )
    }

    private fun successfulTransport(
        username: String = "synthetic-user",
        page: String = appTokenPage(),
    ): NspaceFakeTransport = baseTransport(username = username, page = page)

    private fun baseTransport(
        username: String = "synthetic-user",
        page: String = appTokenPage(),
        remote: (() -> UpcAuthHttpResponse)? = null,
    ): NspaceFakeTransport = NspaceFakeTransport { request ->
        when {
            request.method == "GET" && request.url == UpcNspaceAuthEndpoints.ENTRY_URL ->
                response(302, mapOf("Location" to casUrl()))
            request.method == "GET" && request.url == casUrl() ->
                htmlResponse(formPage(casUrl()))
            request.method == "POST" && request.url == casUrl() ->
                response(302, mapOf("Location" to callback()))
            request.method == "GET" && request.url == callback() ->
                response(302, mapOf("Location" to page))
            request.method == "GET" &&
                request.url.startsWith(UpcNspaceAuthEndpoints.REMOTE_CAS_LOGIN_URL) ->
                remote?.invoke() ?: response(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    """{"status":true,"code":200,"data":{"token":"synthetic-nspace-token",
                       "userInfo":{"id":"synthetic-id","username":"$username"}}}""",
                )
            else -> error("unexpected request ${request.method} ${request.url}")
        }
    }

    private fun authenticator(transport: UpcAuthTransport): UpcNspaceAuthenticator =
        UpcNspaceAuthenticator(
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
            URLEncoder.encode(UpcNspaceAuthEndpoints.SERVICE, StandardCharsets.UTF_8.name())

    private fun callback(): String =
        "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin" +
            "?ticket=ST-synthetic-ticket123456"

    private fun appTokenPage(): String =
        "https://libspace.upc.edu.cn/nspace/?token=synthetic-nspace-app-token"

    private fun formPage(action: String, captcha: Boolean = false): String = """
        <html><body>
        <form action="$action" method="post">
          <input name="username" value="">
          <input name="password" type="password" value="">
          <input name="execution" value="synthetic-execution">
          <input name="type" value="username_password">
          <input name="_eventId" value="submit">
        </form>
        <div id="captchaParent" style="${if (captcha) "display:block" else "display:none"}">captcha</div>
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

private class NspaceFakeTransport(
    private val handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse,
) : UpcAuthTransport {
    var posts = 0

    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
        if (request.method == "POST") posts++
        return handler(request)
    }
}
