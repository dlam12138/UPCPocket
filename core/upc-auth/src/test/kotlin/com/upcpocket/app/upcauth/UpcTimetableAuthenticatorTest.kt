package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(RestrictedSchoolCredentialUse::class)
class UpcTimetableAuthenticatorTest {

    private val accountId = SchoolAccountId("acct_synthetic_account")
    private val credential = SchoolLoginCredential(
        accountId = accountId,
        credentialGeneration = AccountGeneration(3),
        loginName = "synthetic-user",
        password = "synthetic-password".toCharArray(),
    )

    @Test
    fun captchaRequiresInteractionWithoutSubmittingPassword() = runTest {
        val transport = FakeTransport { request ->
            if (request.method == "GET") {
                htmlResponse(formPage(action = UpcTimetableAuthEndpoints.LOGIN_URL, captcha = true))
            } else {
                error("验证码页面不得提交密码")
            }
        }
        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(UpcAuthFailure.INTERACTION_REQUIRED, (result as UpcAuthResult.Failure).reason)
        assertEquals(UpcTimetableAuthEndpoints.LOGIN_URL, result.interactionUrl)
        assertEquals(0, transport.posts)
    }

    @Test
    fun wrongPasswordPostsExactlyOnceAndDoesNotRetry() = runTest {
        var posts = 0
        val transport = FakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcTimetableAuthEndpoints.LOGIN_URL ->
                    htmlResponse(formPage(action = UpcTimetableAuthEndpoints.LOGIN_URL))
                request.method == "POST" -> {
                    posts++
                    htmlResponse(
                        """<html><form action="${UpcTimetableAuthEndpoints.LOGIN_URL}">""" +
                            """<input name="username"><input type="password" name="password">""" +
                            """<div id="errorDiv">用户名或密码错误</div></form></html>""",
                    )
                }
                else -> error("unexpected request")
            }
        }
        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(UpcAuthFailure.INVALID_CREDENTIALS, (result as UpcAuthResult.Failure).reason)
        assertEquals(1, posts)
    }

    @Test
    fun changedFormActionNeverReceivesPassword() = runTest {
        var posts = 0
        val transport = FakeTransport { request ->
            if (request.method == "GET") {
                htmlResponse(
                    formPage(
                        action = "https://evil.invalid/cas/login?service=x",
                    ),
                )
            } else {
                posts++
                error("非官方表单不得收到密码")
            }
        }
        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(UpcAuthFailure.PROTOCOL_ERROR, (result as UpcAuthResult.Failure).reason)
        assertEquals(0, posts)
    }

    @Test
    fun successfulCasRedirectEstablishesAppCookieAndVerifiesIndex() = runTest {
        val callback =
            "https://app.upc.edu.cn/a_upc/api/cas/login" +
                "?ticket=ST-synthetic-ticket&redirect=https%3A%2F%2Fapp.upc.edu.cn" +
                "%2Fsite%2Fweekschedule%2Findex&from=wap"
        val transport = FakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcTimetableAuthEndpoints.LOGIN_URL ->
                    htmlResponse(formPage(action = UpcTimetableAuthEndpoints.LOGIN_URL))
                request.method == "POST" -> response(
                    302,
                    mapOf("Location" to callback),
                )
                request.url == callback -> response(
                    200,
                    mapOf(
                        "Set-Cookie" to
                            "eai-sess=synthetic-session; Domain=app.upc.edu.cn; Path=/; Secure; HttpOnly",
                    ),
                    "<html>app</html>",
                )
                request.url == UpcTimetableAuthEndpoints.INDEX_URL -> response(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    """{"e":0,"d":{"termInfo":[]}}""",
                )
                else -> error("unexpected request")
            }
        }
        val result = authenticator(transport).authenticate(accountId, 3)
        val success = result as UpcAuthResult.Success
        assertEquals(1, success.cookies.size)
        assertEquals("eai-sess", success.cookies.single().name)
        assertEquals("app.upc.edu.cn", success.cookies.single().domain)
        assertTrue(success.cookies.single().secure)
        assertFalse(success.cookies.single().toString().contains("synthetic-session"))
    }

    @Test
    fun ticketRedirectToExternalHostIsRejectedBeforeFollowing() = runTest {
        val transport = FakeTransport { request ->
            when (request.method) {
                "GET" -> if (request.url == UpcTimetableAuthEndpoints.LOGIN_URL) {
                    htmlResponse(formPage(action = UpcTimetableAuthEndpoints.LOGIN_URL))
                } else {
                    error("不得跟随外部票据回调")
                }
                else -> response(
                    302,
                    mapOf(
                        "Location" to
                            "https://evil.invalid/a_upc/api/cas/login?ticket=ST-synthetic&" +
                            "redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex",
                    ),
                )
            }
        }
        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(UpcAuthFailure.PROTOCOL_ERROR, (result as UpcAuthResult.Failure).reason)
    }

    @Test
    fun expiredAppCookieIsNotReturned() = runTest {
        val callback =
            "https://app.upc.edu.cn/a_upc/api/cas/login?ticket=ST-synthetic-ticket123456"
        val transport = FakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcTimetableAuthEndpoints.LOGIN_URL ->
                    htmlResponse(formPage(action = UpcTimetableAuthEndpoints.LOGIN_URL))
                request.method == "POST" -> response(302, mapOf("Location" to callback))
                request.url == callback -> response(
                    200,
                    mapOf(
                        "Set-Cookie" to
                            "eai-sess=gone; Domain=app.upc.edu.cn; Path=/; Secure; " +
                            "Expires=Thu, 01 Jan 1970 00:00:00 GMT",
                    ),
                    "ok",
                )
                request.url == UpcTimetableAuthEndpoints.INDEX_URL -> response(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    """{"e":0}""",
                )
                else -> error("unexpected request")
            }
        }
        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(UpcAuthFailure.UPSTREAM_ERROR, (result as UpcAuthResult.Failure).reason)
    }

    @Test
    fun indexAuthExpiredIsNotSuccess() = runTest {
        val callback =
            "https://app.upc.edu.cn/a_upc/api/cas/login?ticket=ST-synthetic-ticket123456"
        val transport = FakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcTimetableAuthEndpoints.LOGIN_URL ->
                    htmlResponse(formPage(action = UpcTimetableAuthEndpoints.LOGIN_URL))
                request.method == "POST" -> response(302, mapOf("Location" to callback))
                request.url == callback -> response(
                    200,
                    mapOf(
                        "Set-Cookie" to
                            "eai-sess=session; Domain=app.upc.edu.cn; Path=/; Secure",
                    ),
                    "ok",
                )
                request.url == UpcTimetableAuthEndpoints.INDEX_URL -> response(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    """{"e":10013}""",
                )
                else -> error("unexpected request")
            }
        }
        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(UpcAuthFailure.UPSTREAM_ERROR, (result as UpcAuthResult.Failure).reason)
    }

    @Test
    fun accountMismatchDoesNotStartNetwork() = runTest {
        val transport = FakeTransport { error("账号不一致时不得联网") }
        val other = SchoolAccountId("acct_another_account")
        val result = authenticator(transport).authenticate(other, 3)
        assertEquals(UpcAuthFailure.ACCOUNT_CHANGED, (result as UpcAuthResult.Failure).reason)
    }

    @Test
    fun tlsFailureIsDistinctFromNetworkFailure() = runTest {
        val tls = FakeTransport { throw UpcAuthTransportException(UpcAuthTransportFailure.TLS) }
        assertEquals(
            UpcAuthFailure.TLS_ERROR,
            (authenticator(tls).authenticate(accountId, 3) as UpcAuthResult.Failure).reason,
        )
        val network = FakeTransport {
            throw UpcAuthTransportException(UpcAuthTransportFailure.NETWORK)
        }
        assertEquals(
            UpcAuthFailure.NETWORK_UNAVAILABLE,
            (authenticator(network).authenticate(accountId, 3) as UpcAuthResult.Failure).reason,
        )
    }

    @Test
    fun passwordOnlyAppearsInTheSingleCasForm() = runTest {
        val seen = mutableListOf<Pair<String, String>>()
        val transport = FakeTransport { request ->
            seen += request.method to request.url
            if (request.method == "GET") {
                htmlResponse(formPage(action = UpcTimetableAuthEndpoints.LOGIN_URL))
            } else {
                assertTrue(request.body.orEmpty().contains("password=synthetic-password"))
                response(
                    302,
                    mapOf(
                        "Location" to
                            "https://app.upc.edu.cn/a_upc/api/cas/login?ticket=ST-12345678",
                    ),
                )
            }
        }
        authenticator(transport).authenticate(accountId, 3)
        assertEquals(1, seen.count { it.first == "POST" })
        assertEquals(UpcTimetableAuthEndpoints.LOGIN_URL, seen.first { it.first == "POST" }.second)
    }

    private fun authenticator(transport: UpcAuthTransport): UpcTimetableAuthenticator =
        UpcTimetableAuthenticator(
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

private class FakeTransport(
    private val handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse,
) : UpcAuthTransport {
    var posts = 0

    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
        if (request.method == "POST") posts++
        return handler(request)
    }
}
