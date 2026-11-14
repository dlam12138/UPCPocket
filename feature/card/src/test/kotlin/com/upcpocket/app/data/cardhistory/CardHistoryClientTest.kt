package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.account.api.*
import com.upcpocket.app.ui.ecard.CardHistoryWebViewOwnership
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CardHistoryClientTest {
    private class Account : SchoolAccountStudentIdentityReader {
        override val state = MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)))
        override val studentIdentity = MutableStateFlow<SchoolStudentIdentity?>(SchoolStudentIdentity(SchoolAccountId("fixture_account"), "2099999999", AccountGeneration(1)))
        fun change() { studentIdentity.value = studentIdentity.value!!.copy(credentialGeneration = AccountGeneration(2)) }
    }
    private fun client(
        account: Account,
        handler: (Request) -> String,
        interactive: CardHistoryInteractiveAuthenticator? = null,
    ) = CardHistoryClient(account,
        CardHistoryAuthenticator { "fixture-token" }, OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(handler(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build(), interactive)

    private fun client(account: Account, handler: (Request) -> String) = client(account, handler, null)

    private fun interactive(
        onComplete: suspend (SchoolStudentIdentity, String) -> String = { _, _ -> "fixture-web-token" },
    ) = object : CardHistoryInteractiveAuthenticator {
        override val entryUrl = "https://mb-xyk.upc.edu.cn/berserker-auth/cas/redirect/wislearn?fixture"
        override fun inspectNavigation(url: String, method: String, isMainFrame: Boolean): CardHistoryWebNavigation = when {
            url == entryUrl && method == "GET" && isMainFrame -> CardHistoryWebNavigation.Allow
            url.startsWith("https://mb-xyk.upc.edu.cn/plat/") && method == "GET" && isMainFrame -> CardHistoryWebNavigation.Complete(url)
            else -> CardHistoryWebNavigation.Block
        }
        override suspend fun completeResult(identity: SchoolStudentIdentity, resultUrl: String) = onComplete(identity, resultUrl)
    }
    private val user = """{"code":200,"data":{"sno":"2099999999"}}"""
    private fun page(current: Int, pages: Int = 2, total: Int = 2) = """{"code":200,"data":{"current":$current,"pages":$pages,"total":$total,"records":[
        {"orderId":"fixture-$current","tranamt":820,"typeFrom":"2","jndatetimeStr":"2026-11-12 12:00:00"}]}}"""

    @Test fun `all loads each page once and returns last page metadata`() = runTest {
        val account = Account(); val requests = mutableListOf<Request>()
        val client = client(account) { request ->
            requests += request
            if (request.url.encodedPath == "/berserker-base/user") user
            else page(request.url.queryParameter("current")!!.toInt())
        }
        client.login()
        val result = client.all(YearMonth.of(2026, 2))
        assertEquals(2, result.current); assertEquals(2, result.data.rows.size)
        assertEquals(listOf("1", "2"), requests.drop(1).map { it.url.queryParameter("current") })
        assertEquals("2026-02-28", requests.last().url.queryParameter("timeTo"))
        assertTrue(requests.all { it.header("synjones-auth") == "bearer fixture-token" })
    }

    @Test fun `account changed during identity lookup cannot establish session`() = runTest {
        val account = Account(); val client = client(account) { account.change(); user }
        try { client.login(); fail("Expected changed account") }
        catch (error: CardHistoryException) { assertEquals(CardHistoryFailure.ACCOUNT_CHANGED, error.reason) }
        assertFalse(client.hasSession())
    }

    @Test fun `late page after account changes is rejected`() = runTest {
        val account = Account(); val client = client(account) { request ->
            if (request.url.encodedPath == "/berserker-base/user") user else { account.change(); page(1) }
        }
        client.login()
        try { client.page(); fail("Expected changed account") }
        catch (error: CardHistoryException) { assertEquals(CardHistoryFailure.ACCOUNT_CHANGED, error.reason) }
    }

    @Test fun `all rejects incomplete history instead of marking it complete`() = runTest {
        val account = Account(); val client = client(account) { request ->
            if (request.url.encodedPath == "/berserker-base/user") user else page(1, pages = 1, total = 2)
        }
        client.login()
        try { client.all(); fail("Expected incomplete history") }
        catch (error: CardHistoryException) { assertEquals(CardHistoryFailure.PROTOCOL, error.reason) }
    }

    @Test fun `clearing session while page is in flight rejects late result`() = runTest {
        val account = Account(); lateinit var client: CardHistoryClient
        client = client(account) { request ->
            if (request.url.encodedPath == "/berserker-base/user") user else { client.clearSession(); page(1) }
        }
        client.login()
        try { client.page(); fail("Expected invalidated session") }
        catch (error: CardHistoryException) { assertEquals(CardHistoryFailure.ACCOUNT_CHANGED, error.reason) }
        assertFalse(client.hasSession())
    }
    @Test fun `summary accepts integral decimal fen without rounding`() = runTest {
        val account = Account(); val client = client(account) { request ->
            if (request.url.encodedPath == "/berserker-base/user") user
            else """{"code":200,"data":{"income":5000.0,"expenses":820.00}}"""
        }
        client.login()
        val result = client.summary(YearMonth.of(2026, 2))
        assertEquals(5000L, result.incomeFen)
        assertEquals(820L, result.expenseFen)
    }

    @Test fun `summary rejects fractional fen instead of rounding`() = runTest {
        val account = Account(); val client = client(account) { request ->
            if (request.url.encodedPath == "/berserker-base/user") user else """{"code":200,"data":{"income":5000.5,"expenses":820}}"""
        }
        client.login()
        try { client.summary(); fail("Expected protocol error") }
        catch (error: CardHistoryException) { assertEquals(CardHistoryFailure.PROTOCOL, error.reason) }
    }

    @Test fun `expired envelope clears session instead of returning empty history`() = runTest {
        val account = Account(); val client = client(account) { request ->
            if (request.url.encodedPath == "/berserker-base/user") user else """{"code":401,"data":{}}"""
        }
        client.login()
        try { client.page(); fail("Expected login required") }
        catch (error: CardHistoryException) { assertEquals(CardHistoryFailure.LOGIN_REQUIRED, error.reason) }
        assertFalse(client.hasSession())
    }

    @Test fun `web result is consumed once then verifies identity before session commit`() = runTest {
        val account = Account(); var completeCalls = 0; var identityCalls = 0
        val client = client(account, { request ->
            if (request.url.encodedPath == "/berserker-base/user") { identityCalls++; user } else page(1)
        }, interactive { _, _ -> completeCalls++; "fixture-web-token" })
        val attempt = client.beginInteractiveLogin()
        client.completeInteractiveLogin(attempt.id, "https://mb-xyk.upc.edu.cn/plat/?synjones-auth=fixture")
        assertTrue(client.hasSession())
        assertEquals(1, completeCalls)
        assertEquals(1, identityCalls)
        try {
            client.completeInteractiveLogin(attempt.id, "https://mb-xyk.upc.edu.cn/plat/?synjones-auth=fixture")
            fail("Repeated result must not be consumed")
        } catch (error: CardHistoryException) {
            assertEquals(CardHistoryFailure.ACCOUNT_CHANGED, error.reason)
        }
        assertEquals(1, completeCalls)
        assertEquals(1, identityCalls)
    }

    @Test fun `account change while converting web result cannot verify or save token`() = runTest {
        val account = Account(); var identityCalls = 0
        val client = client(account, { request ->
            if (request.url.encodedPath == "/berserker-base/user") { identityCalls++; user } else page(1)
        }, interactive { _, _ -> account.change(); "fixture-web-token" })
        val attempt = client.beginInteractiveLogin()
        try {
            client.completeInteractiveLogin(attempt.id, "https://mb-xyk.upc.edu.cn/plat/?ticket=fixture")
            fail("Account change must reject the late result")
        } catch (error: CardHistoryException) {
            assertEquals(CardHistoryFailure.ACCOUNT_CHANGED, error.reason)
        }
        assertFalse(client.hasSession())
        assertEquals(0, identityCalls)
    }

    @Test fun `clearing the session invalidates an open web attempt`() = runTest {
        val account = Account(); var completeCalls = 0
        val client = client(account, { user }, interactive { _, _ -> completeCalls++; "fixture-web-token" })
        val attempt = client.beginInteractiveLogin()
        client.clearSession()
        try {
            client.completeInteractiveLogin(attempt.id, "https://mb-xyk.upc.edu.cn/plat/?ticket=fixture")
            fail("Cleared attempt must be rejected")
        } catch (error: CardHistoryException) {
            assertEquals(CardHistoryFailure.ACCOUNT_CHANGED, error.reason)
        }
        assertEquals(0, completeCalls)
    }

    @Test fun `failed browser disposal remains tracked and account cleanup retries it`() = runTest {
        val account = Account(); val client = client(account, { user }, interactive())
        val attempt = client.beginInteractiveLogin()
        var stops = 0
        val lease = object : CardHistoryWebViewLease {
            override fun dispose() = Unit
            override suspend fun disposeAndAwait(): Boolean = ++stops > 1
        }
        assertNotNull(client.registerInteractiveWebView(attempt.id, lease))
        assertFalse(client.invalidateForAccountChange())
        assertFalse(client.isInteractiveLoginCurrent(attempt.id))
        assertTrue(client.invalidateForAccountChange())
        assertEquals(2, stops)
        client.beginInteractiveLogin()
    }

    @Test fun `failed old browser destroy rejects reload without losing account cleanup ownership`() = runTest {
        val account = Account(); val client = client(account, { user }, interactive())
        val attempt = client.beginInteractiveLogin()
        class Browser(var failDestroy: Boolean, var destroyCalls: Int = 0)
        val old = Browser(true)
        val replacement = Browser(false)
        val ownership = CardHistoryWebViewOwnership<Browser> { browser ->
            browser.destroyCalls++
            check(!browser.failDestroy)
        }
        val lease = object : CardHistoryWebViewLease {
            override fun dispose() { runCatching { ownership.close() } }
            override suspend fun disposeAndAwait(): Boolean = runCatching { ownership.close() }.isSuccess
        }
        assertTrue(ownership.attach(old))
        ownership.setRegistration(client.registerInteractiveWebView(attempt.id, lease))
        assertTrue(runCatching { ownership.release(old) }.isFailure)
        assertFalse(ownership.attach(replacement))
        assertEquals(1, replacement.destroyCalls)
        assertFalse(client.invalidateForAccountChange())
        assertEquals(2, old.destroyCalls)
        old.failDestroy = false
        assertTrue(client.invalidateForAccountChange())
        assertEquals(3, old.destroyCalls)
        assertEquals(1, replacement.destroyCalls)
        client.beginInteractiveLogin()
    }

    @Test fun `reload releases ownership before new factory and late old release cannot unregister new browser`() = runTest {
        for (failFirstDestroy in listOf(false, true)) {
            val account = Account(); val client = client(account, { user }, interactive())
            val attempt = client.beginInteractiveLogin()
            class Browser(var failDestroy: Boolean, var destroyCalls: Int = 0)
            val old = Browser(failFirstDestroy)
            val replacement = Browser(false)
            val ownership = CardHistoryWebViewOwnership<Browser> { browser ->
                browser.destroyCalls++
                check(!browser.failDestroy)
            }
            val lease = object : CardHistoryWebViewLease {
                override fun dispose() { runCatching { ownership.close() } }
                override suspend fun disposeAndAwait(): Boolean = runCatching { ownership.close() }.isSuccess
            }
            assertTrue(ownership.attach(old))
            ownership.setRegistration(client.registerInteractiveWebView(attempt.id, lease))
            if (failFirstDestroy) {
                assertTrue(runCatching { ownership.releaseForReload() }.isFailure)
                assertEquals(1, old.destroyCalls)
                old.failDestroy = false
            }
            ownership.releaseForReload()
            assertTrue(ownership.attach(replacement))
            ownership.setRegistration(client.registerInteractiveWebView(attempt.id, lease))
            // Model the old AndroidView callback arriving after the new factory.
            ownership.release(old)
            assertEquals(if (failFirstDestroy) 2 else 1, old.destroyCalls)
            assertEquals(0, replacement.destroyCalls)
            assertTrue(client.invalidateForAccountChange())
            assertEquals(1, replacement.destroyCalls)
            assertFalse(client.isInteractiveLoginCurrent(attempt.id))
        }
    }

    @Test fun `account invalidation awaits browser disposal before returning`() = runTest {
        val account = Account(); val client = client(account, { user }, interactive())
        val attempt = client.beginInteractiveLogin()
        var disposed = false
        var awaited = false
        val lease = object : CardHistoryWebViewLease {
            override fun dispose() { disposed = true }
            override suspend fun disposeAndAwait(): Boolean { disposed = true; awaited = true; return true }
        }
        assertNotNull(client.registerInteractiveWebView(attempt.id, lease))
        assertTrue(client.invalidateForAccountChange())
        assertTrue(disposed)
        assertTrue(awaited)
        assertFalse(client.isInteractiveLoginCurrent(attempt.id))
    }

}
