package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(RestrictedSchoolCredentialUse::class)
class UpcCardAuthenticatorTest {
    private val accountId = SchoolAccountId("acct_synthetic_account")
    private val cas = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fcallback"
    private val html = """<form method="post" id="loginForm">
        <input name="username"><input name="password" type="password"><input name="type" value="username_password">
        <input name="execution" value="example-execution"><input name="_eventId" value="submit"></form>"""

    @Test
    fun captchaStopsBeforeSubmittingPassword() = runTest {
        val transport = CardFakeTransport { request ->
            when (request.method) {
                "GET" -> if (request.url == CardAuthOrigins.ENTRY) response(302, location = cas)
                    else htmlResponse(html + "<script>config.captcha = {id: 'example-id'};</script>")
                else -> error("验证码分支不得提交密码")
            }
        }
        val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
        assertEquals(UpcCardAuthFailure.INTERACTION_REQUIRED, (result as UpcCardAuthResult.Failure).reason)
        assertEquals(0, transport.posts)
    }

    @Test
    fun unstyledCaptchaParentStopsBeforeSubmittingPassword() = runTest {
        val transport = CardFakeTransport { request ->
            when (request.method) {
                "GET" -> if (request.url == CardAuthOrigins.ENTRY) response(302, location = cas)
                    else htmlResponse(html + "<div id=\"captchaParent\"><iframe src=\"/captcha\"></iframe></div>")
                else -> error("验证码页面不得提交密码")
            }
        }
        val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
        assertEquals(UpcCardAuthFailure.INTERACTION_REQUIRED, (result as UpcCardAuthResult.Failure).reason)
        assertEquals(0, transport.posts)
    }

    @Test
    fun emptyCaptchaObjectOffersInteractionWithoutSubmittingPassword() = runTest {
        val transport = CardFakeTransport { request ->
            when (request.method) {
                "GET" -> if (request.url == CardAuthOrigins.ENTRY) response(302, location = cas)
                    else htmlResponse(html + "<script>config.captcha = {};</script>")
                else -> error("未解析的验证码不得提交密码")
            }
        }
        val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
        assertEquals(UpcCardAuthFailure.INTERACTION_REQUIRED, (result as UpcCardAuthResult.Failure).reason)
        assertEquals(0, transport.posts)
    }

    @Test
    fun deniedGetAllowsOneValidatedPasswordPostButChallengeStopsBeforePost() = runTest {
        val pages = listOf(html, html + "<script>config.captcha = {id: 'example-id'};</script>",
            html.replace("username_password", "username_smstoken"))
        for (status in listOf(401, 403)) for (page in pages) {
            val transport = CardFakeTransport { request ->
                if (request.url == CardAuthOrigins.ENTRY) response(302, location = cas)
                else response(status, page)
            }
            val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
            assertEquals("GET $status", UpcCardAuthFailure.INTERACTION_REQUIRED, (result as UpcCardAuthResult.Failure).reason)
            val challenge = page != html
            assertEquals(if (challenge) 0 else 1, transport.posts)
            assertEquals(if (challenge) 2 else 3, transport.requests.size)
        }
    }

    @Test
    fun ordinaryDeniedGetCanCompleteAfterOnePasswordPost() = runTest {
        val tokenUrl = "${CardAuthOrigins.BASE}/plat/?synjones-auth=synthetic-token"
        for (status in listOf(401, 403)) {
            val transport = CardFakeTransport { request ->
                when {
                    request.url == CardAuthOrigins.ENTRY -> response(302, location = cas)
                    request.method == "GET" -> response(status, html)
                    else -> response(302, location = tokenUrl)
                }
            }
            val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
            assertTrue("GET $status", result is UpcCardAuthResult.Success)
            assertEquals("GET $status", "synthetic-token", (result as UpcCardAuthResult.Success).token)
            assertEquals("GET $status", 1, transport.posts)
        }
    }

    @Test
    fun deniedPostOffersInteractionAfterExactlyOnePasswordSubmission() = runTest {
        for (status in listOf(401, 403)) for (page in listOf(html, html + "<script>config.captcha = { unsupported: true };</script>",
            html.replace("username_password", "username_smstoken"))) {
            val transport = CardFakeTransport { request ->
                when {
                    request.url == CardAuthOrigins.ENTRY -> response(302, location = cas)
                    request.method == "GET" -> htmlResponse(html)
                    else -> response(status, page)
                }
            }
            val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
            assertEquals(UpcCardAuthFailure.INTERACTION_REQUIRED, (result as UpcCardAuthResult.Failure).reason)
            assertEquals(1, transport.posts)
            assertEquals(3, transport.requests.size)
        }
    }

