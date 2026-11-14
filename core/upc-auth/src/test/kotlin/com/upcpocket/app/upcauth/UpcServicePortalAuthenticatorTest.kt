package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBodyType
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticMediaType
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticSink
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(RestrictedSchoolCredentialUse::class)
class UpcServicePortalAuthenticatorTest {
    private val accountA = SchoolAccountId("acct_synthetic_account")
    private val accountB = SchoolAccountId("acct_another_synthetic")

    @Test
    fun serviceAndCallbackAreExactAndSuccessExportsOnlyBusinessHostCookies() = runTest {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val transport = successTransport(requestObserver = { requests += it })
        val result = authenticator(transport).authenticate(accountA, 7)

        val success = result as UpcServicePortalAuthResult.Success
        val login = UpcServicePortalAuthEndpoints.LOGIN_URL.toHttpUrlOrNullOrFail()
        assertEquals(UpcServicePortalAuthEndpoints.SERVICE, login.queryParameter("service"))
        assertEquals(UpcServicePortalAuthEndpoints.REDIRECT_URL, login.queryParameter("service")
            ?.toHttpUrlOrNullOrFail()?.queryParameter("redirect_url"))
        assertEquals(UpcServicePortalAuthEndpoints.LOGIN_URL, requests.first().url)
        assertEquals(1, requests.count { it.method == "POST" })
        assertTrue(success.cookies.all { it.domain == UpcServicePortalAuthEndpoints.HOST })
        assertTrue(success.cookies.single().hostOnly)
        assertEquals(SchoolAccountReference(accountA, AccountGeneration(7)), success.binding)
        assertEquals(UpcServicePortalAuthSource.PASSWORD, success.source)
        assertFalse(success.toString().contains("portal-cookie-test"))
        assertFalse(success.cookies.single().toString().contains("portal-cookie-test"))
    }

    @Test
    fun ordinaryGet401And403FormsAreSubmittedOnceAndCanCompleteLogin() = runTest {
        for (status in listOf(401, 403)) {
            val transport = successTransport(loginStatus = status)
            val result = authenticator(transport).authenticate(accountA, 7)
            assertTrue("GET $status", result is UpcServicePortalAuthResult.Success)
            assertEquals("GET $status", 1, transport.posts)
        }
    }

    @Test
    fun successfulSessionEnvelopeDoesNotConstrainTheOptionalNumField() = runTest {
        val bodies = listOf(
            "{\"e\":0,\"d\":{\"num\":[]}}",
            "{\"e\":0,\"d\":{}}",
            "{\"e\":0,\"d\":{\"num\":-1}}",
            "{\"e\":0,\"d\":{\"num\":1.5}}",
            "{\"e\":0,\"d\":{\"num\":\"0\"}}",
            "{\"e\":0,\"d\":{\"num\":null}}",
            "{\"e\":0,\"d\":{\"num\":{}}}",
        )
        for (body in bodies) {
            val transport = successTransport(countBody = body)
            val result = authenticator(transport).authenticate(accountA, 7)
            assertTrue(result is UpcServicePortalAuthResult.Success)
            assertEquals(1, transport.posts)
            assertEquals(1, transport.requests.count {
                it.url == UpcServicePortalAuthEndpoints.SESSION_CHECK_URL
            })
        }
    }

    @Test
    fun malformedOrUnsuccessfulSessionEnvelopesNeverAuthenticate() = runTest {
        val malformedSuccessEnvelopes = listOf(
            "not-json",
            "{\"d\":{}}",
            "{\"e\":\"0\",\"d\":{}}",
            "{\"e\":0.0,\"d\":{}}",
            "{\"e\":0}",
            "{\"e\":0,\"d\":[]}",
        )
        for (body in malformedSuccessEnvelopes) {
            val transport = successTransport(countBody = body)
            val result = authenticator(transport).authenticate(accountA, 7)
            assertFailure(UpcServicePortalAuthFailure.PROTOCOL_ERROR, result)
            assertEquals(1, transport.posts)
        }

        val unsuccessful = successTransport(countBody = "{\"e\":987654,\"d\":{}}")
        assertFailure(
            UpcServicePortalAuthFailure.UPSTREAM_ERROR,
            authenticator(unsuccessful).authenticate(accountA, 7),
        )
        assertEquals(1, unsuccessful.posts)
    }

