package com.upcpocket.app.upcauth

import com.upcpocket.app.account.RestrictedSchoolCredentialUse
import com.upcpocket.app.account.SchoolCredentialAccess
import com.upcpocket.app.account.SchoolCredentialFailure
import com.upcpocket.app.account.SchoolCredentialUseResult
import com.upcpocket.app.account.SchoolLoginCredential
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

@OptIn(RestrictedSchoolCredentialUse::class)
class UpcShiguangAuthenticatorTest {
    private val accountId = SchoolAccountId("acct_synthetic_shiguang")
    private val form = """<form><input name="username"><input type="password" name="password"><input name="execution" value="synthetic-execution">
        <input name="type" value="username_password"><input name="_eventId" value="submit"></form>
        <div id="captchaParent" style="display:none"><input type="hidden" name="captcha"></div>
        <!-- <input name="captcha"> -->
        <script>const selector = "[name='captcha']"; config.captcha = null; config.captcha = false; config.captcha = undefined;</script>"""
    private val callback = "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-synthetic-ticket"
    private val listing = """<html><div class="active_2_list"></div></html>"""

    @Test fun `service bytes stay HTTP while all requests and ticket callback are HTTPS`() = runTest {
        val transport = successful()
        val result = auth(transport).authenticate(accountId, 4) as UpcShiguangAuthResult.Success
        assertTrue(result.identityVerified)
        assertEquals("http://dekt.upc.edu.cn/caslogin.jsp",
            transport.requests.first().url.toHttpUrl().queryParameter("service"))
        assertTrue(transport.requests.all { it.url.startsWith("https://") })
        assertEquals(1, transport.requests.count { it.method == "POST" && it.url.contains("cas.upc.edu.cn") })
        assertEquals(1, transport.requests.count { it.method == "POST" && it.url == UpcShiguangAuthEndpoints.REPORT_URL })
        assertEquals("https://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-synthetic-ticket", transport.requests[2].url)
        val report = transport.requests.last()
        assertEquals("", report.body)
        assertEquals(UpcShiguangAuthEndpoints.REPORT_REFERER, report.headers["Referer"])
    }

    @Test fun `exact callback rejects host port userinfo path tricks duplicate query and fragment`() {
        val invalid = listOf(
            "http://evil.invalid/caslogin.jsp?ticket=ST-synthetic",
            "http://dekt.upc.edu.cn.evil.invalid/caslogin.jsp?ticket=ST-synthetic",
            "http://dekt.upc.edu.cn:80/caslogin.jsp?ticket=ST-synthetic",
            "https://dekt.upc.edu.cn:443/caslogin.jsp?ticket=ST-synthetic",
            "http://user@dekt.upc.edu.cn/caslogin.jsp?ticket=ST-synthetic",
            "http://dekt.upc.edu.cn/a/../caslogin.jsp?ticket=ST-synthetic",
            "http://dekt.upc.edu.cn/%2e/caslogin.jsp?ticket=ST-synthetic",
            "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-synthetic&ticket=ST-other",
            "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-synthetic&other=1",
            "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-synthetic#fragment",
            "http://dekt.upc.edu.cn/caslogin.jsp?ticket=not-ticket",
            "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-synthetic%0d%0aCookie%3Ainjected",
        )
        invalid.forEach { assertNull(upgradeShiguangTicketCallback(it)) }
        assertNotNull(upgradeShiguangTicketCallback(callback))
        val opaqueCallback = callback.replace("ST-synthetic-ticket", "ST-synthetic.ticket%2Bopaque%2Fpart%3D")
        val upgraded = upgradeShiguangTicketCallback(opaqueCallback)
        assertNotNull(upgraded)
        assertEquals("ST-synthetic.ticket+opaque/part=", upgraded!!.queryParameter("ticket"))
        assertEquals(opaqueCallback.replace("http://", "https://"), upgraded.toString())
    }

    @Test fun `untrusted callback never receives ticket or credential request`() = runTest {
        listOf(302, 303, 307, 308).forEach { status ->
            val transport = fake { request ->
                if (request.method == "GET") response(200, form)
                else response(status, headers = mapOf("location" to listOf("https://evil.invalid/?ticket=ST-synthetic")))
            }
            assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR, auth(transport).authenticate(accountId, 4))
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun `307 and 308 never replay the password even to a valid callback`() = runTest {
        listOf(307, 308).forEach { status ->
            val transport = fake { request ->
                if (request.method == "GET") response(200, form)
                else response(status, headers = mapOf("location" to listOf(callback)))
            }
            assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR, auth(transport).authenticate(accountId, 4))
            assertEquals(1, transport.requests.count { it.method == "POST" })
        }
    }