    @Test
    fun unknownDeniedBodyAndServerFailuresAreNotReportedAsCaptcha() = runTest {
        val cases = listOf(
            Triple(401, "<html>Service unavailable</html>", UpcCardAuthFailure.PROTOCOL_ERROR),
            Triple(403, "<html>帮助：扫码登录 / 验证码登录</html>", UpcCardAuthFailure.PROTOCOL_ERROR),
            Triple(429, html, UpcCardAuthFailure.ACCESS_DENIED),
            Triple(500, html, UpcCardAuthFailure.UPSTREAM_ERROR),
            Triple(503, html + "<script>config.captcha = { unsupported: true };</script>", UpcCardAuthFailure.UPSTREAM_ERROR),
        )
        for ((status, page, expected) in cases) {
            val transport = CardFakeTransport { request ->
                if (request.url == CardAuthOrigins.ENTRY) response(302, location = cas)
                else response(status, page)
            }
            val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
            assertEquals("HTTP $status", expected, (result as UpcCardAuthResult.Failure).reason)
            assertEquals(0, transport.posts)
        }
        // Even a recognizable form at the card entry is not a CAS challenge.
        val entryDenied = CardFakeTransport { response(401, html) }
        val result = UpcCardAuthenticator(credentials(), entryDenied).authenticate(accountId, 3)
        assertEquals(UpcCardAuthFailure.ACCESS_DENIED, (result as UpcCardAuthResult.Failure).reason)
    }

    @Test
    fun transportTlsAndNetworkFailuresRemainDistinctFromInteraction() = runTest {
        for ((failure, expected) in listOf(UpcAuthTransportFailure.TLS to UpcCardAuthFailure.TLS_ERROR,
            UpcAuthTransportFailure.NETWORK to UpcCardAuthFailure.NETWORK_UNAVAILABLE)) {
            val transport = CardFakeTransport { throw UpcAuthTransportException(failure) }
            val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
            assertEquals(expected, (result as UpcCardAuthResult.Failure).reason)
            assertEquals(0, transport.posts)
        }
    }

    @Test
    fun rejectedPasswordIsSubmittedExactlyOnceAndNeverRetried() = runTest {
        val transport = CardFakeTransport { request ->
            when (request.method) {
                "GET" -> if (request.url == CardAuthOrigins.ENTRY) response(302, location = cas) else htmlResponse(html)
                else -> htmlResponse(html)
            }
        }
        val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
        assertEquals(UpcCardAuthFailure.INTERACTION_REQUIRED, (result as UpcCardAuthResult.Failure).reason)
        assertEquals(1, transport.posts)
        assertEquals(cas, transport.requests.first { it.method == "POST" }.url)
    }

    @Test
    fun explicitPostCredentialRejectionIsClassifiedWithoutRetrying() = runTest {
        val transport = CardFakeTransport { request ->
            when {
                request.url == CardAuthOrigins.ENTRY -> response(302, location = cas)
                request.method == "GET" -> htmlResponse(html)
                else -> htmlResponse(html + "<div id='errorDiv'>用户名或密码错误</div>")
            }
        }
        val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
        assertEquals(UpcCardAuthFailure.INVALID_CREDENTIALS, (result as UpcCardAuthResult.Failure).reason)
        assertEquals(1, transport.posts)
    }

    @Test
    fun changedAccountNeverReachesTheNetwork() = runTest {
        val transport = CardFakeTransport { error("账号代次不符时不得发起登录") }
        val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 4)
        assertEquals(UpcCardAuthFailure.ACCOUNT_CHANGED, (result as UpcCardAuthResult.Failure).reason)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun credentialInvalidatedDuringUseIsReportedAsAccountChanged() = runTest {
        val credentials = object : SchoolCredentialAccess {
            override suspend fun <T> withLoginCredential(
                block: suspend (SchoolLoginCredential) -> T,
            ): SchoolCredentialUseResult<T> = SchoolCredentialUseResult.Failure(SchoolCredentialFailure.CHANGED_DURING_USE)
        }
        val result = UpcCardAuthenticator(credentials, CardFakeTransport { error("不得发起登录") }).authenticate(accountId, 3)
        assertEquals(UpcCardAuthFailure.ACCOUNT_CHANGED, (result as UpcCardAuthResult.Failure).reason)
    }

    private fun credentials(): SchoolCredentialAccess = object : SchoolCredentialAccess {
        override suspend fun <T> withLoginCredential(
            block: suspend (SchoolLoginCredential) -> T,
        ): SchoolCredentialUseResult<T> {
            val copy = SchoolLoginCredential(
                accountId = accountId,
                credentialGeneration = AccountGeneration(3),
                loginName = "2099999999",
                password = "synthetic-password".toCharArray(),
            )
            return try {
                SchoolCredentialUseResult.Success(block(copy))
            } finally {
                copy.close()
            }
        }
    }

    private fun htmlResponse(body: String): UpcAuthHttpResponse = response(200, body = body)

    private fun response(status: Int, body: String = "", location: String? = null): UpcAuthHttpResponse =
        UpcAuthHttpResponse(
            status = status,
            headers = buildMap { location?.let { put("location", listOf(it)) } },
            body = body.toByteArray(),
        )
}

private class CardFakeTransport(private val handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse) : UpcAuthTransport {
    val requests = mutableListOf<UpcAuthHttpRequest>()
    val posts get() = requests.count { it.method == "POST" }

    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
        requests += request
        return handler(request)
    }
}