    @Test
    fun sessionCheckStopsHaveFixedReasonsAndExcludeHeadersBodiesAndCookies() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) {
                events += event
            }
        })
        try {
            data class Case(
                val contentType: String,
                val body: String,
                val callbackCookies: List<String> = listOf("portal=SENT_COOKIE_SENTINEL; Path=/; Secure"),
                val detail: DiagnosticDetail,
                val failure: UpcServicePortalAuthFailure,
                val reason: DiagnosticReason,
                val mediaType: DiagnosticMediaType = DiagnosticMediaType.JSON,
                val bodyType: DiagnosticBodyType = DiagnosticBodyType.JSON_OBJECT,
            )
            val cases = listOf(
                Case(
                    contentType = "text/html; x-header=HEADER_SENTINEL",
                    body = "{\"e\":0,\"d\":{\"num\":0}}",
                    detail = DiagnosticDetail.SERVICE_SESSION_MIME_REJECTED,
                    failure = UpcServicePortalAuthFailure.PROTOCOL_ERROR,
                    reason = DiagnosticReason.MIME,
                    mediaType = DiagnosticMediaType.HTML,
                ),
                Case(
                    contentType = "application/json",
                    body = "BODY_SENTINEL {",
                    detail = DiagnosticDetail.SERVICE_SESSION_BODY_MALFORMED,
                    failure = UpcServicePortalAuthFailure.PROTOCOL_ERROR,
                    reason = DiagnosticReason.SCHEMA,
                    bodyType = DiagnosticBodyType.MALFORMED,
                ),
                Case(
                    contentType = "application/json",
                    body = "{\"e\":0}",
                    detail = DiagnosticDetail.SERVICE_SESSION_SCHEMA_REJECTED,
                    failure = UpcServicePortalAuthFailure.PROTOCOL_ERROR,
                    reason = DiagnosticReason.SCHEMA,
                ),
                Case(
                    contentType = "application/json",
                    body = "{\"e\":30000}",
                    detail = DiagnosticDetail.SERVICE_SESSION_INTERACTION_REQUIRED,
                    failure = UpcServicePortalAuthFailure.INTERACTION_REQUIRED,
                    reason = DiagnosticReason.INTERACTION,
                ),
                Case(
                    contentType = "application/json",
                    body = "{\"e\":987654,\"note\":\"BODY_SENTINEL\"}",
                    detail = DiagnosticDetail.SERVICE_SESSION_SERVER_REJECTED,
                    failure = UpcServicePortalAuthFailure.UPSTREAM_ERROR,
                    reason = DiagnosticReason.UNKNOWN,
                ),
                Case(
                    contentType = "application/json",
                    body = "{\"e\":0,\"d\":{\"num\":0}}",
                    callbackCookies = emptyList(),
                    detail = DiagnosticDetail.SERVICE_SESSION_COOKIE_NOT_SENT,
                    failure = UpcServicePortalAuthFailure.UPSTREAM_ERROR,
                    reason = DiagnosticReason.AUTH,
                ),
            )

            for (case in cases) {
                events.clear()
                val result = authenticator(
                    successTransport(
                        countBody = case.body,
                        countContentType = case.contentType,
                        callbackCookies = case.callbackCookies,
                    ),
                ).authenticate(accountA, 7)

                assertFailure(case.failure, result)
                val event = events.single { it.detail == case.detail }
                assertEquals(DiagnosticModule.SERVICE_PORTAL, event.module)
                assertEquals(DiagnosticAction.AUTH_STOP, event.action)
                assertEquals(DiagnosticResult.FAIL, event.result)
                assertEquals(case.reason, event.reason)
                assertEquals(DiagnosticPhase.PERSONAL_VERIFY, event.phase)
                assertEquals(200, event.httpStatus)
                assertEquals(case.mediaType, event.mediaType)
                assertEquals(case.bodyType, event.bodyType)
                assertEquals(case.body.toByteArray().size, event.responseBytes)
                assertTrue(event.operationId!!.matches(Regex("[0-9a-f]{8}")))
                assertFalse(event.toString().contains("HEADER_SENTINEL"))
                assertFalse(event.toString().contains("BODY_SENTINEL"))
                assertFalse(event.toString().contains("SENT_COOKIE_SENTINEL"))
            }
        } finally {
            Diagnostics.install(null)
        }
    }

    @Test
    fun pollutedOrDuplicateCallbackParametersAreRejectedBeforeCallbackRequest() = runTest {
        val callbacks = listOf(
            "${callbackUrl()}&ticket=ST-duplicate-ticket",
            "${callbackUrl()}&extra=unexpected",
            "https://service.upc.edu.cn/site/login/cas-login?redirect_url=" +
                "https%3A%2F%2Fevil.invalid%2F&ticket=ST-synthetic-ticket",
        )
        callbacks.forEach { callback ->
            var callbackRequests = 0
            val transport = FakeTransport { request ->
                when (request.method) {
                    "GET" -> loginPage()
                    "POST" -> response(302, mapOf("location" to callback))
                    else -> error("unexpected request")
                }.also {
                    if (request.url.startsWith("https://${UpcServicePortalAuthEndpoints.HOST}/")) {
                        callbackRequests++
                    }
                }
            }
            val result = authenticator(transport).authenticate(accountA, 7)
            assertEquals(
                UpcServicePortalAuthFailure.PROTOCOL_ERROR,
                (result as UpcServicePortalAuthResult.Failure).reason,
            )
            assertEquals(0, callbackRequests)
        }
    }

    @Test
    fun captchaStopsBeforePasswordPost() = runTest {
        val transport = FakeTransport { request ->
            if (request.method == "GET") loginPage(captcha = true) else error("captcha must stop login")
        }
        val result = authenticator(transport).authenticate(accountA, 7)
        assertEquals(
            UpcServicePortalAuthFailure.INTERACTION_REQUIRED,
            (result as UpcServicePortalAuthResult.Failure).reason,
        )
        assertEquals(0, transport.posts)
    }

    @Test
    fun secondFactorMarkerStopsBeforePasswordPostEvenWhenPasswordFieldExists() = runTest {
        val transport = FakeTransport { request ->
            if (request.method == "GET") {
                loginPage(secondFactor = true)
            } else {
                error("second factor must stop password submission")
            }
        }
        val result = authenticator(transport).authenticate(accountA, 7)
        assertFailure(UpcServicePortalAuthFailure.INTERACTION_REQUIRED, result)
        assertEquals(0, transport.posts)
    }

    @Test
    fun staticSmsAndQrAlternativesDoNotBlockTheActivePasswordForm() = runTest {
        val staticAlternatives = """
            <nav>扫码登录 验证码登录</nav>
            <form id="smsForm" style="display:none"><input name="type" value="username_smstoken"><input name="captcha"></form>
        """.trimIndent()
        val transport = successTransport(loginPageHtml = formPage() + staticAlternatives)

        val result = authenticator(transport).authenticate(accountA, 7)

        assertTrue(result is UpcServicePortalAuthResult.Success)
        assertEquals(1, transport.posts)
    }

    @Test
    fun callbackRedirectCannotLeaveFixedBusinessHosts() = runTest {
        var externalRequests = 0
        val transport = FakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcServicePortalAuthEndpoints.LOGIN_URL -> loginPage()
                request.method == "POST" -> response(
                    302,
                    mapOf("location" to callbackUrl()),
                )
                request.url == callbackUrl() ->
                    response(302, mapOf("location" to "https://evil.invalid/collect"))
                request.url.startsWith("https://evil.invalid/") -> {
                    externalRequests++
                    error("external URL must not be requested")
                }
                else -> error("unexpected request ${request.method} ${request.url}")
            }
        }
        val result = authenticator(transport).authenticate(accountA, 7)
        assertEquals(
            UpcServicePortalAuthFailure.PROTOCOL_ERROR,
            (result as UpcServicePortalAuthResult.Failure).reason,
        )
        assertEquals(0, externalRequests)
    }

    @Test
    fun messageCountRejectsHtmlAndMalformedSuccessEnvelopes() = runTest {
        val invalidBodies = listOf(
            "<html><form>login</form></html>",
            "not-json",
            "{\"e\":0}",
            "{\"e\":\"0\",\"d\":{}}",
            "{\"e\":0.0,\"d\":{}}",
            "{\"e\":0,\"d\":[]}",
            "{\"e\":0,\"d\":null}",
        )
        invalidBodies.forEachIndexed { index, body ->
            val transport = successTransport(
                countBody = body,
                countContentType = if (index == 0) "text/html; charset=utf-8" else "application/json",
            )
            val result = authenticator(transport).authenticate(accountA, 7)
            assertEquals(
                UpcServicePortalAuthFailure.PROTOCOL_ERROR,
                (result as UpcServicePortalAuthResult.Failure).reason,
            )
        }
    }

    @Test
    fun parentDomainCookieIsNotExportedAndCannotSubstituteForHostCookie() = runTest {
        val transport = successTransport(
            callbackCookies = listOf(
                "parent=synthetic-parent; Domain=upc.edu.cn; Path=/; Secure",
            ),
        )
        val result = authenticator(transport).authenticate(accountA, 7)
        assertEquals(
            UpcServicePortalAuthFailure.UPSTREAM_ERROR,
            (result as UpcServicePortalAuthResult.Failure).reason,
        )
    }

    @Test
    fun unrelatedPathCookieCannotSubstituteForCookieSentToSessionCheck() = runTest {
        val transport = successTransport(
            callbackCookies = listOf("portal=unrelated-path; Path=/unrelated; Secure; HttpOnly"),
        )

        assertFailure(
            UpcServicePortalAuthFailure.UPSTREAM_ERROR,
            authenticator(transport).authenticate(accountA, 7),
        )
        val check = transport.requests.single { it.url == UpcServicePortalAuthEndpoints.SESSION_CHECK_URL }
        assertFalse(check.headers.containsKey("Cookie"))
    }

    @Test
    fun deletedCookieCannotCombineWithAnonymousSuccessJson() = runTest {
        val transport = successTransport(
            callbackCookies = listOf(
                "portal=active; Path=/; Secure; HttpOnly",
                "portal=; Max-Age=0; Path=/; Secure; HttpOnly",
            ),
        )

        assertFailure(
            UpcServicePortalAuthFailure.UPSTREAM_ERROR,
            authenticator(transport).authenticate(accountA, 7),
        )
        val check = transport.requests.single { it.url == UpcServicePortalAuthEndpoints.SESSION_CHECK_URL }
        assertFalse(check.headers.containsKey("Cookie"))
    }

    @Test
    fun sessionCheckSameValueCookieRenewalKeepsTheVerifiedCredential() = runTest {
        val transport = successTransport(
            callbackCookies = listOf("portal=stable-value; Path=/; Secure; HttpOnly; Max-Age=60"),
            sessionCheckCookies = listOf("portal=stable-value; Path=/; Secure; HttpOnly; Max-Age=600"),
        )

        val result = authenticator(transport).authenticate(accountA, 7)

        val success = result as UpcServicePortalAuthResult.Success
        assertEquals("stable-value", success.cookies.single().value)
        val check = transport.requests.single { it.url == UpcServicePortalAuthEndpoints.SESSION_CHECK_URL }
        assertTrue(check.headers["Cookie"].orEmpty().contains("portal=stable-value"))
    }

    @Test
    fun sessionCheckRejectsSameKeyCookieWithNewValue() = runTest {
        val transport = successTransport(
            callbackCookies = listOf("portal=sent-value; Path=/; Secure; HttpOnly"),
            sessionCheckCookies = listOf("portal=replacement-value; Path=/; Secure; HttpOnly"),
        )

        assertFailure(
            UpcServicePortalAuthFailure.UPSTREAM_ERROR,
            authenticator(transport).authenticate(accountA, 7),
        )
        val check = transport.requests.single { it.url == UpcServicePortalAuthEndpoints.SESSION_CHECK_URL }
        assertTrue(check.headers["Cookie"].orEmpty().contains("portal=sent-value"))
    }

    @Test
    fun sessionCheckRejectsSameValueCookieWhoseScopeChanged() = runTest {
        val transport = successTransport(
            callbackCookies = listOf("portal=stable-value; Path=/; Secure; HttpOnly"),
            sessionCheckCookies = listOf(
                "portal=stable-value; Domain=service.upc.edu.cn; Path=/; Secure; HttpOnly",
            ),
        )

        assertFailure(
            UpcServicePortalAuthFailure.UPSTREAM_ERROR,
            authenticator(transport).authenticate(accountA, 7),
        )
    }

    @Test
    fun sessionCheckDeletionDoesNotRetainCookieAsAuthenticationEvidence() = runTest {
        val transport = successTransport(
            callbackCookies = listOf("portal=sent-value; Path=/; Secure; HttpOnly"),
            sessionCheckCookies = listOf("portal=; Max-Age=0; Path=/; Secure; HttpOnly"),
        )

        assertFailure(
            UpcServicePortalAuthFailure.UPSTREAM_ERROR,
            authenticator(transport).authenticate(accountA, 7),
        )
        val check = transport.requests.single { it.url == UpcServicePortalAuthEndpoints.SESSION_CHECK_URL }
        assertTrue(check.headers["Cookie"].orEmpty().contains("portal=sent-value"))
    }

    @Test
    fun exactHostDomainCookieIsExportedWithItsHostOnlyFlag() = runTest {
        val transport = successTransport(
            callbackCookies = listOf(
                "host=synthetic-domain-value; Domain=service.upc.edu.cn; Path=/; Secure; HttpOnly",
            ),
        )
        val result = authenticator(transport).authenticate(accountA, 7)
        val cookie = (result as UpcServicePortalAuthResult.Success).cookies.single()
        assertEquals("service.upc.edu.cn", cookie.domain)
        assertFalse(cookie.hostOnly)
        assertTrue(cookie.httpOnly)
    }

    @Test
    fun tlsNetworkAndPostTimeoutFailuresAreDistinctAndNeverRetryPassword() = runTest {
        val tls = FakeTransport { throw UpcAuthTransportException(UpcAuthTransportFailure.TLS) }
        assertFailure(UpcServicePortalAuthFailure.TLS_ERROR, authenticator(tls).authenticate(accountA, 7))

        val offline = FakeTransport { throw UpcAuthTransportException(UpcAuthTransportFailure.NETWORK) }
        assertFailure(
            UpcServicePortalAuthFailure.NETWORK_UNAVAILABLE,
            authenticator(offline).authenticate(accountA, 7),
        )

        val timeout = FakeTransport { request ->
            if (request.method == "GET") loginPage()
            else throw UpcAuthTransportException(UpcAuthTransportFailure.TIMEOUT)
        }
        assertFailure(
            UpcServicePortalAuthFailure.NETWORK_UNAVAILABLE,
            authenticator(timeout).authenticate(accountA, 7),
        )
        assertEquals(1, timeout.posts)
    }

    @Test
    fun wrongAccountGenerationIsRejectedWithoutNetwork() = runTest {
        val transport = FakeTransport { error("credential mismatch must not access network") }
        val result = authenticator(transport).authenticate(accountB, 7)
        assertFailure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED, result)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun ssoReusesOnlyTheBoundInMemoryAccountGeneration() = runTest {
        val repository = FakeRepository(accountA, 7)
        var loginGets = 0
        var posts = 0
        var secondLoginHadCookie = false
        val transport = FakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcServicePortalAuthEndpoints.LOGIN_URL -> {
                    loginGets++
                    if (loginGets == 1) {
                        response(
                            200,
                            mapOf("content-type" to "text/html", "set-cookie" to "cas=sso-bound; Path=/; Secure"),
                            formPage(),
                        )
                    } else if (loginGets == 2) {
                        secondLoginHadCookie = request.headers["Cookie"].orEmpty().contains("cas=sso-bound")
                        response(
                            302,
                            mapOf("location" to callbackUrl()),
                        )
                    } else {
                        assertFalse(request.headers["Cookie"].orEmpty().contains("cas=sso-bound"))
                        loginPage()
                    }
                }
                request.method == "POST" -> {
                    posts++
                    response(302, mapOf("location" to callbackUrl()))
                }
                request.url == callbackUrl() -> responseWithCookies(
                    UpcServicePortalAuthEndpoints.REDIRECT_URL,
                    listOf("session=portal-cookie-test; Path=/; Secure; HttpOnly"),
                )
                request.url == UpcServicePortalAuthEndpoints.REDIRECT_URL -> response(200)
                request.url == UpcServicePortalAuthEndpoints.SESSION_CHECK_URL -> successCountResponse()
                else -> error("unexpected request ${request.method} ${request.url}")
            }
        }
        val auth = UpcServicePortalAuthenticator(repository, transport)

        val passwordLogin = auth.authenticate(accountA, 7) as UpcServicePortalAuthResult.Success
        val boundSso = auth.authenticate(accountA, 7) as UpcServicePortalAuthResult.Success
        assertEquals(UpcServicePortalAuthSource.PASSWORD, passwordLogin.source)
        assertEquals(UpcServicePortalAuthSource.BOUND_SSO, boundSso.source)
        assertEquals(SchoolAccountReference(accountA, AccountGeneration(7)), boundSso.binding)
        assertTrue(secondLoginHadCookie)
        assertEquals(1, posts)

        repository.generation = 8
        assertTrue(auth.authenticate(accountA, 8) is UpcServicePortalAuthResult.Success)
        assertEquals(2, posts)

        repository.accountId = accountB
        repository.generation = 9
        assertTrue(auth.authenticate(accountB, 9) is UpcServicePortalAuthResult.Success)
        assertEquals(3, posts)

        repository.accountId = accountA
        repository.generation = 10
        assertTrue(auth.authenticate(accountA, 10) is UpcServicePortalAuthResult.Success)
        assertEquals(4, posts)
    }

    @Test
    fun unboundInitialRedirectCannotBeTreatedAsSso() = runTest {
        var businessRequests = 0
        val transport = FakeTransport { request ->
            if (request.url == UpcServicePortalAuthEndpoints.LOGIN_URL) {
                response(302, mapOf("location" to callbackUrl()))
            } else {
                businessRequests++
                error("unbound CAS redirect must not reach the business host")
            }
        }
        val result = authenticator(transport).authenticate(accountA, 7)
        assertFailure(UpcServicePortalAuthFailure.PROTOCOL_ERROR, result)
        assertEquals(0, transport.posts)
        assertEquals(0, businessRequests)
    }

    @Test
    fun boundSessionThatReturnsPasswordFormIsReportedAsPasswordSource() = runTest {
        val repository = FakeRepository(accountA, 7)
        var loginGets = 0
        val transport = FakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcServicePortalAuthEndpoints.LOGIN_URL -> {
                    loginGets++
                    if (loginGets == 1) {
                        response(
                            200,
                            mapOf(
                                "content-type" to "text/html",
                                "set-cookie" to "cas=controlled-sso; Path=/; Secure",
                            ),
                            formPage(),
                        )
                    } else {
                        loginPage()
                    }
                }
                request.method == "POST" -> response(302, mapOf("location" to callbackUrl()))
                request.url == callbackUrl() -> responseWithCookies(
                    UpcServicePortalAuthEndpoints.REDIRECT_URL,
                    listOf("portal=sample_cookie_value; Path=/; Secure"),
                )
                request.url == UpcServicePortalAuthEndpoints.REDIRECT_URL -> response(200)
                request.url == UpcServicePortalAuthEndpoints.SESSION_CHECK_URL -> successCountResponse()
                else -> error("unexpected request ${request.method} ${request.url}")
            }
        }
        val auth = UpcServicePortalAuthenticator(repository, transport)

        val first = auth.authenticate(accountA, 7) as UpcServicePortalAuthResult.Success
        val second = auth.authenticate(accountA, 7) as UpcServicePortalAuthResult.Success
        assertEquals(UpcServicePortalAuthSource.PASSWORD, first.source)
        assertEquals(UpcServicePortalAuthSource.PASSWORD, second.source)
        assertEquals(2, transport.posts)
    }

    @Test
    fun busyAuthenticationIsRejectedWithoutQueueing() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = FakeRepository(accountA, 7) {
            entered.complete(Unit)
            release.await()
        }
        val auth = UpcServicePortalAuthenticator(repository, successTransport())
        val first = async { auth.authenticate(accountA, 7) }
        entered.await()

        assertFailure(
            UpcServicePortalAuthFailure.BUSY,
            auth.authenticate(accountA, 7),
        )
        release.complete(Unit)
        assertTrue(first.await() is UpcServicePortalAuthResult.Success)
    }

    @Test
    fun clearSessionInvalidatesInFlightResponseWithoutPostingPassword() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = FakeTransport { request ->
            if (request.method == "GET" && request.url == UpcServicePortalAuthEndpoints.LOGIN_URL) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                loginPage()
            } else {
                error("clearSession must prevent later requests")
            }
        }
        val auth = authenticator(transport)
        val inFlight = async(Dispatchers.IO) { auth.authenticate(accountA, 7) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            auth.clearSession()
        } finally {
            release.countDown()
        }

        assertFailure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED, inFlight.await())
        assertEquals(0, transport.posts)
    }

    @Test
    fun credentialGenerationChangeAfterSuccessfulNetworkDiscardsResult() = runTest {
        val repository = FakeRepository(accountA, 7).apply {
            failureAfterUse = SchoolCredentialFailure.CHANGED_DURING_USE
        }
        val result = UpcServicePortalAuthenticator(repository, successTransport())
            .authenticate(accountA, 7)
        assertFailure(UpcServicePortalAuthFailure.ACCOUNT_CHANGED, result)
    }

    @Test
    fun clearSessionDoesNotMakeNetworkRequests() = runTest {
        val transport = successTransport()
        val auth = authenticator(transport)
        auth.authenticate(accountA, 7)
        val count = transport.requests.size
        auth.clearSession()
        assertEquals(count, transport.requests.size)
    }

    private fun authenticator(transport: UpcAuthTransport): UpcServicePortalAuthenticator =
        UpcServicePortalAuthenticator(FakeRepository(accountA, 7), transport)

    private fun successTransport(
        requestObserver: ((UpcAuthHttpRequest) -> Unit)? = null,
        callbackCookies: List<String> = listOf(
            "session=portal-cookie-test; Path=/; Secure; HttpOnly",
        ),
        sessionCheckCookies: List<String> = emptyList(),
        countBody: String = "{\"e\":0,\"d\":{\"num\":0}}",
        countContentType: String = "application/json; charset=utf-8",
        loginPageHtml: String = formPage(),
        loginStatus: Int = 200,
    ) = FakeTransport { request ->
        requestObserver?.invoke(request)
        when {
            request.method == "GET" && request.url == UpcServicePortalAuthEndpoints.LOGIN_URL ->
                loginPage(body = loginPageHtml, status = loginStatus)
            request.method == "POST" -> response(
                302,
                mapOf("location" to callbackUrl()),
            )
            request.url == callbackUrl() -> responseWithCookies(
                UpcServicePortalAuthEndpoints.REDIRECT_URL,
                callbackCookies,
            )
            request.url == UpcServicePortalAuthEndpoints.REDIRECT_URL -> response(200)
            request.url == UpcServicePortalAuthEndpoints.SESSION_CHECK_URL -> response(
                200,
                mapOf("content-type" to countContentType),
                countBody,
                setCookieHeaders = sessionCheckCookies,
            )
            else -> error("unexpected request ${request.method} ${request.url}")
        }
    }

    private fun loginPage(
        captcha: Boolean = false,
        secondFactor: Boolean = false,
        body: String? = null,
        status: Int = 200,
    ): UpcAuthHttpResponse = response(
        status,
        mapOf("content-type" to "text/html; charset=utf-8"),
        body ?: formPage(captcha, secondFactor),
    )

    private fun formPage(
        captcha: Boolean = false,
        secondFactor: Boolean = false,
    ): String = """
        <html><form action="${UpcServicePortalAuthEndpoints.LOGIN_URL}" method="post">
          <input name="username"><input name="password" type="password">
          <input name="execution" value="synthetic-execution">
          <input name="type" value="username_password">
          <input name="_eventId" value="submit">
          ${if (secondFactor) "<input name=\"username_smstoken\" value=\"\">" else ""}
        </form>
        <div id="captchaParent" style="${if (captcha) "display:block" else "display:none"}"></div></html>
    """.trimIndent()

    private fun successCountResponse() = response(
        200,
        mapOf("content-type" to "application/json"),
        "{\"e\":0,\"d\":{\"num\":0}}",
    )

    private fun callbackUrl(ticket: String = "ST-synthetic-ticket") =
        "${UpcServicePortalAuthEndpoints.CALLBACK_SERVICE_URL}&ticket=$ticket"

    private fun response(
        status: Int,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        setCookieHeaders: List<String> = emptyList(),
    ): UpcAuthHttpResponse {
        val responseHeaders = headers.mapKeys { it.key.lowercase() }
            .mapValues { listOf(it.value) }
            .toMutableMap()
        if (setCookieHeaders.isNotEmpty()) responseHeaders["set-cookie"] = setCookieHeaders
        return UpcAuthHttpResponse(
            status = status,
            headers = responseHeaders,
            body = body.toByteArray(),
        )
    }

    private fun responseWithCookies(
        location: String,
        cookies: List<String>,
    ) = UpcAuthHttpResponse(
        status = 302,
        headers = mapOf("location" to listOf(location), "set-cookie" to cookies),
        body = ByteArray(0),
    )

    private fun assertFailure(
        expected: UpcServicePortalAuthFailure,
        actual: UpcServicePortalAuthResult,
    ) {
        assertEquals(expected, (actual as UpcServicePortalAuthResult.Failure).reason)
    }

    private fun String.toHttpUrlOrNullOrFail(): okhttp3.HttpUrl =
        toHttpUrlOrNull() ?: error("invalid synthetic URL")

    private class FakeRepository(
        var accountId: SchoolAccountId,
        var generation: Long,
        private val beforeUse: suspend () -> Unit = {},
    ) : SchoolCredentialAccess {
        var failureAfterUse: SchoolCredentialFailure? = null

        override suspend fun <T> withLoginCredential(
            block: suspend (SchoolLoginCredential) -> T,
        ): SchoolCredentialUseResult<T> {
            beforeUse()
            val credential = SchoolLoginCredential(
                accountId = accountId,
                credentialGeneration = AccountGeneration(generation),
                loginName = "synthetic-login",
                password = "synthetic-password".toCharArray(),
            )
            val value = try {
                block(credential)
            } finally {
                credential.close()
            }
            val failure = failureAfterUse
            return if (failure == null) SchoolCredentialUseResult.Success(value)
            else SchoolCredentialUseResult.Failure(failure)
        }
    }

    private class FakeTransport(
        private val handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse,
    ) : UpcAuthTransport {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val posts: Int
            get() = requests.count { it.method == "POST" }

        override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
            requests += request
            return handler(request)
        }
    }
}
