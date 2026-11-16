package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.data.card.CardDiagnosticRecorder
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportError
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportStage
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardHistoryReadTransportTest {
    private val recorder = CardDiagnosticRecorder()
    private val identity = """{"code":200,"data":{"sno":"2099999999"}}"""
    private val page = """{"code":200,"data":{"current":1,"pages":1,"total":1,"records":[{"orderId":"synthetic-order","tranamt":820,"typeFrom":"2","jndatetimeStr":"2026-11-12 12:00:00"}]}}"""
    private val summary = """{"code":200,"data":{"income":100,"expenses":20}}"""

    private class Account : SchoolAccountStudentIdentityReader {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(
            SchoolAccountState(SchoolAccountStatus.SAVED_UNVERIFIED, AccountGeneration(1)),
        )
        override val studentIdentity = kotlinx.coroutines.flow.MutableStateFlow<SchoolStudentIdentity?>(
            SchoolStudentIdentity(SchoolAccountId("fixture_account"), "2099999999", AccountGeneration(1)),
        )
    }

    @After fun detachDiagnostics() { Diagnostics.install(null) }

    @Test fun `recovery policy accepts only fixed card identity history and summary gets`() {
        val valid = listOf(
            "https://${CardHistoryProtocol.HOST}/berserker-base/user?synAccessSource=h5",
            "https://${CardHistoryProtocol.HOST}/berserker-search/search/personal/turnover?size=50&current=1&synAccessSource=h5",
            "https://${CardHistoryProtocol.HOST}/berserker-search/statistics/turnover/count?size=50&current=1&synAccessSource=h5&timeFrom=2026-11-01&timeTo=2026-11-30",
        )
        valid.forEach { assertTrue(CardHistoryReadPolicy.allows(Request.Builder().url(it).build())) }

        val denied = listOf(
            Request.Builder().url("https://${CardHistoryProtocol.HOST}/berserker-auth/cas/login/wislearn?ticket=synthetic-ticket").build(),
            Request.Builder().url("https://${CardHistoryProtocol.HOST}/berserker-base/user?synAccessSource=h5&extra=1").build(),
            Request.Builder().url("https://outside.example.test/berserker-base/user?synAccessSource=h5").build(),
            Request.Builder().url("https://${CardHistoryProtocol.HOST}/berserker-base/user?synAccessSource=h5").post("synthetic".toRequestBody()).build(),
            Request.Builder().url("https://${CardHistoryProtocol.HOST}/berserker-search/search/personal/turnover?size=50&size=20&current=1&synAccessSource=h5").build(),
        )
        denied.forEach { assertFalse(CardHistoryReadPolicy.allows(it)) }

        val transport = CardHistoryReadTransport(
            OkHttpClient.Builder().retryOnConnectionFailure(true).followRedirects(true).build(),
        )
        val allowedCall = transport.prepare(Request.Builder().url(valid.first()).build())
        assertNotNull(allowedCall.trace)
        assertTrue(allowedCall.client.retryOnConnectionFailure)
        assertFalse(allowedCall.client.followRedirects)
        denied.forEach { request ->
            val prepared = transport.prepare(request)
            assertNull(prepared.trace)
            assertFalse(prepared.client.retryOnConnectionFailure)
            assertFalse(prepared.client.followRedirects)
        }
    }

    @Test fun `identity history page and summary recover second loopback address and record bounded traces`() = runTest {
        LoopbackTlsFixture(listOf(UNREACHABLE_LOOPBACK, SERVER_LOOPBACK)).use { fixture ->
            Diagnostics.install(recorder)
            fixture.server.enqueue(MockResponse().setBody(identity))
            fixture.server.enqueue(MockResponse().setBody(page))
            fixture.server.enqueue(MockResponse().setBody(summary))
            val identityResponseReady = CountDownLatch(1)
            val releaseIdentityResponse = CountDownLatch(1)
            val http = fixture.client.newBuilder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().url.encodedPath == "/berserker-base/user") {
                    identityResponseReady.countDown()
                    releaseIdentityResponse.await()
                }
                response
            }.build()
            val client = client(http)

            val login = async(Dispatchers.IO) { client.login() }
            val identityRequest = try {
                assertTrue(identityResponseReady.await(2, TimeUnit.SECONDS))
                val received = fixture.server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(received)
                assertFalse("A session must not be published before identity verification completes", client.hasSession())
                requireNotNull(received)
            } finally {
                releaseIdentityResponse.countDown()
            }
            login.await()
            assertTrue(client.hasSession())
            fixture.client.connectionPool.evictAll()

            assertEquals(1, client.page().data.rows.size)
            fixture.client.connectionPool.evictAll()
            assertEquals(100L, client.summary().incomeFen)

            assertEquals(3, fixture.server.requestCount)
            assertEquals(listOf(
                UNREACHABLE_LOOPBACK, SERVER_LOOPBACK,
                UNREACHABLE_LOOPBACK, SERVER_LOOPBACK,
                UNREACHABLE_LOOPBACK, SERVER_LOOPBACK,
            ), fixture.connectionAttempts)
            assertEquals(listOf(
                "/berserker-base/user",
                "/berserker-search/search/personal/turnover",
                "/berserker-search/statistics/turnover/count",
            ), listOf(identityRequest.requestUrl!!.encodedPath) +
                List(2) { fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath })
            assertEquals(List(3) { TimeUnit.SECONDS.toNanos(20) }, fixture.callTimeouts)

            assertReadTrace(DiagnosticDetail.CARD_IDENTITY_VERIFY)
            assertReadTrace(DiagnosticDetail.CARD_HISTORY_PAGE)
            assertReadTrace(DiagnosticDetail.CARD_HISTORY_SUMMARY)
            val exported = recorder.events.joinToString()
            assertFalse(exported.contains("synthetic-token-canary"))
            assertFalse(exported.contains("synthetic-order"))
            assertFalse(exported.contains(CardHistoryProtocol.HOST))
            assertFalse(exported.contains("127.0.0."))
            assertFalse(exported.contains("synAccessSource"))
        }
    }

    @Test fun `shorter injected total timeout wins and reports a body read timeout`() = runTest {
        LoopbackTlsFixture(callTimeoutMillis = 250).use { fixture ->
            Diagnostics.install(recorder)
            fixture.server.enqueue(
                MockResponse().setBody(identity).setBodyDelay(2, TimeUnit.SECONDS),
            )
            val client = client(fixture.client)
            val started = System.nanoTime()
            val failure = runCatching { client.login() }.exceptionOrNull()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue(failure is CardHistoryException)
            assertEquals(CardHistoryFailure.NETWORK, (failure as CardHistoryException).reason)
            assertFalse(client.hasSession())
            assertTrue("The injected total budget should finish before the delayed body", elapsed < 1_500)
            assertEquals(1, fixture.server.requestCount)
            assertEquals(listOf(TimeUnit.MILLISECONDS.toNanos(250)), fixture.callTimeouts)
            val terminal = recorder.terminals(DiagnosticDetail.CARD_IDENTITY_VERIFY).single()
            assertEquals(DiagnosticReason.NETWORK, terminal.reason)
            assertEquals(DiagnosticPhase.PERSONAL_VERIFY, terminal.phase)
            assertEquals(DiagnosticTransportStage.RESPONSE_BODY, terminal.transport?.stage)
            assertEquals(DiagnosticTransportError.TIMEOUT, terminal.transport?.error)
            assertTrue(requireNotNull(terminal.transport?.bodyMs) in 1L..1_500L)
            assertEquals(1, terminal.transport?.connectAttemptCount)
            assertTrue(requireNotNull(terminal.transport?.totalMs) in 1L..1_500L)
        }
    }

    @Test fun `one total timeout budget spans a failed route and the recovered response read`() = runTest {
        LoopbackTlsFixture(
            listOf(UNREACHABLE_LOOPBACK, SERVER_LOOPBACK),
            callTimeoutMillis = 350,
            firstConnectionFailureDelayMillis = 200,
        ).use { fixture ->
            Diagnostics.install(recorder)
            fixture.server.enqueue(
                MockResponse().setBody(identity).setBodyDelay(250, TimeUnit.MILLISECONDS),
            )
            val client = client(fixture.client)
            val started = System.nanoTime()
            val failure = runCatching { client.login() }.exceptionOrNull()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue(failure is CardHistoryException)
            assertEquals(CardHistoryFailure.NETWORK, (failure as CardHistoryException).reason)
            assertFalse(client.hasSession())
            assertTrue("The one Call budget should include route recovery and body reading", elapsed < 1_500)
            assertEquals(listOf(UNREACHABLE_LOOPBACK, SERVER_LOOPBACK), fixture.connectionAttempts)
            assertEquals(1, fixture.server.requestCount)
            assertEquals(listOf(TimeUnit.MILLISECONDS.toNanos(350)), fixture.callTimeouts)
            val terminal = recorder.terminals(DiagnosticDetail.CARD_IDENTITY_VERIFY).single()
            assertEquals(DiagnosticTransportStage.RESPONSE_BODY, terminal.transport?.stage)
            assertEquals(DiagnosticTransportError.TIMEOUT, terminal.transport?.error)
            assertEquals(2, terminal.transport?.connectAttemptCount)
            assertEquals(1, terminal.transport?.requestHeadersCount)
            assertTrue(requireNotNull(terminal.transport?.bodyMs) in 1L..1_500L)
        }
    }

    @Test fun `exhausted connection recovery records the final connect failure`() = runTest {
        LoopbackTlsFixture(
            listOf(UNREACHABLE_LOOPBACK, UNREACHABLE_LOOPBACK_2),
            failAllConnections = true,
        ).use { fixture ->
            Diagnostics.install(recorder)
            val client = client(fixture.client)

            val failure = runCatching { client.login() }.exceptionOrNull()

            assertTrue(failure is CardHistoryException)
            assertEquals(CardHistoryFailure.NETWORK, (failure as CardHistoryException).reason)
            assertFalse(client.hasSession())
            assertEquals(listOf(UNREACHABLE_LOOPBACK, UNREACHABLE_LOOPBACK_2), fixture.connectionAttempts)
            assertEquals(0, fixture.server.requestCount)
            val terminal = recorder.terminals(DiagnosticDetail.CARD_IDENTITY_VERIFY).single()
            assertEquals(DiagnosticTransportStage.CONNECT, terminal.transport?.stage)
            assertEquals(DiagnosticTransportError.CONNECT, terminal.transport?.error)
            assertEquals(2, terminal.transport?.connectAttemptCount)
            assertEquals(0, terminal.transport?.requestHeadersCount)
            assertNull(terminal.transport?.headersMs)
            assertNull(terminal.transport?.bodyMs)
        }
    }

    @Test fun `408 and retry after zero 503 retain status and are not followed up`() {
        val cases = listOf(408 to null, 503 to "0")
        for ((status, retryAfter) in cases) {
            LoopbackTlsFixture().use { fixture ->
                val response = MockResponse().setResponseCode(status).setBody("synthetic-response")
                retryAfter?.let { response.setHeader("Retry-After", it) }
                fixture.server.enqueue(response)
                val transport = CardHistoryReadTransport(fixture.client)
                val request = Request.Builder()
                    .url("https://${CardHistoryProtocol.HOST}/berserker-base/user?synAccessSource=h5")
                    .build()
                val prepared = transport.prepare(request)

                prepared.client.newCall(prepared.request).execute().use { received ->
                    assertEquals(status, received.code)
                    assertEquals("1", received.header("Retry-After"))
                }
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun `http and business auth expiry are not replayed`() = runTest {
        data class Case(
            val status: Int,
            val body: String,
            val retryAfter: String? = null,
            val failure: CardHistoryFailure,
            val expiresSession: Boolean,
        )
        val cases = listOf(
            Case(401, "{}", failure = CardHistoryFailure.LOGIN_REQUIRED, expiresSession = true),
            Case(403, "{}", failure = CardHistoryFailure.LOGIN_REQUIRED, expiresSession = true),
            Case(200, """{"code":401,"data":{}}""", failure = CardHistoryFailure.LOGIN_REQUIRED, expiresSession = true),
            Case(408, "{}", failure = CardHistoryFailure.PROTOCOL, expiresSession = false),
            Case(503, "{}", retryAfter = "0", failure = CardHistoryFailure.PROTOCOL, expiresSession = false),
        )
        for (case in cases) {
            val localRecorder = CardDiagnosticRecorder()
            LoopbackTlsFixture(listOf(SERVER_LOOPBACK, UNREACHABLE_LOOPBACK)).use { fixture ->
                Diagnostics.install(localRecorder)
                fixture.server.enqueue(MockResponse().setBody(identity))
                val response = MockResponse().setResponseCode(case.status).setBody(case.body)
                case.retryAfter?.let { response.setHeader("Retry-After", it) }
                fixture.server.enqueue(response)
                var authenticatorCalls = 0
                val injectedClient = fixture.client.newBuilder().authenticator(Authenticator { _, prior ->
                    authenticatorCalls++
                    prior.request.newBuilder().url("https://outside.example.test/replayed").build()
                }).build()
                val client = client(injectedClient)
                client.login()
                fixture.client.connectionPool.evictAll()

                val failure = runCatching { client.page() }.exceptionOrNull()
                assertTrue(failure is CardHistoryException)
                assertEquals(case.failure, (failure as CardHistoryException).reason)
                assertEquals(!case.expiresSession, client.hasSession())
                assertEquals(0, authenticatorCalls)
                assertEquals("The rejected GET must be sent once", 2, fixture.server.requestCount)
                assertEquals("/berserker-base/user", fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
                assertEquals("/berserker-search/search/personal/turnover", fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
                val terminal = localRecorder.terminals(DiagnosticDetail.CARD_HISTORY_PAGE).single()
                assertEquals(1, terminal.transport?.requestHeadersCount)
                assertEquals(1, terminal.transport?.connectAttemptCount)
                assertNull(terminal.transport?.error)
            }
        }
    }

    @Test fun `caller interceptor cannot change the actual request target`() = runTest {
        LoopbackTlsFixture().use { fixture ->
            Diagnostics.install(recorder)
            val rewritten = fixture.client.newBuilder().addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .url("https://outside.example.test/leak")
                    .build()
                chain.proceed(request)
            }.build()
            val client = client(rewritten)

            val failure = runCatching { client.login() }.exceptionOrNull()
            assertTrue(failure is CardHistoryException)
            assertEquals(CardHistoryFailure.PROTOCOL, (failure as CardHistoryException).reason)
            assertFalse(client.hasSession())
            assertTrue(fixture.dnsLookups.isEmpty())
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test fun `cancelling personal verification cancels the call before session commit`() = runTest {
        LoopbackTlsFixture(callTimeoutMillis = 20_000).use { fixture ->
            Diagnostics.install(recorder)
            fixture.server.enqueue(
                MockResponse().setBody(identity).throttleBody(1, 1, TimeUnit.SECONDS),
            )
            val client = client(fixture.client)
            val login = async(Dispatchers.IO) { client.login() }

            try {
                assertNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
                assertTrue(fixture.responseBodyStarted.await(2, TimeUnit.SECONDS))
                assertFalse("The streamed identity response must still be incomplete", login.isCompleted)
                assertFalse(client.hasSession())
            } finally {
                login.cancelAndJoin()
            }

            assertTrue(fixture.cancelledCalls.await(2, TimeUnit.SECONDS))
            assertTrue("Cancellation must release the blocked network body read",
                fixture.responseBodyFinished.await(2, TimeUnit.SECONDS))
            assertTrue(login.isCancelled)
            assertFalse(client.hasSession())
            assertEquals(1, fixture.server.requestCount)
            val terminal = recorder.terminals(DiagnosticDetail.CARD_IDENTITY_VERIFY).single()
            assertEquals(DiagnosticResult.CANCELLED, terminal.result)
            assertEquals(DiagnosticTransportError.CANCELLED, terminal.transport?.error)
            assertEquals(DiagnosticTransportStage.RESPONSE_BODY, terminal.transport?.stage)
            assertEquals(1, terminal.transport?.requestHeadersCount)
        }
    }

    @Test fun `redirect is not followed even when the injected client enables redirects`() = runTest {
        LoopbackTlsFixture(listOf(SERVER_LOOPBACK, UNREACHABLE_LOOPBACK)).use { fixture ->
            Diagnostics.install(recorder)
            fixture.server.enqueue(MockResponse().setBody(identity))
            fixture.server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", "https://outside.example.test/"),
            )
            val client = client(fixture.client)
            client.login()
            fixture.client.connectionPool.evictAll()

            val failure = runCatching { client.page() }.exceptionOrNull()
            assertTrue(failure is CardHistoryException)
            assertEquals(CardHistoryFailure.PROTOCOL, (failure as CardHistoryException).reason)
            assertEquals(2, fixture.server.requestCount)
            assertEquals(listOf(CardHistoryProtocol.HOST, CardHistoryProtocol.HOST), fixture.dnsLookups)
            val terminal = recorder.terminals(DiagnosticDetail.CARD_HISTORY_PAGE).single()
            assertEquals(DiagnosticTransportStage.RESPONSE_HEADERS, terminal.transport?.stage)
            assertNull(terminal.transport?.bodyMs)
            assertEquals(1, terminal.transport?.requestHeadersCount)
        }
    }

    @Test fun `certificate rejection never falls back to another address or plaintext`() = runTest {
        LoopbackTlsFixture(listOf(SERVER_LOOPBACK, UNREACHABLE_LOOPBACK), trustFixture = false).use { fixture ->
            Diagnostics.install(recorder)
            fixture.server.enqueue(MockResponse().setBody(identity))
            val client = client(fixture.client)

            val failure = runCatching { client.login() }.exceptionOrNull()
            assertTrue(failure is CardHistoryException)
            assertEquals(CardHistoryFailure.NETWORK, (failure as CardHistoryException).reason)
            assertFalse(client.hasSession())
            assertEquals(listOf(SERVER_LOOPBACK), fixture.connectionAttempts)
            assertEquals(0, fixture.server.requestCount)
            val terminal = recorder.terminals(DiagnosticDetail.CARD_IDENTITY_VERIFY).single()
            assertEquals(DiagnosticTransportStage.TLS, terminal.transport?.stage)
            assertEquals(DiagnosticTransportError.TLS_HANDSHAKE, terminal.transport?.error)
            assertEquals(1, terminal.transport?.tlsAttemptCount)
            assertEquals(0, terminal.transport?.requestHeadersCount)
            assertNull(terminal.transport?.headersMs)
            assertNull(terminal.transport?.bodyMs)
        }
    }

    private fun client(http: OkHttpClient) = CardHistoryClient(
        Account(), CardHistoryAuthenticator { "synthetic-token-canary" }, http,
    )

    private fun assertReadTrace(detail: DiagnosticDetail) {
        val terminal = recorder.terminals(detail).single()
        assertEquals(DiagnosticResult.OK, terminal.result)
        assertNotNull(terminal.transport)
        assertEquals(DiagnosticTransportStage.RESPONSE_BODY, terminal.transport?.stage)
        assertNull(terminal.transport?.error)
        assertEquals(1, terminal.transport?.dnsQueryCount)
        assertEquals(2, terminal.transport?.dnsIpv4Count)
        assertEquals(0, terminal.transport?.dnsIpv6Count)
        assertEquals(2, terminal.transport?.connectAttemptCount)
        assertEquals(1, terminal.transport?.tlsAttemptCount)
        assertEquals(1, terminal.transport?.requestHeadersCount)
        assertTrue(requireNotNull(terminal.transport?.totalMs) in 0L..600_000L)
    }

    /** DNS and sockets only route to local loopback addresses; TLS keeps the production host name. */
    private class LoopbackTlsFixture(
        private val addresses: List<InetAddress> = listOf(SERVER_LOOPBACK),
        trustFixture: Boolean = true,
        callTimeoutMillis: Long = 0,
        private val failAllConnections: Boolean = false,
        private val firstConnectionFailureDelayMillis: Long = 0,
    ) : Closeable {
        val server = MockWebServer()
        val connectionAttempts = Collections.synchronizedList(mutableListOf<InetAddress>())
        val dnsLookups = Collections.synchronizedList(mutableListOf<String>())
        val callTimeouts = Collections.synchronizedList(mutableListOf<Long>())
        val responseBodyStarted = CountDownLatch(1)
        val responseBodyFinished = CountDownLatch(1)
        val cancelledCalls = CountDownLatch(1)
        val client: OkHttpClient

        init {
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(CERTIFICATE).build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.start(SERVER_LOOPBACK, 0)
            val builder = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .protocols(listOf(Protocol.HTTP_1_1))
                .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        dnsLookups += hostname
                        if (hostname != CardHistoryProtocol.HOST) throw UnknownHostException("Synthetic host blocked")
                        return addresses
                    }
                })
                .socketFactory(object : SocketFactory() {
                    override fun createSocket(): Socket = object : Socket() {
                        override fun connect(endpoint: SocketAddress?, timeout: Int) {
                            check(endpoint is InetSocketAddress && endpoint.port == 443 && endpoint.address in addresses)
                            check(endpoint.address.isLoopbackAddress)
                            connectionAttempts += endpoint.address
                            if (failAllConnections ||
                                (connectionAttempts.size == 1 && firstConnectionFailureDelayMillis > 0)
                            ) {
                                if (!failAllConnections) Thread.sleep(firstConnectionFailureDelayMillis)
                                throw ConnectException("Synthetic local connection failure")
                            }
                            super.connect(InetSocketAddress(endpoint.address, server.port), timeout)
                        }
                    }

                    override fun createSocket(host: String, port: Int): Socket = unsupported()
                    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
                    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
                    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
                    private fun unsupported(): Nothing = error("Only explicit loopback routes are allowed")
                })
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) { callTimeouts += call.timeout().timeoutNanos() }
                    override fun responseBodyStart(call: Call) { responseBodyStarted.countDown() }
                    override fun responseBodyEnd(call: Call, byteCount: Long) { responseBodyFinished.countDown() }
                    override fun responseFailed(call: Call, ioe: IOException) { responseBodyFinished.countDown() }
                    override fun canceled(call: Call) { cancelledCalls.countDown() }
                })
            if (trustFixture) {
                val certificates = HandshakeCertificates.Builder()
                    .addTrustedCertificate(CERTIFICATE.certificate)
                    .build()
                builder.sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
            }
            client = builder.build()
        }

        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    private companion object {
        val SERVER_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val UNREACHABLE_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
        val UNREACHABLE_LOOPBACK_2: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 3))
        val CERTIFICATE: HeldCertificate by lazy {
            HeldCertificate.Builder()
                .commonName("UPC Pocket local test fixture")
                .addSubjectAlternativeName(CardHistoryProtocol.HOST)
                .build()
        }
    }
}
