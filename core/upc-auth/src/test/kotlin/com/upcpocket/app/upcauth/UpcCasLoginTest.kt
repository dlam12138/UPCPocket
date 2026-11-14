package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(RestrictedSchoolCredentialUse::class)
class UpcCasLoginTest {
    private val accountId = SchoolAccountId("acct_synthetic_cas")
    private val callbackUrl = "https://service.upc.edu.cn/callback"
    private val service = UpcCasService(
        casHost = "cas.upc.edu.cn",
        serviceUrl = callbackUrl,
        callbackHost = "service.upc.edu.cn",
        callbackPath = "/callback",
        callbackParameterNames = setOf("ticket"),
    )
    private val casUrl = "https://cas.upc.edu.cn/cas/login?service=" +
        URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8.name()) + "&from=wap&noAutoRedirect=1"
    private val passwordForm = """
        <form method="post" action="$casUrl">
          <input name="username"><input type="password" name="password">
          <input name="type" value="username_password">
          <input name="execution" value="synthetic-execution"><input name="_eventId" value="submit">
        </form>
    """.trimIndent()

    @Test
    fun staticSmsAndQrTabsFromExistingShiguangFixtureDoNotBlockSharedCasPasswordLogin() {
        val staticAlternatives = """
            <nav>扫码登录 验证码登录</nav>
            <div>帮助：扫码登录 / 验证码登录</div>
            <form id="smsForm" style="display:none"><input name="type" value="username_smstoken"><input name="captcha"></form>
        """.trimIndent()
        val transport = CasFakeTransport { request ->
            when (request.method) {
                "GET" -> response(200, body = passwordForm + staticAlternatives)
                "POST" -> response(302, headers = mapOf("location" to callback("ST-synthetic-ticket")))
                else -> error("unexpected method")
            }
        }
        val credential = credential()

        val result = try {
            UpcCasLogin(service, transport).login(credential, casUrl)
        } finally {
            credential.close()
        }

        assertTrue(result is UpcCasLoginResult.Callback)
        assertTrue((result as UpcCasLoginResult.Callback).passwordSubmitted)
        assertEquals(1, transport.requests.count { it.method == "POST" })
    }

    @Test
    fun activeSecondFactorOnSelectedPasswordFormStopsBeforePasswordSubmission() {
        val transport = CasFakeTransport { response(200, body = passwordForm.replace(
            "</form>", "<input name=\"username_smstoken\" value=\"\"></form>",
        )) }
        val credential = credential()

        val result = try {
            UpcCasLogin(service, transport).login(credential, casUrl)
        } finally {
            credential.close()
        }

        assertEquals(UpcAuthFailure.INTERACTION_REQUIRED, (result as UpcCasLoginResult.Failure).reason)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.none { it.method == "POST" })
    }

    @Test
    fun ordinaryDeniedGetFormsAllowOnePasswordPostForBoth401And403() {
        for (status in listOf(401, 403)) {
            val transport = CasFakeTransport { request ->
                when (request.method) {
                    "GET" -> response(status, body = passwordForm)
                    "POST" -> response(302, headers = mapOf("location" to callback("ST-synthetic-ticket")))
                    else -> error("unexpected method")
                }
            }
            val credential = credential()
            val result = try {
                UpcCasLogin(service, transport).login(credential, casUrl)
            } finally {
                credential.close()
            }
            assertTrue("GET $status", result is UpcCasLoginResult.Callback)
            assertEquals("GET $status", 1, transport.requests.count { it.method == "POST" })
        }
    }

    @Test
    fun publicCasPostFormWithoutAnActionUsesTheExactCurrentServiceUrl() {
        val publicCasShape = """
            <form method="post">
              <input name="username"><input name="password" type="password">
              <input name="submit" value="登录">
              <input name="type" value="username_password">
              <input name="execution" value="synthetic-execution">
              <input name="_eventId" value="submit">
            </form>
        """.trimIndent()
        val transport = CasFakeTransport { request ->
            when (request.method) {
                "GET" -> response(200, body = publicCasShape)
                "POST" -> response(302, headers = mapOf("location" to callback("ST-synthetic-ticket")))
                else -> error("unexpected method")
            }
        }
        val credential = credential()

        val result = try {
            UpcCasLogin(service, transport).login(credential, casUrl)
        } finally {
            credential.close()
        }

        assertTrue(result is UpcCasLoginResult.Callback)
        assertTrue((result as UpcCasLoginResult.Callback).passwordSubmitted)
        assertEquals(casUrl, transport.requests.single { it.method == "POST" }.url)
    }

    @Test
    fun validExecutionLargerThanTheLegacyFourKiBCapIsSubmittedExactlyOnce() {
        val execution = "e".repeat(4_097)
        val longForm = passwordForm.replace("synthetic-execution", execution)
        val transport = CasFakeTransport { request ->
            when (request.method) {
                "GET" -> response(200, body = longForm)
                "POST" -> response(302, headers = mapOf("location" to callback("ST-synthetic-ticket")))
                else -> error("unexpected method")
            }
        }
        val credential = credential()

        val result = try {
            UpcCasLogin(service, transport).login(credential, casUrl)
        } finally {
            credential.close()
        }

        assertTrue(result is UpcCasLoginResult.Callback)
        assertTrue((result as UpcCasLoginResult.Callback).passwordSubmitted)
        assertEquals(1, transport.requests.count { it.method == "POST" })
        assertTrue(transport.requests.single { it.method == "POST" }.body.orEmpty().contains("execution=$execution"))
    }

    @Test
    fun passwordFormValidationExposesOnlyFixedRejectionRules() {
        fun rejection(html: String) = casPasswordFormRejection(
            parseCasLoginForm(html), casUrl, service,
        )

        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_PASSWORD_FIELD_MISSING,
            rejection(passwordForm.replace("type=\"password\"", "type=\"text\"")),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_TYPE_FIELD_MISSING,
            rejection(passwordForm.replace("<input name=\"type\" value=\"username_password\">", "")),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_TYPE_REJECTED,
            rejection(passwordForm.replace("username_password", "other")),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_EVENT_FIELD_MISSING,
            rejection(passwordForm.replace("<input name=\"_eventId\" value=\"submit\">", "")),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_EVENT_REJECTED,
            rejection(passwordForm.replace("name=\"_eventId\" value=\"submit\"", "name=\"_eventId\" value=\"continue\"")),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_EXECUTION_MISSING,
            rejection(passwordForm.replace("<input name=\"execution\" value=\"synthetic-execution\">", "")),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_EXECUTION_INVALID,
            rejection(passwordForm.replace("synthetic-execution", " ")),
        )
        listOf("\u0001", "\u007f").forEach { control ->
            assertEquals(
                com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_EXECUTION_INVALID,
                rejection(passwordForm.replace("synthetic-execution", control)),
            )
        }
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_ACTION_REJECTED,
            rejection(passwordForm.replace(casUrl, "https://invalid.example/cas/login")),
        )
        assertNull(rejection(passwordForm.replace("synthetic-execution", "e".repeat(4_097))))
        assertNull(rejection(passwordForm.replace(
            "synthetic-execution", "e".repeat(MAX_CAS_EXECUTION_CHARS),
        )))
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_EXECUTION_INVALID,
            rejection(passwordForm.replace(
                "synthetic-execution", "e".repeat(MAX_CAS_EXECUTION_CHARS + 1),
            )),
        )
        assertEquals(
            com.upcpocket.app.platform.diagnostics.DiagnosticDetail.CAS_FORM_NOT_FOUND,
            casPasswordFormRejection(null, casUrl, service),
        )
    }

    @Test
    fun realChallengeAndRateOrServerFailuresStopBeforePasswordPost() {
        val captcha = passwordForm + "<script>config.captcha = { id: 'synthetic' };</script>"
        val challenge = CasFakeTransport { response(403, body = captcha) }
        val challengeCredential = credential()
        val challengeResult = try {
            UpcCasLogin(service, challenge).login(challengeCredential, casUrl)
        } finally {
            challengeCredential.close()
        }
        assertEquals(UpcAuthFailure.INTERACTION_REQUIRED, (challengeResult as UpcCasLoginResult.Failure).reason)
        assertEquals(0, challenge.requests.count { it.method == "POST" })

        listOf(429 to UpcAuthFailure.ACCESS_DENIED, 500 to UpcAuthFailure.UPSTREAM_ERROR).forEach { (status, reason) ->
            val transport = CasFakeTransport { response(status, body = passwordForm) }
            val credential = credential()
            val result = try {
                UpcCasLogin(service, transport).login(credential, casUrl)
            } finally {
                credential.close()
            }
            assertEquals("HTTP $status", reason, (result as UpcCasLoginResult.Failure).reason)
            assertEquals(0, transport.requests.count { it.method == "POST" })
        }
    }

    @Test
    fun unknownPostRefusalIsNotMisclassifiedAsBadPasswordOrRetried() {
        val transport = CasFakeTransport { request ->
            if (request.method == "GET") response(200, body = passwordForm)
            else response(401, body = passwordForm)
        }
        val credential = credential()
        val result = try {
            UpcCasLogin(service, transport).login(credential, casUrl)
        } finally {
            credential.close()
        }
        assertEquals(UpcAuthFailure.INTERACTION_REQUIRED, (result as UpcCasLoginResult.Failure).reason)
        assertEquals(casUrl, result.interactionUrl)
        assertEquals(1, transport.requests.count { it.method == "POST" })
    }

    @Test
    fun visibleSecondFactorFormWinsOverHiddenPasswordAlternative() {
        val hiddenPassword = passwordForm.replace("<form method", "<form hidden method")
        val activeSmsForm = """
            <form id="smsForm"><input name="type" value="username_smstoken"><input name="smsCode"></form>
        """.trimIndent()
        val transport = CasFakeTransport { response(200, body = hiddenPassword + activeSmsForm) }
        val credential = credential()

        val result = try {
            UpcCasLogin(service, transport).login(credential, casUrl)
        } finally {
            credential.close()
        }

        assertEquals(UpcAuthFailure.INTERACTION_REQUIRED, (result as UpcCasLoginResult.Failure).reason)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.none { it.method == "POST" })
    }

    @Test
    fun callbackStringFormDoesNotExposeTicket() {
        val callback = UpcCasLoginResult.Callback(
            url = callback("ST-synthetic-ticket").toHttpUrlOrNull()!!,
            session = UpcCasHttpSession(CasFakeTransport { response(200) },
                UpcAuthCookieJar(setOf("cas.upc.edu.cn", "service.upc.edu.cn"))),
        )

        assertFalse(callback.toString().contains("ticket=ST-"))
    }

    @Test
    fun cookieJarDeletesByNameDomainAndPathAndFiltersExpiredCookiesBeforeSending() {
        val jar = UpcAuthCookieJar(setOf("cas.upc.edu.cn"))
        val login = "https://cas.upc.edu.cn/cas/login"
        jar.save(login, listOf(
            "shared=root; Path=/; Secure",
            "shared=nested; Path=/cas; Secure",
            "expired=max-age; Path=/; Max-Age=0; Secure",
            "expired=date; Path=/; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Secure",
        ))

        val beforeDelete = jar.header(login).orEmpty()
        assertTrue(beforeDelete.contains("shared=root"))
        assertTrue(beforeDelete.contains("shared=nested"))
        assertFalse(beforeDelete.contains("expired="))

        jar.save(login, listOf("shared=; Path=/cas; Max-Age=0; Secure"))

        val afterDelete = jar.header(login).orEmpty()
        assertTrue(afterDelete.contains("shared=root"))
        assertFalse(afterDelete.contains("shared=nested"))
        assertEquals(1, jar.cookiesForHost("cas.upc.edu.cn").count { it.name == "shared" })
    }

    @Test
    fun sentCookieIdentityComparesValueAndScopeWithoutExposingValue() {
        val identity = UpcAuthCookieReference(
            name = "portal",
            value = "synthetic-secret",
            domain = "service.upc.edu.cn",
            path = "/",
            secure = true,
            httpOnly = true,
            hostOnly = true,
        )

        assertTrue(identity.matches("portal", "synthetic-secret", "service.upc.edu.cn", "/", true, true, true))
        assertFalse(identity.matches("portal", "replacement-secret", "service.upc.edu.cn", "/", true, true, true))
        assertFalse(identity.matches("portal", "synthetic-secret", "service.upc.edu.cn", "/", true, true, false))
        assertFalse(identity.toString().contains("synthetic-secret"))
    }

    private fun credential() = SchoolLoginCredential(
        accountId = accountId,
        credentialGeneration = AccountGeneration(3),
        loginName = "synthetic-login",
        password = "synthetic-password".toCharArray(),
    )

    private fun callback(ticket: String) = "$callbackUrl?ticket=$ticket"

    private fun response(
        status: Int,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ) = UpcAuthHttpResponse(
        status = status,
        headers = headers.mapKeys { it.key.lowercase() }.mapValues { listOf(it.value) },
        body = body.toByteArray(),
    )

    private class CasFakeTransport(
        private val handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse,
    ) : UpcAuthTransport {
        val requests = mutableListOf<UpcAuthHttpRequest>()

        override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
            requests += request
            return handler(request)
        }
    }
}