    @Test fun `challenge pages never submit a password and 401 403 preserve interaction semantics`() = runTest {
        listOf(200, 401, 403).forEach { status ->
            val transport = fake { response(status, form + "<input name='captcha'>" +
                "<form><input name='captcha'></form>") }
            // Existing parser takes the password form; explicit captcha configuration is a page marker.
            val challenge = fake { response(status, form + "<script>config.captcha=true</script>") }
            assertFailure(UpcShiguangAuthFailure.INTERACTION_REQUIRED, auth(challenge).authenticate(accountId, 4))
            assertEquals(1, challenge.requests.size)
            if (status != 200) {
                assertFailure(UpcShiguangAuthFailure.INTERACTION_REQUIRED, auth(transport).authenticate(accountId, 4))
            }
        }
    }

    @Test fun `only explicit credential rejection is called invalid while unknown post refusal offers the school page`() = runTest {
        val wrong = fake { if (it.method == "GET") response(200, form) else response(200, form + "<div id='errorDiv'>用户名或密码错误</div>") }
        assertFailure(UpcShiguangAuthFailure.INVALID_CREDENTIALS, auth(wrong).authenticate(accountId, 4))
        assertEquals(1, wrong.requests.count { it.method == "POST" })
        listOf(401, 403).forEach { status ->
            val denied = fake { if (it.method == "GET") response(status, form) else response(status, form) }
            assertFailure(UpcShiguangAuthFailure.INTERACTION_REQUIRED, auth(denied).authenticate(accountId, 4))
            assertEquals(1, denied.requests.count { it.method == "POST" })
        }
        listOf(429 to UpcShiguangAuthFailure.ACCESS_DENIED,
            500 to UpcShiguangAuthFailure.UPSTREAM_ERROR,
            200 to UpcShiguangAuthFailure.PROTOCOL_ERROR,
            403 to UpcShiguangAuthFailure.ACCESS_DENIED).forEach { (status, reason) ->
            assertFailure(reason, auth(fake { response(status, "<html>unavailable</html>") }).authenticate(accountId, 4))
        }
        listOf(UpcAuthTransportFailure.TLS to UpcShiguangAuthFailure.TLS_ERROR,
            UpcAuthTransportFailure.NETWORK to UpcShiguangAuthFailure.NETWORK_UNAVAILABLE,
            UpcAuthTransportFailure.TIMEOUT to UpcShiguangAuthFailure.NETWORK_UNAVAILABLE).forEach { (error, reason) ->
            assertFailure(reason, auth(fake { throw UpcAuthTransportException(error) }).authenticate(accountId, 4))
        }
    }

