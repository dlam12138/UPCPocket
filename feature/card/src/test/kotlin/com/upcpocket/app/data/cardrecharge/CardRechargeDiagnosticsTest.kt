package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.data.card.CardDiagnosticRecorder
import com.upcpocket.app.data.cardhistory.CardHistoryAuthenticator
import com.upcpocket.app.data.cardhistory.CardHistoryClient
import com.upcpocket.app.data.cardhistory.CardHistoryException
import com.upcpocket.app.data.cardhistory.CardHistoryFailure
import com.upcpocket.app.platform.diagnostics.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CardRechargeDiagnosticsTest {
    private val recorder = CardDiagnosticRecorder()
    @Before fun attach() { Diagnostics.install(recorder) }
    @After fun detach() { Diagnostics.install(null) }

    @Test fun `prepare authentication failure has a terminal without any business request`() = runTest {
        val account = CardRechargeServiceTest.Account()
        val history = CardHistoryClient(account, CardHistoryAuthenticator {
            throw CardHistoryException(CardHistoryFailure.INTERACTION_REQUIRED)
        })
        var requests = 0
        val store = CardRechargeServiceTest.Store()
        val service = CardRechargeService(history, store, RechargeTransport { _, _, _ -> requests++; error("Unexpected request") })
        service.open()
        val events = recorder.forDetail(DiagnosticDetail.CARD_RECHARGE_PREPARE)
        assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.BLOCKED), events.map { it.result })
        assertEquals(DiagnosticPhase.BUSINESS_SESSION, events.last().phase)
        assertEquals(DiagnosticReason.INTERACTION, events.last().reason)
        assertEquals(0, requests)
        assertNull(store.value)
        assertFalse(history.hasSession())
    }

    @Test fun `prepare failure reports the existing failing step and preserves read order and zero writes`() = runTest {
        data class Case(val route: String, val body: String, val detail: DiagnosticDetail, val requests: Int,
            val traceMs: Long, val result: DiagnosticResult = DiagnosticResult.FAIL)
        val cases = listOf(
            Case("/berserker-base/user", """{"code":200,"data":{"sno":"2099999998"}}""", DiagnosticDetail.CARD_IDENTITY_VERIFY, 1, 1),
            Case("/frontInfo?", "{}", DiagnosticDetail.CARD_RECHARGE_CONFIG, 2, 2),
            Case("/queryCard?", """{"code":200,"data":{"card":[]}}""", DiagnosticDetail.CARD_RECHARGE_CARD, 3, 3, DiagnosticResult.BLOCKED),
            Case("/singleFeeitem?", "{}", DiagnosticDetail.CARD_RECHARGE_RULES, 4, 4),
            Case("/campus-card/js/", "<html>synthetic-signer-canary</html>", DiagnosticDetail.CARD_RECHARGE_SIGNER, 5, 5),
            // The API intentionally parses rules after signer; the failed rule step belongs to its feeitem GET.
            Case("/singleFeeitem?", """{"feeitem":{"maxmoney":"synthetic-rule-canary"}}""", DiagnosticDetail.CARD_RECHARGE_RULES, 5, 4),
        )
        for (case in cases) {
            recorder.events.clear()
            val f = CardRechargeServiceTest.Fixture()
            val requests = mutableListOf<String>()
            val transport = RechargeTransport { url, fields, token ->
                requests += url
                val response = if (url.contains(case.route)) RechargeResponse(200, case.body)
                    else f.transport.request(url, fields, token)
                val traceMs = when {
                    url.contains("/berserker-base/user") -> 1L
                    url.contains("/frontInfo?") -> 2L
                    url.contains("/queryCard?") -> 3L
                    url.contains("/singleFeeitem?") -> 4L
                    url == CardRechargeProtocol.SIGNER_SOURCE -> 5L
                    else -> 6L
                }
                response.copy(transportSnapshot = DiagnosticTransportSnapshot(
                    stage = DiagnosticTransportStage.RESPONSE_HEADERS,
                    totalMs = traceMs,
                    requestHeadersCount = 1,
                ))
            }
            val service = CardRechargeService(f.history, f.store, transport)
            service.open()
            val root = recorder.forDetail(DiagnosticDetail.CARD_RECHARGE_PREPARE)
            assertEquals(listOf(DiagnosticResult.PENDING, case.result), root.map { it.result })
            val failed = recorder.terminals(case.detail).single { it.operationId == root.first().operationId }
            assertEquals(case.result, failed.result)
            assertEquals(if (case.detail == DiagnosticDetail.CARD_IDENTITY_VERIFY) DiagnosticPhase.PERSONAL_VERIFY else DiagnosticPhase.PARSE, failed.phase)
            assertEquals(case.traceMs, failed.transport?.totalMs)
            assertEquals(case.requests, requests.size)
            assertEquals(1, f.authentications)
            assertEquals(0, f.createPosts)
            assertEquals(0, f.payPosts)
            assertNull(f.store.value)
            assertFalse(recorder.events.joinToString().contains("canary"))
        }
    }

    @Test fun `pre-dispatch failure cannot inherit the previous step transport snapshot`() = runTest {
        val f = CardRechargeServiceTest.Fixture()
        var dispatched = 0
        var reads = 0
        val trace = DiagnosticTransportSnapshot(
            stage = DiagnosticTransportStage.RESPONSE_BODY,
            totalMs = 31,
            requestHeadersCount = 1,
        )
        val transport = object : RechargeTransport {
            override fun request(url: String, fields: Map<String, String>?, token: String?) =
                f.transport.request(url, fields, token)

            override suspend fun preparationGet(
                url: String,
                token: String?,
                onTransport: (DiagnosticTransportSnapshot?) -> Unit,
            ): RechargeResponse {
                reads++
                if (reads == 2) throw RechargeFailure("P-NETWORK")
                dispatched++
                return f.transport.request(url, null, token).copy(transportSnapshot = trace)
            }
        }
        val service = CardRechargeService(f.history, f.store, transport)

        service.open()

        val root = recorder.terminals(DiagnosticDetail.CARD_RECHARGE_PREPARE).single()
        val failed = recorder.terminals(DiagnosticDetail.CARD_RECHARGE_CONFIG).single()
        assertEquals(2, reads)
        assertEquals(1, dispatched)
        assertNull(failed.transport)
        assertNull(root.transport)
        assertEquals(0, f.createPosts)
        assertEquals(0, f.payPosts)
        assertNull(f.store.value)
    }

    @Test fun `rules failure without fee item trace does not inherit signer trace`() = runTest {
        val f = CardRechargeServiceTest.Fixture()
        val signerTrace = DiagnosticTransportSnapshot(
            stage = DiagnosticTransportStage.RESPONSE_BODY,
            totalMs = 52,
            requestHeadersCount = 1,
        )
        val requests = mutableListOf<String>()
        val transport = RechargeTransport { url, fields, token ->
            requests += url
            val response = f.transport.request(url, fields, token)
            when {
                url.contains("/singleFeeitem?") -> response.copy(
                    body = """{"feeitem":{"maxmoney":"synthetic-rule-canary"}}""",
                    transportSnapshot = null,
                )
                url == CardRechargeProtocol.SIGNER_SOURCE -> response.copy(transportSnapshot = signerTrace)
                else -> response
            }
        }
        val service = CardRechargeService(f.history, f.store, transport)

        service.open()

        val root = recorder.terminals(DiagnosticDetail.CARD_RECHARGE_PREPARE).single()
        val operationId = root.operationId
        val signer = recorder.terminals(DiagnosticDetail.CARD_RECHARGE_SIGNER)
            .single { it.operationId == operationId }
        val rules = recorder.terminals(DiagnosticDetail.CARD_RECHARGE_RULES)
            .single { it.operationId == operationId }
        assertEquals(DiagnosticResult.FAIL, root.result)
        assertEquals(DiagnosticResult.OK, signer.result)
        assertEquals(52L, signer.transport?.totalMs)
        assertEquals(DiagnosticResult.FAIL, rules.result)
        assertNull(rules.transport)
        assertNull(root.transport)
        assertEquals(5, requests.size)
        assertEquals(0, f.createPosts)
        assertEquals(0, f.payPosts)
        assertNull(f.store.value)
        assertFalse(recorder.events.joinToString().contains("synthetic-rule-canary"))
    }

    @Test fun `create reply unknown has one post after its durable marker and cannot retry`() = runTest {
        val f = CardRechargeServiceTest.Fixture()
        f.service.open()
        recorder.events.clear()
        f.failCreate = true
        f.onCreate = {
            assertNotNull(f.store.value)
            assertNull(f.store.value!!.orderId)
            assertEquals(DiagnosticResult.PENDING, recorder.forDetail(DiagnosticDetail.CARD_ORDER_CREATE).last().result)
        }
        assertNull(f.service.submit("20"))
        assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.UNKNOWN),
            recorder.forDetail(DiagnosticDetail.CARD_ORDER_CREATE).map { it.result })
        val original = f.store.value
        assertNotNull(original)
        assertNull(original!!.orderId)
        assertNull(f.service.submit("20"))
        assertEquals(1, f.createPosts)
        assertEquals(0, f.payPosts)
        assertEquals(original, f.store.value)
        assertEquals(listOf(DiagnosticResult.UNKNOWN, DiagnosticResult.BLOCKED),
            recorder.terminals(DiagnosticDetail.CARD_ORDER_CREATE).map { it.result })
        assertEquals(1, recorder.forDetail(DiagnosticDetail.CARD_ORDER_CREATE).count { it.result == DiagnosticResult.PENDING })
        assertTrue(recorder.terminals(DiagnosticDetail.CARD_PAYMENT_CONFIRMED).isEmpty())
    }

    @Test fun `unreadable payment preparation cannot submit and retains the created order`() = runTest {
        val f = CardRechargeServiceTest.Fixture()
        val service = CardRechargeService(f.history, f.store, RechargeTransport { url, fields, token ->
            if (url.contains("/getpayinfo?")) RechargeResponse(200, """{"order":{"orderid":"${f.order}"},"payList":[]}""")
            else f.transport.request(url, fields, token)
        })
        service.open()
        recorder.events.clear()
        assertNull(service.submit("20"))
        val terminal = recorder.terminals(DiagnosticDetail.CARD_PAYMENT_PREPARE).single()
        assertEquals(DiagnosticResult.FAIL, terminal.result)
        assertEquals(DiagnosticPhase.PARSE, terminal.phase)
        assertEquals(DiagnosticReason.SCHEMA, terminal.reason)
        assertTrue(recorder.forDetail(DiagnosticDetail.CARD_PAYMENT_SUBMIT).isEmpty())
        assertEquals(1, f.createPosts)
        assertEquals(0, f.payPosts)
        assertEquals(f.order, f.store.value!!.orderId)
        assertNull(f.store.value!!.payId)
        assertTrue(service.state.value.recoveryRequired)
    }

    @Test fun `payment response unknown retains protection and never means payment confirmed`() = runTest {
        val f = CardRechargeServiceTest.Fixture()
        f.service.open()
        recorder.events.clear()
        f.failPay = true
        assertNull(f.service.submit("20"))
        assertEquals(DiagnosticResult.OK, recorder.terminals(DiagnosticDetail.CARD_ORDER_CREATE).single().result)
        assertEquals(DiagnosticResult.OK, recorder.terminals(DiagnosticDetail.CARD_PAYMENT_PREPARE).single().result)
        assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.UNKNOWN),
            recorder.forDetail(DiagnosticDetail.CARD_PAYMENT_SUBMIT).map { it.result })
        assertEquals(f.order, f.store.value!!.orderId)
        assertEquals("81", f.store.value!!.payId)
        assertTrue(f.service.state.value.recoveryRequired)
        assertNull(f.service.submit("20"))
        assertEquals(1, f.createPosts)
        assertEquals(1, f.payPosts)
        assertTrue(recorder.terminals(DiagnosticDetail.CARD_PAYMENT_CONFIRMED).isEmpty())
    }

    @Test fun `business auth rejection after either write is unknown and never replays the request`() = runTest {
        for ((stage, detail) in listOf("create" to DiagnosticDetail.CARD_ORDER_CREATE, "pay" to DiagnosticDetail.CARD_PAYMENT_SUBMIT)) {
            recorder.events.clear()
            val f = CardRechargeServiceTest.Fixture()
            f.service.open()
            f.expireOn = stage
            assertNull(f.service.submit("20"))
            val terminal = recorder.terminals(detail).single()
            assertEquals(DiagnosticResult.UNKNOWN, terminal.result)
            assertEquals(DiagnosticReason.AUTH, terminal.reason)
            assertFalse(f.history.hasSession())
            assertNotNull(f.store.value)
            assertTrue(f.service.state.value.recoveryRequired)
            assertEquals(1, f.createPosts)
            assertEquals(if (stage == "pay") 1 else 0, f.payPosts)
            assertEquals(1, f.authentications)
        }
    }

    @Test fun `failed pending commit blocks create before its pending event and before any write`() = runTest {
        val f = CardRechargeServiceTest.Fixture()
        val store = object : RechargePendingStore by f.store {
            override fun save(record: RechargePending) { throw RechargeFailure("P-PENDING-STORE") }
        }
        val service = CardRechargeService(f.history, store, f.transport)
        service.open()
        recorder.events.clear()
        assertNull(service.submit("20"))
        val events = recorder.forDetail(DiagnosticDetail.CARD_ORDER_CREATE)
        assertEquals(listOf(DiagnosticResult.BLOCKED), events.map { it.result })
        assertEquals(DiagnosticReason.STORAGE, events.single().reason)
        assertEquals(0, f.createPosts)
        assertEquals(0, f.payPosts)
        assertNull(f.store.value)
        assertTrue(service.state.value.recoveryRequired)
    }

    @Test fun `unknown status keeps pending and only an explicit school success confirms payment`() = runTest {
        val f = CardRechargeServiceTest.Fixture()
        f.service.open()
        assertNotNull(f.service.submit("20"))
        assertTrue(recorder.terminals(DiagnosticDetail.CARD_PAYMENT_CONFIRMED).isEmpty())
        recorder.events.clear()
        f.status = "synthetic-status-canary"
        val original = f.store.value
        f.service.refresh()
        assertEquals(original, f.store.value)
        assertTrue(f.service.state.value.recoveryRequired)
        assertEquals(DiagnosticResult.UNKNOWN, recorder.terminals(DiagnosticDetail.CARD_ORDER_STATUS).single().result)
        assertEquals(DiagnosticPhase.PARSE, recorder.terminals(DiagnosticDetail.CARD_ORDER_STATUS).single().phase)
        assertEquals(1, f.statusRequests)
        assertTrue(recorder.terminals(DiagnosticDetail.CARD_PAYMENT_CONFIRMED).isEmpty())
        f.status = "1"
        f.service.refresh()
        assertEquals(listOf(DiagnosticResult.UNKNOWN, DiagnosticResult.OK),
            recorder.terminals(DiagnosticDetail.CARD_ORDER_STATUS).map { it.result })
        assertEquals(2, recorder.terminals(DiagnosticDetail.CARD_ORDER_STATUS).map { it.operationId }.distinct().size)
        assertEquals(DiagnosticResult.OK, recorder.terminals(DiagnosticDetail.CARD_PAYMENT_CONFIRMED).single().result)
        assertNull(f.store.value)
        assertEquals(2, f.statusRequests)
        assertEquals(1, f.createPosts)
        assertEquals(1, f.payPosts)
        assertFalse(recorder.events.joinToString().contains("canary"))
    }

    @Test fun `unknown order marker never probes status and closes the status action as unknown`() = runTest {
        val f = CardRechargeServiceTest.Fixture()
        f.service.open()
        f.failCreate = true
        assertNull(f.service.submit("20"))
        recorder.events.clear()
        val original = f.store.value
        f.service.refresh()
        assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.UNKNOWN),
            recorder.forDetail(DiagnosticDetail.CARD_ORDER_STATUS).map { it.result })
        assertEquals(0, f.statusRequests)
        assertEquals(1, f.createPosts)
        assertEquals(0, f.payPosts)
        assertEquals(original, f.store.value)
        assertTrue(f.service.state.value.recoveryRequired)
    }

    @Test fun `unreadable status closes parse failure without clearing pending or confirming payment`() = runTest {
        val f = CardRechargeServiceTest.Fixture()
        var statusRequests = 0
        val service = CardRechargeService(f.history, f.store, RechargeTransport { url, fields, token ->
            if (url.contains("/personal_data?")) {
                statusRequests++
                RechargeResponse(200, "<html>synthetic-status-body-canary</html>")
            } else f.transport.request(url, fields, token)
        })
        service.open()
        assertNotNull(service.submit("20"))
        val original = f.store.value
        recorder.events.clear()
        service.refresh()
        val terminal = recorder.terminals(DiagnosticDetail.CARD_ORDER_STATUS).single()
        assertEquals(DiagnosticResult.FAIL, terminal.result)
        assertEquals(DiagnosticReason.SCHEMA, terminal.reason)
        assertEquals(DiagnosticPhase.PARSE, terminal.phase)
        assertEquals(1, statusRequests)
        assertEquals(original, f.store.value)
        assertTrue(service.state.value.recoveryRequired)
        assertEquals(1, f.createPosts)
        assertEquals(1, f.payPosts)
        assertTrue(recorder.terminals(DiagnosticDetail.CARD_PAYMENT_CONFIRMED).isEmpty())
        assertFalse(recorder.events.joinToString().contains("canary"))
    }

    @Test fun `sink failure leaves the exact write and handoff count unchanged`() = runTest {
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) = error("synthetic sink failure")
        })
        val f = CardRechargeServiceTest.Fixture()
        f.service.open()
        val handoff = f.service.submit("20")!!
        assertEquals("weixin://wap/pay?fixture=1", handoff.consume())
        assertNull(handoff.consume())
        assertNotNull(f.store.value)
        assertEquals(1, f.createPosts)
        assertEquals(1, f.payPosts)
        assertEquals(0, f.statusRequests)
    }
}
