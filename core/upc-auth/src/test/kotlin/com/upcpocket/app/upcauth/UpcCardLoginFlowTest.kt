package com.upcpocket.app.upcauth

import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticSink
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportSnapshot
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportStage
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportError
import com.upcpocket.app.platform.diagnostics.EpochBoundDiagnosticSink
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class UpcCardLoginFlowTest {
    private val cas = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fcallback"
    private val html = """<form id="loginForm"><input name="username"><input name="password" type="password">
        <input name="execution" value="fixture-execution">
        <input name="_eventId" value="submit"><input name="type" value="username_password"></form>"""
    private fun response(status: Int = 200, body: String = "", location: String? = null, cookie: String? = null) =
        UpcAuthHttpResponse(status, buildMap {
            location?.let { put("location", listOf(it)) }; cookie?.let { put("set-cookie", listOf(it)) }
        }, body.toByteArray())

    @Test fun `normal password form is not blocked by inactive alternative login tabs`() {
        val page = html + """<button>扫码登录</button><button>验证码登录</button>
            <form id="smsForm" style="display:none"><input name="type" value="username_smstoken"><input name="captcha"></form>
            <script>const selector = "[name='captcha']"; config.captcha = null;</script>"""
        val form = CardCasProtocol.form(page, cas)
        assertFalse(form.interactionRequired)
        assertTrue(CardCasProtocol.body(form, "2099999999", "fixture-passphrase", "").contains("password="))
        assertTrue(CardCasProtocol.form(html.replace("</form>", "<input name='captcha'></form>"), cas).interactionRequired)
        assertTrue(CardCasProtocol.form(html + "<script>config.captcha = { unsupported: true };</script>", cas).interactionRequired)
    }

    @Test fun `form submit value is replayed with url encoding and missing value keeps legacy default`() {
        val provided = html.replace("</form>", "<input name='submit' value='登录&继续'></form>")
        val body = CardCasProtocol.body(CardCasProtocol.form(provided, cas), "2099999999", "safe-passphrase", "")
        assertTrue(body.contains("submit=%E7%99%BB%E5%BD%95%26%E7%BB%A7%E7%BB%AD"))
        val legacy = CardCasProtocol.body(CardCasProtocol.form(html, cas), "2099999999", "safe-passphrase", "")
        assertTrue(legacy.contains("submit=LOGIN"))
    }

    @Test fun `form action must stay on the same exact cas service`() {
        val changedService = html.replace("id=\"loginForm\"", "id=\"loginForm\" action=\"/cas/login?service=https%3A%2F%2Fevil.example%2F\"")
        assertThrows(IOException::class.java) { CardCasProtocol.form(changedService, cas) }
        val duplicateService = cas + "&service=https%3A%2F%2Fmb-xyk.upc.edu.cn%2Fcallback"
        assertThrows(IOException::class.java) { CardCasProtocol.form(html, duplicateService) }
    }

    @Test fun `direct platform auth completes without ticket exchange or loading frontend`() {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val replies = ArrayDeque(listOf(
            response(302, location = cas), response(body = html, cookie = "fixture=cas-cookie; Path=/; Secure"),
            response(302, location = "http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-fixture"),
            response(302, location = "https://mb-xyk.upc.edu.cn/plat/?name=login&synjones-auth=fixture-token"),
        ))
        val transport = object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                requests += request
                return replies.removeFirst()
            }
        }
        val flow = UpcCardLoginFlow(transport)
        assertEquals("fixture-token", flow.login(flow.begin(), "2099999999", "fixture-password", ""))
        assertEquals(4, requests.size)
        assertEquals(1, requests.count { it.method == "POST" })
        assertTrue(requests[2].headers["Cookie"].orEmpty().contains("fixture=cas-cookie"))
        assertNull(requests[3].headers["Cookie"])
        assertTrue(requests.all { it.url.startsWith("https://") })
        assertEquals("https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-fixture", requests.last().url)
    }

    @Test fun `platform ticket is exchanged once at fixed endpoint`() {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val replies = ArrayDeque(listOf(response(302, location = cas), response(body = html),
            response(302, location = "https://mb-xyk.upc.edu.cn/plat/?ticket=fixture-ticket"),
            response(body = """{"access_token":"fixture-token"}""")))
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                requests += request; return replies.removeFirst()
            }
        })
        assertEquals("fixture-token", flow.login(flow.begin(), "2099999999", "fixture-password", ""))
        assertEquals(CardAuthOrigins.BASE + "/berserker-auth/oauth/token", requests.last().url)
        assertTrue(requests.last().body.orEmpty().contains("username=fixture-ticket"))
        assertFalse(requests.last().body.orEmpty().contains("fixture-password"))
    }

    @Test fun `web direct token uses the shared parser without a second network request`() {
        var calls = 0
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                calls++
                error("Direct result does not require ticket exchange")
            }
        })
        assertEquals(
            "fixture-token",
            flow.completeLoginResult("https://mb-xyk.upc.edu.cn/plat/?name=loginTransit&synjones-auth=fixture-token"),
        )
        assertEquals(0, calls)
    }

    @Test fun `web platform ticket is exchanged once and CAS service ticket is rejected`() {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                requests += request
                return response(body = """{"access_token":"fixture-token"}""")
            }
        })
        assertEquals("fixture-token", flow.completeLoginResult("https://mb-xyk.upc.edu.cn/plat/?ticket=fixture-ticket"))
        assertEquals(1, requests.size)
        assertEquals(CardAuthOrigins.BASE + "/berserker-auth/oauth/token", requests.single().url)
        assertThrows(IOException::class.java) {
            flow.completeLoginResult("https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-fixture")
        }
        assertEquals(1, requests.size)
    }

    @Test fun `web result rejects ambiguous token and ticket parameters`() {
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse = error("Ambiguous result must not be exchanged")
        })
        assertThrows(IOException::class.java) {
            flow.completeLoginResult("https://mb-xyk.upc.edu.cn/plat/?ticket=one&ticket=two")
        }
        assertThrows(IOException::class.java) {
            flow.completeLoginResult("https://mb-xyk.upc.edu.cn/plat/?ticket=fixture&synjones-auth=fixture")
        }
    }

    @Test fun `unknown post refusal offers manual login after one password submission`() {
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val replies = ArrayDeque(listOf(response(302, location = cas), response(body = html), response(body = html)))
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                requests += request; return replies.removeFirst()
            }
        })
        val form = flow.begin()
        assertThrows(CardCasInteractionRequired::class.java) { flow.login(form, "2099999999", "fixture-password", "") }
        assertEquals(3, requests.size)
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals(cas, requests.single { it.method == "POST" }.url)
    }

    @Test fun `external redirect stops before any request to external host`() {
        var calls = 0
        val flow = UpcCardLoginFlow(object : UpcAuthTransport {
            override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                calls++; return response(302, location = "https://attacker.example/login")
            }
        })
        assertThrows(IOException::class.java) { flow.begin() }
        assertEquals(1, calls)
    }

    @Test fun `network failure preserves entry or password post diagnostics without retry`() {
        assertTransportFailureAtEntryAndPost(
            UpcAuthTransportFailure.NETWORK, DiagnosticDetail.TRANSPORT_NETWORK, DiagnosticReason.NETWORK,
        )
    }

    @Test fun `timeout preserves entry or password post diagnostics without retry`() {
        assertTransportFailureAtEntryAndPost(
            UpcAuthTransportFailure.TIMEOUT, DiagnosticDetail.TRANSPORT_TIMEOUT, DiagnosticReason.NETWORK,
        )
    }

    @Test fun `tls failure preserves entry or password post diagnostics without retry`() {
        assertTransportFailureAtEntryAndPost(
            UpcAuthTransportFailure.TLS, DiagnosticDetail.TRANSPORT_TLS, DiagnosticReason.TLS,
        )
    }

    @Test fun `oversized response preserves entry or password post diagnostics without retry`() {
        assertTransportFailureAtEntryAndPost(
            UpcAuthTransportFailure.TOO_LARGE, DiagnosticDetail.TRANSPORT_TOO_LARGE, DiagnosticReason.SCHEMA,
        )
    }

    @Test fun `protocol failure preserves entry or password post diagnostics without retry`() {
        assertTransportFailureAtEntryAndPost(
            UpcAuthTransportFailure.PROTOCOL, DiagnosticDetail.TRANSPORT_PROTOCOL, DiagnosticReason.SCHEMA,
        )
    }

    @Test fun `transport failure snapshot is on the existing stop exactly once`() {
        val events = mutableListOf<DiagnosticEvent>()
        val snapshot = DiagnosticTransportSnapshot(DiagnosticTransportStage.RESPONSE_BODY, 23,
            error = DiagnosticTransportError.EOF, bodyMs = 12, requestHeadersCount = 1)
        val failure = UpcAuthTransportException(UpcAuthTransportFailure.NETWORK,
            snapshot, httpStatus = 200, responseBytes = 17)
        var calls = 0
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) { events += event }
        })
        try {
            val flow = UpcCardLoginFlow(object : UpcAuthTransport {
                override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                    calls++
                    assertTrue(request.collectTransportDiagnostics)
                    throw failure
                }
            })
            assertSame(failure, assertThrows(UpcAuthTransportException::class.java) { flow.begin() })
            val stop = events.single { it.action == DiagnosticAction.AUTH_STOP }
            assertSame(snapshot, stop.transport)
            assertEquals(200, stop.httpStatus)
            assertEquals(17, stop.responseBytes)
            assertEquals(0, stop.passwordSubmitted)
            assertEquals(DiagnosticPhase.ENTRY_GET, stop.phase)
            assertEquals(1, events.count { it.transport != null })
            assertEquals(events.first().operationId, stop.operationId)
            assertEquals(1, calls)
        } finally { Diagnostics.install(null) }
    }

    @Test fun `successful transport is not relabeled when form parsing later fails`() {
        val events = mutableListOf<DiagnosticEvent>()
        val snapshot = DiagnosticTransportSnapshot(DiagnosticTransportStage.RESPONSE_BODY, 12,
            requestHeadersCount = 1)
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) { events += event }
        })
        try {
            val flow = UpcCardLoginFlow(object : UpcAuthTransport {
                override fun execute(request: UpcAuthHttpRequest) = UpcAuthHttpResponse(
                    200, emptyMap(), "<html>synthetic body canary</html>".toByteArray(), snapshot)
            })
            assertThrows(IOException::class.java) { flow.begin() }
            val summary = events.single { it.transport != null }
            assertEquals(DiagnosticAction.AUTH_STAGE, summary.action)
            assertEquals(DiagnosticResult.OK, summary.result)
            assertSame(snapshot, summary.transport)
            assertNull(summary.transport?.error)
            val stop = events.single { it.action == DiagnosticAction.AUTH_STOP }
            assertEquals(DiagnosticPhase.CAS_FORM, stop.phase)
            assertNull(stop.transport)
            assertFalse(events.toString().contains("synthetic body canary"))
        } finally { Diagnostics.install(null) }
    }

    @Test fun `token exchange and all preceding calls emit one snapshot each without another password post`() {
        val events = mutableListOf<DiagnosticEvent>()
        val requests = mutableListOf<UpcAuthHttpRequest>()
        val snapshots = mutableListOf<DiagnosticTransportSnapshot>()
        val replies = ArrayDeque(listOf(response(302, location = cas), response(body = html),
            response(302, location = "https://mb-xyk.upc.edu.cn/plat/?ticket=fixture-ticket"),
            response(body = """{"access_token":"fixture-token"}""")))
        Diagnostics.install(object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) { events += event }
        })
        try {
            val flow = UpcCardLoginFlow(object : UpcAuthTransport {
                override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                    requests += request
                    val next = replies.removeFirst()
                    val snapshot = DiagnosticTransportSnapshot(DiagnosticTransportStage.RESPONSE_BODY,
                        totalMs = requests.size.toLong(), requestHeadersCount = 1)
                    snapshots += snapshot
                    return UpcAuthHttpResponse(next.status, next.headers, next.body, snapshot)
                }
            })
            assertEquals("fixture-token", flow.login(flow.begin(), "2099999999", "fixture-password", ""))
            val summaries = events.filter { it.transport != null }
            assertEquals(4, summaries.size)
            snapshots.forEach { snapshot -> assertEquals(1, summaries.count { it.transport === snapshot }) }
            assertEquals(listOf(DiagnosticPhase.ENTRY_GET, DiagnosticPhase.CAS_GET,
                DiagnosticPhase.CAS_POST, DiagnosticPhase.SESSION_ADOPT), summaries.map { it.phase })
            assertEquals(1, summaries.map { it.operationId }.distinct().size)
            assertTrue(summaries.all { it.result == DiagnosticResult.OK })
            assertTrue(requests.all { it.collectTransportDiagnostics })
            assertEquals(1, requests.count { it.url == cas && it.method == "POST" })
            assertEquals(1, requests.count { it.url.endsWith("/berserker-auth/oauth/token") })
        } finally { Diagnostics.install(null) }
    }

    @Test fun `http failures carry the successful transport snapshot only on their stop event`() {
        for (status in listOf(400, 429, 503)) {
            val events = mutableListOf<DiagnosticEvent>()
            val snapshot = DiagnosticTransportSnapshot(DiagnosticTransportStage.RESPONSE_BODY, 4)
            Diagnostics.install(object : DiagnosticSink {
                override fun record(event: DiagnosticEvent) { events += event }
            })
            try {
                val flow = UpcCardLoginFlow(object : UpcAuthTransport {
                    override fun execute(request: UpcAuthHttpRequest) = UpcAuthHttpResponse(
                        status, emptyMap(), byteArrayOf(1, 2, 3), snapshot)
                })
                assertThrows(CardCasHttpFailure::class.java) { flow.begin() }
                val summary = events.single { it.transport != null }
                assertEquals(DiagnosticAction.AUTH_STOP, summary.action)
                assertEquals(DiagnosticResult.FAIL, summary.result)
                assertSame(snapshot, summary.transport)
                assertNull(summary.transport?.error)
                assertEquals(status, summary.httpStatus)
                assertEquals(3, summary.responseBytes)
                assertEquals(0, summary.passwordSubmitted)
            } finally { Diagnostics.install(null) }
        }
    }

    @Test fun `denied cas forms still emit one transport snapshot before local form classification`() {
        for (status in listOf(401, 403)) {
            val events = mutableListOf<DiagnosticEvent>()
            val replies = ArrayDeque(listOf(response(302, location = cas), response(status, html)))
            Diagnostics.install(object : DiagnosticSink {
                override fun record(event: DiagnosticEvent) { events += event }
            })
            try {
                val flow = UpcCardLoginFlow(object : UpcAuthTransport {
                    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                        val next = replies.removeFirst()
                        return UpcAuthHttpResponse(next.status, next.headers, next.body,
                            DiagnosticTransportSnapshot(DiagnosticTransportStage.RESPONSE_BODY, 1))
                    }
                })
                assertFalse(flow.begin().interactionRequired)
                assertEquals(2, events.count { it.transport != null })
                assertEquals(1, events.count { it.transport != null && it.httpStatus == status })
                assertTrue(events.filter { it.transport != null }.all { it.result == DiagnosticResult.OK })
                assertEquals(if (status == 401) DiagnosticDetail.CAS_GET_401_FORM else DiagnosticDetail.CAS_GET_403_FORM,
                    events.single { it.transport != null && it.httpStatus == status }.detail)
            } finally { Diagnostics.install(null) }
        }
    }

    @Test fun `password post transport summary never claims get form or credential acceptance`() {
        val tokenUrl = "https://mb-xyk.upc.edu.cn/plat/?synjones-auth=fixture-token"
        val cases = listOf(
            response(200, html + "<div id='errorDiv'>用户名或密码错误</div>") to DiagnosticDetail.CAS_POST_EXPLICIT_REJECTION,
            response(200, html) to DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
            response(200, "<html>synthetic unclassified body</html>") to DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
            response(401, html) to DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
            response(403, html) to DiagnosticDetail.CAS_POST_UNKNOWN_REJECTION,
            response(302, location = tokenUrl) to DiagnosticDetail.SESSION_ADOPTED,
        )
        for ((postResponse, terminalDetail) in cases) {
            val events = mutableListOf<DiagnosticEvent>()
            val requests = mutableListOf<UpcAuthHttpRequest>()
            val replies = ArrayDeque(listOf(response(302, location = cas), response(body = html), postResponse))
            Diagnostics.install(object : DiagnosticSink {
                override fun record(event: DiagnosticEvent) { events += event }
            })
            try {
                val flow = UpcCardLoginFlow(object : UpcAuthTransport {
                    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                        requests += request
                        val next = replies.removeFirst()
                        return UpcAuthHttpResponse(next.status, next.headers, next.body,
                            DiagnosticTransportSnapshot(DiagnosticTransportStage.RESPONSE_BODY,
                                totalMs = requests.size.toLong()))
                    }
                })
                val form = flow.begin()
                if (terminalDetail == DiagnosticDetail.SESSION_ADOPTED) {
                    assertEquals("fixture-token", flow.login(form, "2099999999", "fixture-password", ""))
                } else {
                    assertThrows(IOException::class.java) {
                        flow.login(form, "2099999999", "fixture-password", "")
                    }
                }
                val summaries = events.filter { it.transport != null }
                assertEquals(3, summaries.size)
                val post = summaries.single { it.phase == DiagnosticPhase.CAS_POST }
                assertEquals(DiagnosticAction.AUTH_STAGE, post.action)
                assertEquals(DiagnosticResult.OK, post.result)
                assertEquals(DiagnosticDetail.RESPONSE_HTTP, post.detail)
                assertEquals(postResponse.status, post.httpStatus)
                assertEquals(1, post.passwordSubmitted)
                assertEquals(terminalDetail, events.last().detail)
                assertFalse(events.any { it.phase == DiagnosticPhase.CAS_POST && it.detail in setOf(
                    DiagnosticDetail.CAS_GET_401_FORM, DiagnosticDetail.CAS_GET_403_FORM,
                    DiagnosticDetail.CAS_FORM_ACCEPTED) })
                assertEquals(3, requests.size)
                assertEquals(1, requests.count { it.method == "POST" })
                assertEquals(1, summaries.map { it.operationId }.distinct().size)
                assertFalse(events.toString().contains("synthetic unclassified body"))
            } finally { Diagnostics.install(null) }
        }
    }

    @Test fun `throwing sink and failed epoch capture preserve exactly one password submission`() {
        for (failEpoch in listOf(false, true)) {
            var sinkWrites = 0
            if (failEpoch) {
                Diagnostics.install(object : EpochBoundDiagnosticSink {
                    override fun captureEpoch(): Long = error("synthetic epoch secret canary")
                    override fun record(event: DiagnosticEvent) { sinkWrites++ }
                    override fun record(event: DiagnosticEvent, expectedEpoch: Long?) { sinkWrites++ }
                })
            } else {
                Diagnostics.install(object : DiagnosticSink {
                    override fun record(event: DiagnosticEvent) { error("synthetic sink secret canary") }
                })
            }
            val requests = mutableListOf<UpcAuthHttpRequest>()
            val replies = ArrayDeque(listOf(response(302, location = cas), response(body = html),
                response(302, location = "http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=ST-fixture"),
                response(302, location = "https://mb-xyk.upc.edu.cn/plat/?name=login&synjones-auth=fixture-token")))
            try {
                val flow = UpcCardLoginFlow(object : UpcAuthTransport {
                    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                        requests += request
                        val next = replies.removeFirst()
                        return UpcAuthHttpResponse(next.status, next.headers, next.body,
                            DiagnosticTransportSnapshot(DiagnosticTransportStage.RESPONSE_BODY, 1))
                    }
                })
                assertEquals("fixture-token", flow.login(flow.begin(), "2099999999", "fixture-password", ""))
                assertEquals(4, requests.size)
                assertEquals(1, requests.count { it.method == "POST" })
                assertEquals(0, sinkWrites)
            } finally { Diagnostics.install(null) }
        }
    }

    @Test fun `a late transport result cannot write after the captured account epoch was cleared`() {
        var epoch = 1L
        val events = mutableListOf<DiagnosticEvent>()
        Diagnostics.install(object : EpochBoundDiagnosticSink {
            override fun captureEpoch(): Long = epoch
            override fun record(event: DiagnosticEvent) = error("must remain epoch bound")
            override fun record(event: DiagnosticEvent, expectedEpoch: Long?) {
                if (expectedEpoch == epoch) events += event
            }
        })
        try {
            val flow = UpcCardLoginFlow(object : UpcAuthTransport {
                override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                    epoch++
                    events.clear()
                    return UpcAuthHttpResponse(200, emptyMap(), "<html>unparseable</html>".toByteArray(),
                        DiagnosticTransportSnapshot(DiagnosticTransportStage.RESPONSE_BODY, 2))
                }
            })
            assertThrows(IOException::class.java) { flow.begin() }
            assertTrue(events.isEmpty())
        } finally { Diagnostics.install(null) }
    }

    private fun assertTransportFailureAtEntryAndPost(
        failure: UpcAuthTransportFailure,
        detail: DiagnosticDetail,
        reason: DiagnosticReason,
    ) {
        for (afterPassword in listOf(false, true)) {
            val events = mutableListOf<DiagnosticEvent>()
            val requests = mutableListOf<UpcAuthHttpRequest>()
            val exception = UpcAuthTransportException(failure)
            Diagnostics.install(object : DiagnosticSink {
                override fun record(event: DiagnosticEvent) { events += event }
            })
            try {
                val flow = UpcCardLoginFlow(object : UpcAuthTransport {
                    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
                        requests += request
                        return when {
                            !afterPassword -> throw exception
                            requests.size == 1 -> response(302, location = cas)
                            requests.size == 2 -> response(body = html)
                            else -> throw exception
                        }
                    }
                })
                val form = if (afterPassword) flow.begin() else null
                val thrown = assertThrows(UpcAuthTransportException::class.java) {
                    if (form == null) flow.begin()
                    else flow.login(form, "2099999999", "synthetic-password", "")
                }
                assertSame(exception, thrown)
                val stop = events.single { it.action == DiagnosticAction.AUTH_STOP }
                assertEquals(DiagnosticModule.CARD, stop.module)
                assertEquals(DiagnosticResult.FAIL, stop.result)
                assertEquals(if (afterPassword) DiagnosticPhase.CAS_POST else DiagnosticPhase.ENTRY_GET, stop.phase)
                assertEquals(detail, stop.detail)
                assertEquals(reason, stop.reason)
                assertEquals(if (afterPassword) 1 else 0, stop.passwordSubmitted)
                assertEquals(events.first().operationId, stop.operationId)
                assertNull(stop.httpStatus)
                assertEquals(if (afterPassword) 3 else 1, requests.size)
                assertEquals(if (afterPassword) 1 else 0, requests.count { it.method == "POST" })
                assertEquals(CardAuthOrigins.ENTRY, requests.first().url)
                if (afterPassword) assertEquals(cas, requests.single { it.method == "POST" }.url)
            } finally {
                Diagnostics.install(null)
            }
        }
    }
}
