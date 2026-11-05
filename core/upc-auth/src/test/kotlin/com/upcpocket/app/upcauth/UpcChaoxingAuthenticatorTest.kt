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
class UpcChaoxingAuthenticatorTest {

    private val accountId = SchoolAccountId("acct_synthetic_chaoxing")
    private val credential = SchoolLoginCredential(
        accountId = accountId,
        credentialGeneration = AccountGeneration(3),
        loginName = "synthetic-user",
        password = "synthetic-password".toCharArray(),
    )

    @Test
    fun `normal Chaoxing CAS returns only office cookies after identity page`() = runTest {
        var seatEntryCalls = 0
        val transport = ChaoxingFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcChaoxingAuthEndpoints.SEAT_ENTRY ->
                    if (seatEntryCalls++ == 0) {
                        response(
                            303,
                            mapOf(
                                "Location" to
                                    "https://tyrzfw.chaoxing.com/auth3/upc/cas/init" +
                                    "?refer=https://office.chaoxing.com/front/apps/seat/index" +
                                    "?fidEnc=${UpcChaoxingAuthEndpoints.FID_ENC}" +
                                    "&mappId=${UpcChaoxingAuthEndpoints.MAPP_ID}",
                            ),
                        )
                    } else {
                        htmlResponse(
                            """<script>var userLoginInfo={"userInfo":{"sno":"20260001"}}||{};</script>""",
                        )
                    }
                request.method == "GET" && request.url.startsWith("https://tyrzfw.chaoxing.com/auth3/upc/cas/init?") ->
                    htmlResponse(initPage(casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(action = casUrl()))
                request.method == "POST" && request.url == casUrl() -> {
                    assertTrue(request.body.orEmpty().contains("password=synthetic-password"))
                    response(
                        302,
                        mapOf(
                            "Location" to callback(ticket = true),
                            "Set-Cookie" to "CASTGC=synthetic-cas-ticket; Path=/cas/; Secure; HttpOnly",
                        ),
                    )
                }
                request.method == "GET" && request.url == callback(ticket = true) ->
                    response(302, mapOf("Location" to callback(ticket = false)))
                request.method == "GET" && request.url == callback(ticket = false) ->
                    htmlResponse(redirectPage(passportUrl()))
                request.method == "GET" && request.url == passportUrl() ->
                    htmlResponse(
                        receivePage() +
                            """<input type="hidden" id="refer" value="$officeRefer">""",
                        setCookie = listOf(
                            "fid=synthetic-fid; Domain=.chaoxing.com; Path=/; Secure",
                            "_uid=synthetic-uid; Domain=.chaoxing.com; Path=/; Secure",
                        ),
                    )
                request.method == "GET" && request.url == officeRefer ->
                    response(
                        302,
                        mapOf(
                            "Location" to
                                "https://office.chaoxing.com/front/third/apps/seat/index" +
                                "?fidEnc=${UpcChaoxingAuthEndpoints.FID_ENC}" +
                                "&mappId=${UpcChaoxingAuthEndpoints.MAPP_ID}",
                            "Set-Cookie" to "JSESSIONID=office-session; Path=/; Secure; HttpOnly",
                        ),
                    )
                else -> error("unexpected request ${request.method} ${request.url}")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 3)

        val success = result as? UpcChaoxingAuthResult.Success
            ?: error("自动认证未成功：$result；请求=${transport.requests}")
        assertTrue(success.cookieHeader.contains("_uid=synthetic-uid"))
        assertTrue(success.cookieHeader.contains("JSESSIONID=office-session"))
        assertFalse(success.cookieHeader.contains("CASTGC"))
        assertFalse(success.cookieHeader.contains("ST-"))
        assertFalse(success.toString().contains("synthetic-uid"))
        assertEquals(1, transport.posts)
        assertEquals(2, transport.officeIdentityRequests)
    }

