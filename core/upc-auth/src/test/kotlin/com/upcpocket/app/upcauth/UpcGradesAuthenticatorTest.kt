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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(RestrictedSchoolCredentialUse::class)
class UpcGradesAuthenticatorTest {

    private val accountId = SchoolAccountId("acct_synthetic_grades")
    private val credential = SchoolLoginCredential(
        accountId = accountId,
        credentialGeneration = AccountGeneration(4),
        loginName = "synthetic-user",
        password = "synthetic-password".toCharArray(),
    )

    @Test
    fun normalCasAcceptsOnlyTheGradesCallbackAndVerifiesIndexOnce() = runTest {
        val callback =
            "https://app.upc.edu.cn/a_upc/api/cas/login" +
                "?ticket=ST-synthetic-grade-ticket&redirect=https%3A%2F%2Fapp.upc.edu.cn" +
                "%2Fsite%2Fscore%2Findex&from=wap"
        val transport = FakeGradesTransport { request ->
            when {
                request.method == "GET" && request.url == UpcGradesAuthEndpoints.LOGIN_URL ->
                    htmlResponse(formPage(action = UpcGradesAuthEndpoints.LOGIN_URL))
                request.method == "POST" -> {
                    assertTrue(request.body.orEmpty().contains("password=synthetic-password"))
                    assertEquals(UpcGradesAuthEndpoints.LOGIN_URL, request.url)
                    response(302, mapOf("Location" to callback))
                }
                request.url == callback -> response(
                    200,
                    mapOf(
                        "Set-Cookie" to
                            "eai-sess=synthetic-grade-session; Domain=app.upc.edu.cn; " +
                            "Path=/; Secure; HttpOnly",
                    ),
                    "<html>grades</html>",
                )
                request.url == UpcGradesAuthEndpoints.INDEX_URL -> response(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    """{"e":0,"d":{"terms":[],"params":{}}}""",
                )
                else -> error("unexpected request")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 4)

        val success = result as UpcAuthResult.Success
        assertEquals(1, success.cookies.size)
        assertEquals("eai-sess", success.cookies.single().name)
        assertEquals("app.upc.edu.cn", success.cookies.single().domain)
        assertTrue(success.cookies.single().secure)
        assertFalse(success.cookies.single().toString().contains("synthetic-grade-session"))
        assertEquals(1, transport.posts)
        assertEquals(1, transport.count("GET", UpcGradesAuthEndpoints.INDEX_URL))
        val indexRequest = transport.requests.single {
            it.method == "GET" && it.url == UpcGradesAuthEndpoints.INDEX_URL
        }
        assertEquals(
            UpcGradesAuthEndpoints.PAGE_URL,
            indexRequest.headers["Referer"],
        )
        assertEquals("XMLHttpRequest", indexRequest.headers["X-Requested-With"])
    }

    @Test
    fun missingWrongDuplicateOrUnknownCallbackParametersAreRejectedBeforeFollow() = runTest {
        val wrongTarget = "https://app.upc.edu.cn/a_upc/api/cas/login?ticket=ST-synthetic-ticket&" +
            "redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex&from=wap"
        val missingRedirect = "https://app.upc.edu.cn/a_upc/api/cas/login?ticket=ST-synthetic-ticket"
        val duplicateTicket = "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?ticket=ST-synthetic-ticket&ticket=ST-synthetic-other" +
            "&redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fscore%2Findex"
        val unknownParameter = "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?ticket=ST-synthetic-ticket&redirect=https%3A%2F%2Fapp.upc.edu.cn" +
            "%2Fsite%2Fscore%2Findex&next=https%3A%2F%2Fevil.invalid%2F"

        for ((name, location) in listOf(
            "wrong target" to wrongTarget,
            "missing redirect" to missingRedirect,
            "duplicate ticket" to duplicateTicket,
            "unknown parameter" to unknownParameter,
        )) {
            val transport = FakeGradesTransport { request ->
                when {
                    request.method == "GET" &&
                        request.url == UpcGradesAuthEndpoints.LOGIN_URL ->
                        htmlResponse(formPage(action = UpcGradesAuthEndpoints.LOGIN_URL))
                    request.method == "POST" -> response(302, mapOf("Location" to location))
                    else -> error("$name 回调不得被跟随")
                }
            }

            val result = authenticator(transport).authenticate(accountId, 4)

            assertEquals(
                name,
                UpcAuthFailure.PROTOCOL_ERROR,
                (result as UpcAuthResult.Failure).reason,
            )
            assertEquals(1, transport.posts)
        }
    }

    @Test
    fun externalTicketCallbackIsRejectedBeforeFollowing() = runTest {
        val transport = FakeGradesTransport { request ->
            when {
                request.method == "GET" -> if (request.url == UpcGradesAuthEndpoints.LOGIN_URL) {
                    htmlResponse(formPage(action = UpcGradesAuthEndpoints.LOGIN_URL))
                } else {
                    error("不得跟随外部回调")
                }
                else -> response(
                    302,
                    mapOf(
                        "Location" to
                            "https://evil.invalid/a_upc/api/cas/login?ticket=ST-synthetic&" +
                            "redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fscore%2Findex",
                    ),
                )
            }
        }

        val result = authenticator(transport).authenticate(accountId, 4)
        assertEquals(UpcAuthFailure.PROTOCOL_ERROR, (result as UpcAuthResult.Failure).reason)
    }

    @Test
    fun captchaStopsBeforeSubmittingPassword() = runTest {
        val transport = FakeGradesTransport { request ->
            if (request.method == "GET") {
                htmlResponse(
                    formPage(action = UpcGradesAuthEndpoints.LOGIN_URL, captcha = true),
                )
            } else {
                error("验证码页面不得提交密码")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 4)
        assertEquals(UpcAuthFailure.INTERACTION_REQUIRED, (result as UpcAuthResult.Failure).reason)
        assertEquals(UpcGradesAuthEndpoints.LOGIN_URL, result.interactionUrl)
        assertEquals(0, transport.posts)
    }

    @Test
    fun secondFactorFormStopsWithoutSubmittingPassword() = runTest {
        val page = """
            <form action="${UpcGradesAuthEndpoints.LOGIN_URL}">
              <input name="type" value="username_smstoken">
              <input name="username_smstoken">
            </form>
        """.trimIndent()
        val transport = FakeGradesTransport { request ->
            if (request.method == "GET") htmlResponse(page)
            else error("二次验证页面不得提交密码")
        }

        val result = authenticator(transport).authenticate(accountId, 4)
        assertEquals(UpcAuthFailure.INTERACTION_REQUIRED, (result as UpcAuthResult.Failure).reason)
        assertEquals(UpcGradesAuthEndpoints.LOGIN_URL, result.interactionUrl)
        assertEquals(0, transport.posts)
    }

    @Test
    fun expiredAppCookieIsNotReturnedAsSuccess() = runTest {
        val callback = "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?ticket=ST-synthetic-expired&redirect=https%3A%2F%2Fapp.upc.edu.cn" +
            "%2Fsite%2Fscore%2Findex"
        val transport = FakeGradesTransport { request ->
            when {
                request.method == "GET" && request.url == UpcGradesAuthEndpoints.LOGIN_URL ->
                    htmlResponse(formPage(action = UpcGradesAuthEndpoints.LOGIN_URL))
                request.method == "POST" -> response(302, mapOf("Location" to callback))
                request.url == callback -> response(
                    200,
                    mapOf(
                        "Set-Cookie" to
                            "eai-sess=expired; Domain=app.upc.edu.cn; Path=/; Secure; " +
                            "Expires=Thu, 01 Jan 1970 00:00:00 GMT",
                    ),
                    "ok",
                )
                request.url == UpcGradesAuthEndpoints.INDEX_URL -> response(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    """{"e":0}""",
                )
                else -> error("unexpected request")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 4)
        assertEquals(UpcAuthFailure.UPSTREAM_ERROR, (result as UpcAuthResult.Failure).reason)
    }

    @Test
    fun indexBusinessFailureOrNonJsonNeverBecomesSuccess() = runTest {
        val callback = "https://app.upc.edu.cn/a_upc/api/cas/login" +
            "?ticket=ST-synthetic-index&redirect=https%3A%2F%2Fapp.upc.edu.cn" +
            "%2Fsite%2Fscore%2Findex"
        for ((name, indexResponse) in listOf(
            "auth expired" to response(
                200,
                mapOf("Content-Type" to "application/json"),
                """{"e":10013}""",
            ),
            "non json" to response(
                200,
                mapOf("Content-Type" to "text/html"),
                "<html>login</html>",
            ),
        )) {
            val transport = FakeGradesTransport { request ->
                when {
                    request.method == "GET" &&
                        request.url == UpcGradesAuthEndpoints.LOGIN_URL ->
                        htmlResponse(formPage(action = UpcGradesAuthEndpoints.LOGIN_URL))
                    request.method == "POST" -> response(302, mapOf("Location" to callback))
                    request.url == callback -> response(
                        200,
                        mapOf(
                            "Set-Cookie" to
                                "eai-sess=synthetic; Domain=app.upc.edu.cn; Path=/; Secure",
                        ),
                        "ok",
                    )
                    request.url == UpcGradesAuthEndpoints.INDEX_URL -> indexResponse
                    else -> error("unexpected request")
                }
            }

            val result = authenticator(transport).authenticate(accountId, 4)
            assertEquals(
                name,
                UpcAuthFailure.UPSTREAM_ERROR.takeIf { name == "auth expired" }
                    ?: UpcAuthFailure.PROTOCOL_ERROR,
                (result as UpcAuthResult.Failure).reason,
            )
        }
    }

    @Test
    fun accountMismatchDoesNotStartNetwork() = runTest {
        val transport = FakeGradesTransport { error("账号不一致时不得联网") }
        val result = authenticator(transport).authenticate(
            SchoolAccountId("acct_synthetic_other"),
            4,
        )
        assertEquals(UpcAuthFailure.ACCOUNT_CHANGED, (result as UpcAuthResult.Failure).reason)
    }

    private fun authenticator(transport: UpcAuthTransport): UpcGradesAuthenticator =
        UpcGradesAuthenticator(
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
        <div id="captchaParent" style="${if (captcha) "display:block" else "display:none"}">
          ${if (captcha) "" else "<input type=\"hidden\" name=\"captcha\">"}
        </div>
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

private class FakeGradesTransport(
    private val handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse,
) : UpcAuthTransport {
    val requests = mutableListOf<UpcAuthHttpRequest>()
    var posts = 0

    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
        requests += request
        if (request.method == "POST") posts++
        return handler(request)
    }

    fun count(method: String, url: String): Int =
        requests.count { it.method == method && it.url == url }
}
