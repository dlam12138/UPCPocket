package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.account.api.*
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
    private fun client(account: Account, handler: (Request) -> String) = CardHistoryClient(account,
        CardHistoryAuthenticator { "fixture-token" }, OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(handler(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build())
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

}