    @Test
    fun `office cookie header includes domain and host cookies but not CAS`() {
        val jar = UpcAuthCookieJar(
            setOf(
                UpcChaoxingAuthEndpoints.CAS_HOST,
                UpcChaoxingAuthEndpoints.OFFICE_HOST,
                UpcChaoxingAuthEndpoints.CHAOXING_COOKIE_DOMAIN,
            ),
        )
        jar.save(
            "https://passport2-api.chaoxing.com/api/v2/casreceive",
            listOf("fid=synthetic-fid; Domain=.chaoxing.com; Path=/; Secure"),
        )
        jar.save(
            "https://office.chaoxing.com/front/apps/seat/index",
            listOf("JSESSIONID=office-session; Path=/; Secure; HttpOnly"),
        )
        jar.save(
            "https://cas.upc.edu.cn/cas/login",
            listOf("CASTGC=synthetic-cas; Path=/cas/; Secure"),
        )
        val header = jar.header(UpcChaoxingAuthEndpoints.SEAT_ENTRY)
        assertTrue(header.orEmpty().contains("fid=synthetic-fid"))
        assertTrue(header.orEmpty().contains("JSESSIONID=office-session"))
        assertFalse(header.orEmpty().contains("CASTGC"))
    }

    @Test
    fun `captcha is an interaction and never submits the password`() = runTest {
        val transport = ChaoxingFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcChaoxingAuthEndpoints.SEAT_ENTRY ->
                    response(
                        303,
                        mapOf(
                            "Location" to
                                "https://tyrzfw.chaoxing.com/auth3/upc/cas/init" +
                                "?refer=https://office.chaoxing.com/front/apps/seat/index" +
                                "?fidEnc=${UpcChaoxingAuthEndpoints.FID_ENC}",
                        ),
                    )
                request.method == "GET" && request.url.startsWith("https://tyrzfw.chaoxing.com/auth3/upc/cas/init?") ->
                    htmlResponse(initPage(casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(action = casUrl(), captcha = true))
                else -> error("验证码页面不得提交密码")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 3)
        val failure = result as UpcChaoxingAuthResult.Failure
        assertEquals(UpcChaoxingAuthFailure.INTERACTION_REQUIRED, failure.reason)
        assertTrue(!failure.interactionUrl.isNullOrBlank())
        assertEquals(0, transport.posts)
    }