    @Test fun `post 401 403 challenges are interaction and never retried`() = runTest {
        listOf(401, 403).forEach { status ->
            val challenge = form + "<script>config.captcha=true</script>"
            val transport = fake { if (it.method == "GET") response(200, form) else response(status, challenge) }
            assertFailure(UpcShiguangAuthFailure.INTERACTION_REQUIRED, auth(transport).authenticate(accountId, 4))
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun `static SMS and QR tabs beside a valid password form allow one normal submission`() = runTest {
        val tabs = "<nav>扫码登录 验证码登录</nav><form><input name='username_smstoken'></form>"
        val transport = successful(loginPage = form + tabs)
        assertTrue(auth(transport).authenticate(accountId, 4) is UpcShiguangAuthResult.Success)
        assertEquals(1, transport.requests.count { it.method == "POST" && it.url == UpcShiguangAuthEndpoints.LOGIN_URL })
    }

    @Test fun `ordinary GET 401 and 403 password forms allow one successful submission`() = runTest {
        listOf(401, 403).forEach { status ->
            val transport = successful(loginStatus = status)
            assertTrue("GET $status", auth(transport).authenticate(accountId, 4) is UpcShiguangAuthResult.Success)
            assertEquals("GET $status", 1, transport.requests.count {
                it.method == "POST" && it.url == UpcShiguangAuthEndpoints.LOGIN_URL
            })
        }
    }

    @Test fun `bounded execution above legacy four KiB limit is submitted on Shiguang`() = runTest {
        val execution = "e".repeat(4_097)
        val transport = successful(loginPage = form.replace("synthetic-execution", execution))

        assertTrue(auth(transport).authenticate(accountId, 4) is UpcShiguangAuthResult.Success)
        assertEquals(1, transport.requests.count {
            it.method == "POST" && it.url == UpcShiguangAuthEndpoints.LOGIN_URL
        })
        assertTrue(transport.requests.single {
            it.method == "POST" && it.url == UpcShiguangAuthEndpoints.LOGIN_URL
        }.body.orEmpty().contains("execution=$execution"))
    }

    @Test fun `execution above shared size cap stops before Shiguang password submission`() = runTest {
        val oversized = "e".repeat(MAX_CAS_EXECUTION_CHARS + 1)
        val transport = successful(loginPage = form.replace("synthetic-execution", oversized))

        assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR, auth(transport).authenticate(accountId, 4))
        assertEquals(0, transport.requests.count { it.method == "POST" })
    }

    @Test fun `execution controls and untrusted CAS action stop before Shiguang password submission`() = runTest {
        listOf("\u0001", "\u007f").forEach { control ->
            val controlled = successful(loginPage = form.replace("synthetic-execution", control))
            assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR, auth(controlled).authenticate(accountId, 4))
            assertEquals(0, controlled.requests.count { it.method == "POST" })
        }

        val evilAction = successful(loginPage = form.replace(
            "<form>", "<form action=\"https://evil.invalid/cas/login?service=https%3A%2F%2Fdekt.upc.edu.cn%2Fcaslogin.jsp\">",
        ))
        assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR, auth(evilAction).authenticate(accountId, 4))
        assertEquals(0, evilAction.requests.count { it.method == "POST" })
    }

    @Test fun `unknown post refusal with static second factor tabs offers interaction without retry`() = runTest {
        val page = form + "<nav>扫码登录 验证码登录 username_smstoken</nav>"
        val transport = fake { response(200, page) }
        assertFailure(UpcShiguangAuthFailure.INTERACTION_REQUIRED, auth(transport).authenticate(accountId, 4))
        assertEquals(1, transport.requests.count { it.method == "POST" })
    }

    @Test fun `second factor without a usable password form requires interaction without submitting`() = runTest {
        listOf(
            "<form><input name='username_smstoken'></form>",
            "<form><input type='hidden' name='type' value='username_smstoken'><input name='smsCode'></form>",
            form.replace("</form>", "<input name='username_smstoken' value=''></form>"),
        ).forEach { page ->
            val transport = fake { response(200, page) }
            assertFailure(UpcShiguangAuthFailure.INTERACTION_REQUIRED, auth(transport).authenticate(accountId, 4))
            assertEquals(0, transport.requests.count { it.method == "POST" })
        }
    }

    @Test fun `static login labels without an active challenge are not treated as second factor`() = runTest {
        listOf("<html>扫码登录</html>", "<html>验证码登录</html>").forEach { page ->
            val transport = fake { response(200, page) }
            assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR, auth(transport).authenticate(accountId, 4))
            assertEquals(0, transport.requests.count { it.method == "POST" })
        }
    }

    @Test fun `identity requires all populated records to have exact matching student numbers`() = runTest {
        val verified = listOf(
            """{"gridModel":[{"stuNo":"synthetic-user"}]}""",
            """{"gridModel":[{"stuNo":"synthetic-user"},{"stuNo":"synthetic-user"}]}""",
        )
        verified.forEach { assertTrue((auth(successful(it)).authenticate(accountId, 4) as UpcShiguangAuthResult.Success).identityVerified) }
        val unverified = listOf("{}", """{"gridModel":[]}""", """{"gridModel":[{}]}""",
            """{"gridModel":[{"stuNo":"synthetic-user"},{}]}""",
            """{"gridModel":[{"hours":1}]}""",
            """{"gridModel":[{"stuNo":"synthetic-user"},{"hours":1}]}""")
        unverified.forEach { assertFalse((auth(successful(it)).authenticate(accountId, 4) as UpcShiguangAuthResult.Success).identityVerified) }
        listOf("""{"gridModel":[{"stuNo":"other-synthetic"}]}""",
            """{"gridModel":[{"stuNo":"synthetic-user"},{"stuNo":"other-synthetic"}]}""",
            """{"gridModel":[{"stuNo":" synthetic-user"}]}""").forEach {
            assertFailure(UpcShiguangAuthFailure.IDENTITY_MISMATCH, auth(successful(it)).authenticate(accountId, 4))
        }
    }

    @Test fun `CAS and parent domain cookies never cross to business or returned session`() = runTest {
        val transport = successful()
        val success = auth(transport).authenticate(accountId, 4) as UpcShiguangAuthResult.Success
        assertEquals(listOf("SESSION"), success.cookies.map { it.name })
        assertTrue(success.cookies.all { it.domain == UpcShiguangAuthEndpoints.HOST })
        transport.requests.filter { it.url.contains("dekt.upc.edu.cn") && !it.url.contains("cas.upc.edu.cn") }.forEach {
            assertFalse(it.headers["Cookie"].orEmpty().contains("CAS_SECRET"))
            assertFalse(it.headers["Cookie"].orEmpty().contains("PARENT_SECRET"))
        }
        assertFalse(success.toString().contains("synthetic-business-secret"))
        assertFalse(success.cookies.toString().contains("synthetic-business-secret"))
    }

    @Test fun `missing session or activity structure rejects an unauthenticated page`() = runTest {
        assertFailure(UpcShiguangAuthFailure.ACCESS_DENIED, auth(successful(sessionCookie = false)).authenticate(accountId, 4))
        assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR, auth(successful(listBody = form)).authenticate(accountId, 4))
    }

    @Test fun `second stage accepts only fixed path with one userName and upgrades observed HTTP redirects`() = runTest {
        val transport = successful(secondStage = "/sysmng/login!loginByUserNoForCas.action?userName=synthetic-user")
        assertTrue(auth(transport).authenticate(accountId, 4) is UpcShiguangAuthResult.Success)
        assertTrue(transport.requests.all { it.url.startsWith("https://") })
        listOf("https://evil.invalid/sysmng/login!loginByUserNoForCas.action?userName=x",
            "/sysmng/login!loginByUserNoForCas.action?userName=x&userName=y",
            "/sysmng/../sysmng/login!loginByUserNoForCas.action?userName=x",
            "https://dekt.upc.edu.cn:443/sysmng/login!loginByUserNoForCas.action?userName=x",
            "/actvMngAjax/actvMngFront!goEnrollInForWeb.action?activityId=x").forEach {
            assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR, auth(successful(secondStage = it)).authenticate(accountId, 4))
        }
    }

    @Test fun `account or generation mismatch prevents requests and changed during use discards session`() = runTest {
        val noNetwork = fake { error("Unexpected synthetic request") }
        assertFailure(UpcShiguangAuthFailure.ACCOUNT_CHANGED, auth(noNetwork).authenticate(accountId, 5))
        assertFailure(UpcShiguangAuthFailure.ACCOUNT_CHANGED, auth(noNetwork).authenticate(SchoolAccountId("acct_other_synthetic"), 4))
        assertTrue(noNetwork.requests.isEmpty())
        assertFailure(UpcShiguangAuthFailure.ACCOUNT_CHANGED, auth(successful(), changed = true).authenticate(accountId, 4))
    }

    @Test fun `cancellation is propagated`() = runTest {
        try {
            auth(fake { throw CancellationException() }).authenticate(accountId, 4)
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun `cancelling externally during initial GET prevents the password POST`() = runTest {
        val firstGetStarted = CountDownLatch(1)
        val releaseFirstGet = CountDownLatch(1)
        val transport = fake { request ->
            if (request.method == "GET") {
                firstGetStarted.countDown()
                check(releaseFirstGet.await(5, TimeUnit.SECONDS))
                response(200, form)
            } else {
                error("Cancelled login must not submit a password")
            }
        }
        val job = launch(Dispatchers.Default) { auth(transport).authenticate(accountId, 4) }
        try {
            withContext(Dispatchers.IO) { assertTrue(firstGetStarted.await(5, TimeUnit.SECONDS)) }
            job.cancel()
        } finally { releaseFirstGet.countDown() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, transport.requests.size)
        assertEquals(0, transport.requests.count { it.method == "POST" })
    }

    @Test fun `web ticket uses no password or CAS cookie and retains HTTPS business and identity probes`() = runTest {
        listOf(callback, callback.replace("http://", "https://")).forEach { ticket ->
            val transport = successful(secondStage = "/sysmng/login!loginByUserNoForCas.action?userName=synthetic-user")
            // A released password makes any accidental usePassword call fail this success assertion.
            val authenticator = auth(transport, releasePassword = true)
            assertEquals(UpcShiguangAuthEndpoints.LOGIN_URL, authenticator.entryUrl)
            assertTrue(authenticator.inspectWebNavigation(ticket, "GET", false, ShiguangWebRequestKind.NAVIGATION)
                is UpcShiguangWebNavigation.Complete)
            val success = authenticator.completeWebLoginResult(accountId, 4, ticket) as UpcShiguangAuthResult.Success
            assertTrue(success.identityVerified)
            assertEquals(listOf("SESSION"), success.cookies.map { it.name })
            assertEquals(callback.replace("http://", "https://"), transport.requests.first().url)
            assertNull(transport.requests.first().headers["Cookie"])
            assertTrue(transport.requests.all { it.url.startsWith(UpcShiguangAuthEndpoints.BASE) })
            assertEquals(0, transport.requests.count { it.url.contains("cas.upc.edu.cn") })
            assertEquals(listOf(UpcShiguangAuthEndpoints.REPORT_URL), transport.requests.filter { it.method == "POST" }.map { it.url })
            assertEquals(1, transport.requests.count { it.url == UpcShiguangAuthEndpoints.LIST_URL })
            assertEquals("", transport.requests.last().body)
        }
    }

    @Test fun `web completion checks account generation and changed during use before returning session`() = runTest {
        val noNetwork = fake { error("Unexpected synthetic request") }
        assertFailure(UpcShiguangAuthFailure.ACCOUNT_CHANGED, auth(noNetwork).completeWebLoginResult(accountId, 5, callback))
        assertFailure(UpcShiguangAuthFailure.ACCOUNT_CHANGED,
            auth(noNetwork).completeWebLoginResult(SchoolAccountId("acct_other_synthetic"), 4, callback))
        assertTrue(noNetwork.requests.isEmpty())
        assertFailure(UpcShiguangAuthFailure.ACCOUNT_CHANGED,
            auth(successful(), changed = true).completeWebLoginResult(accountId, 4, callback))
        val unavailable = UpcShiguangAuthenticator(object : SchoolCredentialAccess {
            override suspend fun <T> withLoginCredential(block: suspend (SchoolLoginCredential) -> T): SchoolCredentialUseResult<T> =
                SchoolCredentialUseResult.Failure(SchoolCredentialFailure.NOT_CONFIGURED)
        }, noNetwork)
        assertFailure(UpcShiguangAuthFailure.ACCOUNT_UNAVAILABLE, unavailable.completeWebLoginResult(accountId, 4, callback))
        assertTrue(noNetwork.requests.isEmpty())
    }

    @Test fun `web completion rejects invalid callback before network`() = runTest {
        listOf(callback + "&other=1", callback + "&ticket=ST-second", callback.replace("dekt.upc.edu.cn", "evil.invalid"),
            callback.replace("/caslogin.jsp", "/%63aslogin.jsp"), callback.replace("dekt.upc.edu.cn", "dekt.upc.edu.cn:80"))
            .forEach { ticket ->
                val transport = fake { error("Unexpected synthetic request") }
                assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR, auth(transport).completeWebLoginResult(accountId, 4, ticket))
                assertTrue(transport.requests.isEmpty())
            }
    }

    @Test fun `web completion never accepts cookies alone or missing activity probe`() = runTest {
        assertFailure(UpcShiguangAuthFailure.ACCESS_DENIED,
            auth(successful(sessionCookie = false)).completeWebLoginResult(accountId, 4, callback))
        assertFailure(UpcShiguangAuthFailure.PROTOCOL_ERROR,
            auth(successful(listBody = form)).completeWebLoginResult(accountId, 4, callback))
    }

    @Test fun `web completion preserves missing and mismatched identity semantics`() = runTest {
        listOf("{}", """{"gridModel":[]}""", """{"gridModel":[{}]}""",
            """{"gridModel":[{"stuNo":"synthetic-user"},{}]}""").forEach {
            assertFalse((auth(successful(it)).completeWebLoginResult(accountId, 4, callback) as UpcShiguangAuthResult.Success).identityVerified)
        }
        assertFailure(UpcShiguangAuthFailure.IDENTITY_MISMATCH,
            auth(successful("""{"gridModel":[{"stuNo":"other-synthetic"}]}""")).completeWebLoginResult(accountId, 4, callback))
    }

    @Test fun `web completion propagates cancellation and fixed transport failures`() = runTest {
        try {
            auth(fake { throw CancellationException() }).completeWebLoginResult(accountId, 4, callback)
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertFailure(UpcShiguangAuthFailure.TLS_ERROR,
            auth(fake { throw UpcAuthTransportException(UpcAuthTransportFailure.TLS) }).completeWebLoginResult(accountId, 4, callback))
    }

    @Test fun `external cancellation during web callback prevents following probe and identity requests`() = runTest {
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val transport = fake {
            callbackStarted.countDown()
            check(releaseCallback.await(5, TimeUnit.SECONDS))
            response(200, "<html>ready</html>")
        }
        val job = launch(Dispatchers.Default) { auth(transport).completeWebLoginResult(accountId, 4, callback) }
        try {
            withContext(Dispatchers.IO) { assertTrue(callbackStarted.await(5, TimeUnit.SECONDS)) }
            job.cancel()
        } finally { releaseCallback.countDown() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, transport.requests.size)
        assertEquals("GET", transport.requests.single().method)
    }

    private fun auth(transport: UpcAuthTransport, changed: Boolean = false, releasePassword: Boolean = false) = UpcShiguangAuthenticator(
        object : SchoolCredentialAccess {
            override suspend fun <T> withLoginCredential(block: suspend (SchoolLoginCredential) -> T): SchoolCredentialUseResult<T> {
                val credential = SchoolLoginCredential(accountId, AccountGeneration(4), "synthetic-user", "synthetic-password".toCharArray())
                if (releasePassword) credential.close()
                return try {
                    val result = block(credential)
                    if (changed) SchoolCredentialUseResult.Failure(SchoolCredentialFailure.CHANGED_DURING_USE)
                    else SchoolCredentialUseResult.Success(result)
                } finally { credential.close() }
            }
        }, transport)

    private fun successful(
        reportBody: String = """{"gridModel":[{"stuNo":"synthetic-user"}]}""",
        sessionCookie: Boolean = true,
        listBody: String = listing,
        secondStage: String? = null,
        loginPage: String = form,
        loginStatus: Int = 200,
    ) = fake { request ->
        when {
            request.url == UpcShiguangAuthEndpoints.LOGIN_URL && request.method == "GET" -> response(loginStatus, loginPage,
                mapOf("set-cookie" to listOf("CAS_SECRET=synthetic-cas-secret; Path=/; Secure", "PARENT_SECRET=synthetic-parent-secret; Domain=upc.edu.cn; Path=/; Secure")))
            request.url == UpcShiguangAuthEndpoints.LOGIN_URL && request.method == "POST" -> response(302,
                headers = mapOf("location" to listOf(callback)))
            request.url.contains("/caslogin.jsp?ticket=") -> response(200,
                secondStage?.let { "<script>var pcRedirectUrl = '$it';</script>" } ?: "<html>ready</html>",
                if (sessionCookie) mapOf("set-cookie" to listOf("SESSION=synthetic-business-secret; Path=/; Secure; HttpOnly")) else emptyMap())
            request.url.contains(UpcShiguangAuthEndpoints.SECOND_STAGE) -> response(302, headers = mapOf("location" to listOf("http://dekt.upc.edu.cn/web/index.html")))
            request.url.endsWith("/web/index.html") -> response(200, "<html>ready</html>")
            request.url == UpcShiguangAuthEndpoints.LIST_URL -> response(200, listBody)
            request.url == UpcShiguangAuthEndpoints.REPORT_URL -> response(200, reportBody)
            else -> error("Unexpected synthetic request")
        }
    }

    private fun fake(handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse) = ShiguangFakeTransport(handler)
    private fun response(status: Int, body: String = "", headers: Map<String, List<String>> = emptyMap()) =
        UpcAuthHttpResponse(status, headers, body.toByteArray())
    private fun assertFailure(reason: UpcShiguangAuthFailure, result: UpcShiguangAuthResult) {
        assertTrue(result is UpcShiguangAuthResult.Failure)
        assertEquals(reason, (result as UpcShiguangAuthResult.Failure).reason)
    }
}

private class ShiguangFakeTransport(private val handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse) : UpcAuthTransport {
    val requests = mutableListOf<UpcAuthHttpRequest>()
    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
        requests += request
        return handler(request)
    }
}
