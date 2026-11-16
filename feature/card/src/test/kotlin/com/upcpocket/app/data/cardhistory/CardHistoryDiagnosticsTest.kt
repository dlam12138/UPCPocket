package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.account.api.*
import com.upcpocket.app.data.card.CardDiagnosticRecorder
import com.upcpocket.app.platform.diagnostics.*
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CardHistoryDiagnosticsTest {
    private val recorder = CardDiagnosticRecorder()
    private val user = """{"code":200,"data":{"sno":"2099999999"}}"""
    private class Account : SchoolAccountStudentIdentityReader {
        override val state = MutableStateFlow(SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)))
        override val studentIdentity = MutableStateFlow<SchoolStudentIdentity?>(SchoolStudentIdentity(
            SchoolAccountId("fixture_account"), "2099999999", AccountGeneration(1)))
    }
    private fun client(
        authenticate: CardHistoryAuthenticator = CardHistoryAuthenticator { "synthetic-token-canary" },
        response: (Request) -> String,
    ) = CardHistoryClient(Account(), authenticate, OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(response(chain.request()).toResponseBody("application/json".toMediaType())).build()
    }.build())

    @Before fun attach() { Diagnostics.install(recorder) }
    @After fun detach() { Diagnostics.install(null) }

    @Test fun `authentication failure closes identity action without a personal request`() = runTest {
        var requests = 0
        val client = client(CardHistoryAuthenticator { throw CardHistoryException(CardHistoryFailure.NETWORK) }) {
            requests++; user
        }
        assertTrue(runCatching { client.login() }.isFailure)
        assertEquals(0, requests)
        assertFalse(client.hasSession())
        val events = recorder.forDetail(DiagnosticDetail.CARD_IDENTITY_VERIFY)
        assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.FAIL), events.map { it.result })
        assertEquals(DiagnosticPhase.BUSINESS_SESSION, events.last().phase)
        assertEquals(DiagnosticReason.NETWORK, events.last().reason)
        assertEquals(1, events.map { it.operationId }.distinct().size)
    }

    @Test fun `unverified identity closes its own action and cannot establish a session`() = runTest {
        var requests = 0
        val client = client { requests++; """{"code":200,"data":{"sno":"2099999998","extra":"synthetic-identity-canary"}}""" }
        assertTrue(runCatching { client.login() }.isFailure)
        assertEquals(1, requests)
        assertFalse(client.hasSession())
        val terminal = recorder.terminals(DiagnosticDetail.CARD_IDENTITY_VERIFY).single()
        assertEquals(DiagnosticResult.FAIL, terminal.result)
        assertEquals(DiagnosticPhase.PERSONAL_VERIFY, terminal.phase)
        assertFalse(recorder.events.joinToString().contains("synthetic-identity-canary"))
    }

    @Test fun `history and summary parser failures are distinct completed actions without retries`() = runTest {
        var requests = 0
        val client = client { request ->
            requests++
            when (request.url.encodedPath) {
                "/berserker-base/user" -> user
                "/berserker-search/search/personal/turnover" -> """{"code":200,"data":{"current":1,"pages":1,"total":1,"records":[{"orderId":"synthetic-order-canary","resume":"synthetic-row-canary","tranamt":1.5,"typeFrom":"2","jndatetimeStr":"synthetic-time-canary"}]}}"""
                else -> """{"code":200,"data":{"income":1.5,"expenses":1}}"""
            }
        }
        client.login()
        assertTrue(runCatching { client.page() }.isFailure)
        assertTrue(runCatching { client.summary() }.isFailure)
        assertEquals(3, requests)
        for (detail in listOf(DiagnosticDetail.CARD_HISTORY_PAGE, DiagnosticDetail.CARD_HISTORY_SUMMARY)) {
            val events = recorder.forDetail(detail)
            assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.FAIL), events.map { it.result })
            assertEquals(DiagnosticPhase.PARSE, events.last().phase)
            assertEquals(DiagnosticReason.SCHEMA, events.last().reason)
            assertEquals(1, events.map { it.operationId }.distinct().size)
        }
        assertNotEquals(recorder.terminals(DiagnosticDetail.CARD_HISTORY_PAGE).single().operationId,
            recorder.terminals(DiagnosticDetail.CARD_HISTORY_SUMMARY).single().operationId)
        assertFalse(recorder.events.joinToString().contains("canary"))
    }

    @Test fun `history transport failure closes response phase without a parser success`() = runTest {
        var requests = 0
        val client = client { request ->
            requests++
            if (request.url.encodedPath == "/berserker-base/user") user else throw IOException("synthetic-network-canary")
        }
        client.login()
        assertTrue(runCatching { client.page() }.isFailure)
        assertEquals(2, requests)
        val terminal = recorder.terminals(DiagnosticDetail.CARD_HISTORY_PAGE).single()
        assertEquals(DiagnosticPhase.RESPONSE, terminal.phase)
        assertEquals(DiagnosticReason.NETWORK, terminal.reason)
        assertEquals(DiagnosticResult.FAIL, terminal.result)
        assertFalse(recorder.events.joinToString().contains("synthetic-network-canary"))
    }

    @Test fun `all history records a failed aggregate when one parsed page is incomplete`() = runTest {
        var requests = 0
        val client = client { request ->
            requests++
            if (request.url.encodedPath == "/berserker-base/user") user
            else """{"code":200,"data":{"current":1,"pages":1,"total":2,"records":[{"orderId":"synthetic","tranamt":1,"typeFrom":"2","jndatetimeStr":"synthetic"}]}}"""
        }
        client.login()
        assertTrue(runCatching { client.all() }.isFailure)
        assertEquals(2, requests)
        val terminals = recorder.terminals(DiagnosticDetail.CARD_HISTORY_PAGE)
        assertEquals(listOf(DiagnosticResult.OK, DiagnosticResult.FAIL), terminals.map { it.result })
        assertEquals(DiagnosticPhase.PARSE, terminals.last().phase)
        assertEquals(2, terminals.map { it.operationId }.distinct().size)
    }
}
