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
        <input name="username"><input name="password"><input name="type" value="username_password">
        <input name="execution" value="example-execution"><input name="_eventId" value="submit"></form>"""

    @Test
    fun captchaStopsBeforeSubmittingPassword() = runTest {
        val transport = CardFakeTransport { request ->
            when (request.method) {
                "GET" -> if (request.url == CardAuthOrigins.ENTRY) response(302, location = cas)
                    else htmlResponse(html + "config.captcha = {id: 'example-id'};")
                else -> error("验证码分支不得提交密码")
            }
        }
        val result = UpcCardAuthenticator(credentials(), transport).authenticate(accountId, 3)
        assertEquals(UpcCardAuthFailure.INTERACTION_REQUIRED, (result as UpcCardAuthResult.Failure).reason)
        assertEquals(0, transport.posts)
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
        assertEquals(UpcCardAuthFailure.INVALID_CREDENTIALS, (result as UpcCardAuthResult.Failure).reason)
        assertEquals(1, transport.posts)
        assertEquals(cas, transport.requests.first { it.method == "POST" }.url)
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