    @Test
    fun `wrong password is submitted once and not retried`() = runTest {
        val transport = ChaoxingFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcChaoxingAuthEndpoints.SEAT_ENTRY ->
                    response(
                        303,
                        mapOf(
                            "Location" to
                                "https://tyrzfw.chaoxing.com/auth3/upc/cas/init" +
                                "?refer=https://office.chaoxing.com/front/apps/seat/index" +
                                "?fidEnc=${UpcChaoxingAuthEndpoints.FID_ENC}",
                        ),
                    )
                request.method == "GET" && request.url.startsWith("https://tyrzfw.chaoxing.com/auth3/upc/cas/init?") ->
                    htmlResponse(initPage(casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(action = casUrl()))
                request.method == "POST" ->
                    htmlResponse(
                        formPage(action = casUrl()) +
                            """<div id="errorDiv">用户名或密码错误</div>""",
                    )
                else -> error("不得重试密码")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(
            UpcChaoxingAuthFailure.INVALID_CREDENTIALS,
            (result as UpcChaoxingAuthResult.Failure).reason,
        )
        assertEquals(1, transport.posts)
    }

    @Test
    fun `external callback is rejected before following`() = runTest {
        val transport = ChaoxingFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcChaoxingAuthEndpoints.SEAT_ENTRY ->
                    response(
                        303,
                        mapOf(
                            "Location" to
                                "https://tyrzfw.chaoxing.com/auth3/upc/cas/init" +
                                "?refer=https://office.chaoxing.com/front/apps/seat/index" +
                                "?fidEnc=${UpcChaoxingAuthEndpoints.FID_ENC}",
                        ),
                    )
                request.method == "GET" && request.url.startsWith("https://tyrzfw.chaoxing.com/auth3/upc/cas/init?") ->
                    htmlResponse(initPage(casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(action = casUrl()))
                request.method == "POST" ->
                    response(
                        302,
                        mapOf(
                            "Location" to
                                "https://evil.invalid/auth3/upc/cas/index?ticket=ST-synthetic",
                        ),
                    )
                else -> error("不得跟随外部回调")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(
            UpcChaoxingAuthFailure.PROTOCOL_ERROR,
            (result as UpcChaoxingAuthResult.Failure).reason,
        )
    }

    @Test
    fun `passport receive with an external refer is rejected`() = runTest {
        val transport = ChaoxingFakeTransport { request ->
            when {
                request.method == "GET" && request.url == UpcChaoxingAuthEndpoints.SEAT_ENTRY ->
                    response(
                        303,
                        mapOf(
                            "Location" to
                                "https://tyrzfw.chaoxing.com/auth3/upc/cas/init" +
                                "?refer=https://office.chaoxing.com/front/apps/seat/index" +
                                "?fidEnc=${UpcChaoxingAuthEndpoints.FID_ENC}",
                        ),
                    )
                request.method == "GET" && request.url.startsWith("https://tyrzfw.chaoxing.com/auth3/upc/cas/init?") ->
                    htmlResponse(initPage(casUrl()))
                request.method == "GET" && request.url == casUrl() ->
                    htmlResponse(formPage(action = casUrl()))
                request.method == "POST" ->
                    response(302, mapOf("Location" to callback(ticket = true)))
                request.method == "GET" && request.url == callback(ticket = true) ->
                    response(302, mapOf("Location" to callback(ticket = false)))
                request.method == "GET" && request.url == callback(ticket = false) ->
                    htmlResponse(
                        redirectPage(
                            passportUrl(refer = "https://evil.invalid/front/apps/seat/index"),
                        ),
                    )
                else -> error("不得提交外部 refer")
            }
        }

        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(
            UpcChaoxingAuthFailure.PROTOCOL_ERROR,
            (result as UpcChaoxingAuthResult.Failure).reason,
        )
    }

    @Test
    fun `missing userLoginInfo is not a session`() = runTest {
        val transport = successfulTransport(
            officePage = """<html><body>login</body></html>""",
        )
        val result = authenticator(transport).authenticate(accountId, 3)
        assertEquals(
            UpcChaoxingAuthFailure.PROTOCOL_ERROR,
            (result as UpcChaoxingAuthResult.Failure).reason,
        )
    }

    @Test
    fun `account mismatch does not start network`() = runTest {
        val transport = ChaoxingFakeTransport { error("账号不一致时不得联网") }
        val other = SchoolAccountId("acct_another_chaoxing")
        val result = authenticator(transport).authenticate(other, 3)
        assertEquals(
            UpcChaoxingAuthFailure.ACCOUNT_CHANGED,
            (result as UpcChaoxingAuthResult.Failure).reason,
        )
    }

    @Test
    fun `tls and network failures stay distinct`() = runTest {
        val tls = ChaoxingFakeTransport {
            throw UpcAuthTransportException(UpcAuthTransportFailure.TLS)
        }
        assertEquals(
            UpcChaoxingAuthFailure.TLS_ERROR,
            (authenticator(tls).authenticate(accountId, 3) as UpcChaoxingAuthResult.Failure).reason,
        )
        val network = ChaoxingFakeTransport {
            throw UpcAuthTransportException(UpcAuthTransportFailure.NETWORK)
        }
        assertEquals(
            UpcChaoxingAuthFailure.NETWORK_UNAVAILABLE,
            (authenticator(network).authenticate(accountId, 3) as UpcChaoxingAuthResult.Failure).reason,
        )
    }

    private fun successfulTransport(
        officePage: String = """<script>var userLoginInfo={"userInfo":{"sno":"20260001"}}||{};</script>""",
    ): ChaoxingFakeTransport {
        var seatEntryCalls = 0
        return ChaoxingFakeTransport { request ->
        when {
            request.method == "GET" && request.url == UpcChaoxingAuthEndpoints.SEAT_ENTRY ->
                if (seatEntryCalls++ == 0) {
                    response(
                        303,
                        mapOf(
                            "Location" to
                                "https://tyrzfw.chaoxing.com/auth3/upc/cas/init" +
                                "?refer=https://office.chaoxing.com/front/apps/seat/index" +
                                "?fidEnc=${UpcChaoxingAuthEndpoints.FID_ENC}",
                        ),
                    )
                } else {
                    htmlResponse(officePage)
                }
            request.method == "GET" && request.url.startsWith("https://tyrzfw.chaoxing.com/auth3/upc/cas/init?") ->
                htmlResponse(initPage(casUrl()))
            request.method == "GET" && request.url == casUrl() ->
                htmlResponse(formPage(action = casUrl()))
            request.method == "POST" ->
                response(302, mapOf("Location" to callback(ticket = true)))
            request.method == "GET" && request.url == callback(ticket = true) ->
                response(302, mapOf("Location" to callback(ticket = false)))
            request.method == "GET" && request.url == callback(ticket = false) ->
                htmlResponse(redirectPage(passportUrl()))
            request.method == "GET" && request.url == passportUrl() ->
                htmlResponse(
                    receivePage() +
                        """<input type="hidden" id="refer" value="$officeRefer">""",
                    setCookie = listOf(
                        "fid=synthetic-fid; Domain=.chaoxing.com; Path=/; Secure",
                        "_uid=synthetic-uid; Domain=.chaoxing.com; Path=/; Secure",
                    ),
                )
            request.method == "GET" && request.url == officeRefer ->
                response(
                    302,
                    mapOf(
                        "Location" to
                            "https://office.chaoxing.com/front/third/apps/seat/index" +
                            "?fidEnc=${UpcChaoxingAuthEndpoints.FID_ENC}" +
                            "&mappId=${UpcChaoxingAuthEndpoints.MAPP_ID}",
                    ),
                )
            else -> error("unexpected request ${request.method} ${request.url}")
        }
        }
    }

    private fun authenticator(transport: UpcAuthTransport): UpcChaoxingAuthenticator =
        UpcChaoxingAuthenticator(
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
                UpcChaoxingAuthEndpoints.CAS_SERVICE,
                StandardCharsets.UTF_8.name(),
            )

    private fun callback(ticket: Boolean): String =
        "https://tyrzfw.chaoxing.com/auth3/upc/cas/index" +
            if (ticket) "?ticket=ST-synthetic-ticket123456" else ""

    private fun passportUrl(refer: String = officeRefer): String =
        "https://passport2-api.chaoxing.com/api/v2/casreceive" +
            "?thirdinfo=synthetic-thirdinfo" +
            "&refer=" + URLEncoder.encode(refer, StandardCharsets.UTF_8.name()) +
            "&setmain=true&casfrom=login&casinfo=&casenc="

    private val officeRefer =
        "https://office.chaoxing.com/front/apps/seat/index" +
            "?fidEnc=${UpcChaoxingAuthEndpoints.FID_ENC}" +
            "&mappId=${UpcChaoxingAuthEndpoints.MAPP_ID}"

    private fun initPage(cas: String): String =
        """<script>window.location = "$cas";</script>"""

    private fun redirectPage(target: String): String =
        """<script>window.location = "${target.replace("/", "\\/").replace("&", "\\u0026")}";</script>"""

    private fun receivePage(): String =
        """<html><body><input type="hidden" id="status" value="true"></body></html>"""

    private fun formPage(action: String, captcha: Boolean = false): String = """
        <html><body>
        <form action="${action.replace("&", "&amp;")}" method="post">
          <input name="username" value="">
          <input name="password" type="password" value="">
          <input name="execution" value="synthetic-execution">
          <input name="type" value="username_password">
          <input name="_eventId" value="submit">
        </form>
        <div id="captchaParent" style="${if (captcha) "display:block" else "display:none"}">captcha</div>
        </body></html>
    """.trimIndent()

    private fun htmlResponse(
        body: String,
        setCookie: List<String> = emptyList(),
    ): UpcAuthHttpResponse = response(
        200,
        buildMap {
            put("Content-Type", "text/html; charset=utf-8")
            if (setCookie.isNotEmpty()) put("Set-Cookie", setCookie)
        },
        body,
    )

    private fun response(
        status: Int,
        headers: Map<String, Any> = emptyMap(),
        body: String = "",
    ): UpcAuthHttpResponse {
        val normalized = headers.mapKeys { (name, _) -> name.lowercase() }.mapValues { (_, value) ->
            when (value) {
                is List<*> -> value.map { it.toString() }
                else -> listOf(value.toString())
            }
        }
        return UpcAuthHttpResponse(
            status = status,
            headers = normalized,
            body = body.toByteArray(),
        )
    }
}

private class ChaoxingFakeTransport(
    private val handler: (UpcAuthHttpRequest) -> UpcAuthHttpResponse,
) : UpcAuthTransport {
    var posts = 0
    var officeIdentityRequests = 0
    val requests = mutableListOf<String>()

    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
        requests += "${request.method} ${request.url.substringBefore('?')}"
        if (request.method == "POST") posts++
        if (request.method == "GET" &&
            request.url.startsWith("https://office.chaoxing.com/front/third/apps/seat/index?")
        ) {
            officeIdentityRequests++
        }
        return handler(request)
    }
}
