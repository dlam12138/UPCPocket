package com.upcpocket.app.upcauth

import com.upcpocket.app.platform.diagnostics.DiagnosticTransportError
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportProtocol
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportStage
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import okio.Buffer
import org.junit.Test

class UpcAuthTransportTest {
    @Test
    fun requestStringFormDoesNotExposeUrlHeadersOrBody() {
        val request = UpcAuthHttpRequest(
            method = "POST",
            url = "https://service.upc.edu.cn/callback?ticket=ST-synthetic-ticket",
            headers = mapOf("Cookie" to "session=synthetic-cookie"),
            body = "password=synthetic-password",
        )

        val safe = request.toString()

        assertFalse(safe.contains("ticket="))
        assertFalse(safe.contains("session="))
        assertFalse(safe.contains("password="))
    }

    @Test
    fun exactPublicEntryRecoversTheSecondAddressOnlyWhenOptedIn() {
        for (recover in listOf(false, true)) {
            LoopbackTlsFixture(listOf(UNREACHABLE_LOOPBACK, SERVER_LOOPBACK)).use { fixture ->
                fixture.server.enqueue(MockResponse().setBody("synthetic-public-entry"))
                val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = recover)
                if (recover) {
                    val result = transport.execute(observedRequest())
                    assertEquals(200, result.status)
                    assertEquals("synthetic-public-entry", result.body.toString(Charsets.UTF_8))
                    assertEquals(2, fixture.connectionAttempts.size)
                    assertEquals(1, fixture.server.requestCount)
                    assertEquals(listOf(TimeUnit.SECONDS.toNanos(20)), fixture.callTimeouts)
                    val snapshot = requireNotNull(result.transport)
                    assertEquals(DiagnosticTransportStage.RESPONSE_BODY, snapshot.stage)
                    assertNull(snapshot.error)
                    assertEquals(1, snapshot.dnsQueryCount)
                    assertEquals(2, snapshot.dnsIpv4Count)
                    assertEquals(0, snapshot.dnsIpv6Count)
                    assertEquals(2, snapshot.connectAttemptCount)
                    assertEquals(1, snapshot.tlsAttemptCount)
                    assertEquals(1, snapshot.requestHeadersCount)
                    assertEquals(0, snapshot.connectionReused)
                    assertEquals(DiagnosticTransportProtocol.HTTP_1_1, snapshot.protocol)
                } else {
                    val failure = assertThrows(UpcAuthTransportException::class.java) {
                        transport.execute(observedRequest())
                    }
                    assertEquals(UpcAuthTransportFailure.NETWORK, failure.failure)
                    assertEquals(1, failure.transport?.connectAttemptCount)
                    assertEquals(DiagnosticTransportStage.CONNECT, failure.transport?.stage)
                    assertNull(failure.transport?.connectionReused)
                    assertEquals(1, fixture.connectionAttempts.size)
                    assertEquals(0, fixture.server.requestCount)
                }
            }
        }
    }

    @Test
    fun callbacksOtherGetsAndPasswordPostsNeverRecoverAnotherAddress() {
        val requests = listOf(
            UpcAuthHttpRequest("GET", CardAuthOrigins.ENTRY + "&extra=synthetic"),
            UpcAuthHttpRequest("GET", CardAuthOrigins.ENTRY + "#synthetic"),
            UpcAuthHttpRequest("GET", "https://cas.upc.edu.cn/cas/login?service=synthetic"),
            UpcAuthHttpRequest("GET", "${CardAuthOrigins.BASE}/berserker-auth/cas/login/wislearn?ticket=ST-synthetic"),
            UpcAuthHttpRequest("GET", "${CardAuthOrigins.BASE}/plat/?code=synthetic"),
            UpcAuthHttpRequest("GET", "${CardAuthOrigins.BASE}/plat/?ticket=synthetic"),
            UpcAuthHttpRequest("POST", CardAuthOrigins.ENTRY, body = "password=synthetic-password"),
        )
        for (request in requests) {
            LoopbackTlsFixture(listOf(UNREACHABLE_LOOPBACK, SERVER_LOOPBACK)).use { fixture ->
                val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = true)
                assertFailure(UpcAuthTransportFailure.NETWORK) { transport.execute(request) }
                assertEquals(1, fixture.connectionAttempts.size)
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test
    fun passwordPostWithUnknownResponseIsSentOnlyOnce() {
        LoopbackTlsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            fixture.server.enqueue(MockResponse().setBody("must-not-be-read"))
            val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = true)
            assertFailure(UpcAuthTransportFailure.NETWORK) {
                transport.execute(UpcAuthHttpRequest(
                    "POST", "https://cas.upc.edu.cn/cas/login?service=synthetic",
                    body = "password=synthetic-password",
                ))
            }
            assertEquals(1, fixture.server.requestCount)
            assertEquals(1, fixture.connectionAttempts.size)
            assertEquals("POST", fixture.server.takeRequest(1, TimeUnit.SECONDS)?.method)
        }
    }

    @Test
    fun oneTimeTicketGetWithUnknownResponseIsNotReplayed() {
        LoopbackTlsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            fixture.server.enqueue(MockResponse().setBody("must-not-be-read"))
            val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = true)
            assertFailure(UpcAuthTransportFailure.NETWORK) {
                transport.execute(UpcAuthHttpRequest(
                    "GET", "${CardAuthOrigins.BASE}/berserker-auth/cas/login/wislearn?ticket=ST-synthetic",
                ))
            }
            assertEquals(1, fixture.server.requestCount)
            assertEquals(1, fixture.connectionAttempts.size)
            assertEquals("GET", fixture.server.takeRequest(1, TimeUnit.SECONDS)?.method)
        }
    }

    @Test
    fun entryRecoveryPreservesAShorterTotalCallBudget() {
        LoopbackTlsFixture(callTimeoutMillis = 250).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("delayed").setBodyDelay(1, TimeUnit.SECONDS))
            val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = true)
            val started = System.nanoTime()
            val failure = assertThrows(UpcAuthTransportException::class.java) {
                transport.execute(observedRequest())
            }
            assertEquals(UpcAuthTransportFailure.TIMEOUT, failure.failure)
            assertEquals(DiagnosticTransportError.TIMEOUT, failure.transport?.error)
            assertEquals(DiagnosticTransportStage.RESPONSE_BODY, failure.transport?.stage)
            assertEquals(200, failure.httpStatus)
            assertEquals(0, failure.responseBytes)
            assertNotNull(failure.transport?.bodyMs)
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("The bounded call must finish before the socket read timeout", elapsedMillis < 2_000)
            assertEquals(listOf(TimeUnit.MILLISECONDS.toNanos(250)), fixture.callTimeouts)
            assertEquals(1, fixture.connectionAttempts.size)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun fixedAndChunkedResponsesStillRespectTheOneMiBLimit() {
        val oversized = "x".repeat(1024 * 1024 + 1)
        for (chunked in listOf(false, true)) {
            LoopbackTlsFixture().use { fixture ->
                fixture.server.enqueue(if (chunked) MockResponse().setChunkedBody(oversized, 8 * 1024)
                    else MockResponse().setBody(oversized))
                val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = true)
                val failure = assertThrows(UpcAuthTransportException::class.java) {
                    transport.execute(observedRequest())
                }
                assertEquals(UpcAuthTransportFailure.TOO_LARGE, failure.failure)
                assertEquals(DiagnosticTransportError.TOO_LARGE, failure.transport?.error)
                assertEquals(200, failure.httpStatus)
                if (chunked) {
                    assertTrue(requireNotNull(failure.responseBytes) > 1024 * 1024)
                    assertTrue(requireNotNull(failure.responseBytes) <= 1024 * 1024 + 8192)
                    assertEquals(DiagnosticTransportStage.RESPONSE_BODY, failure.transport?.stage)
                } else {
                    assertEquals(0, failure.responseBytes)
                    assertEquals(DiagnosticTransportStage.RESPONSE_HEADERS, failure.transport?.stage)
                }
                assertEquals(1, fixture.server.requestCount)
                assertEquals(1, fixture.connectionAttempts.size)
            }
        }
    }

    @Test
    fun untrustedTlsRemainsRejectedWithoutTryingAnotherAddress() {
        LoopbackTlsFixture(listOf(SERVER_LOOPBACK, UNREACHABLE_LOOPBACK), trustFixture = false).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("must-not-be-read"))
            val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = true)
            val failure = assertThrows(UpcAuthTransportException::class.java) {
                transport.execute(observedRequest())
            }
            assertEquals(UpcAuthTransportFailure.TLS, failure.failure)
            assertEquals(DiagnosticTransportStage.TLS, failure.transport?.stage)
            assertEquals(DiagnosticTransportError.TLS_HANDSHAKE, failure.transport?.error)
            assertEquals(1, failure.transport?.tlsAttemptCount)
            assertEquals(0, failure.transport?.requestHeadersCount)
            assertNull(failure.transport?.connectionReused)
            assertNull(failure.httpStatus)
            assertNull(failure.responseBytes)
            assertEquals(1, fixture.connectionAttempts.size)
            assertEquals(0, fixture.server.requestCount)
        }
    }

    @Test
    fun recoveredPublicEntryStillDoesNotAutomaticallyFollowRedirects() {
        LoopbackTlsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://outside.example.test/"))
            val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = true)
            val response = transport.execute(UpcAuthHttpRequest("GET", CardAuthOrigins.ENTRY))
            assertEquals(302, response.status)
            assertEquals(1, fixture.server.requestCount)
            assertEquals(1, fixture.connectionAttempts.size)
        }
    }

    @Test fun reusedTlsConnectionDoesNotInventDnsConnectOrTlsDurations() {
        LoopbackTlsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("first"))
            fixture.server.enqueue(MockResponse().setBody("second"))
            val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = true)
            val first = requireNotNull(transport.execute(observedRequest()).transport)
            val second = requireNotNull(transport.execute(observedRequest()).transport)
            assertEquals(0, first.connectionReused)
            assertEquals(1, second.connectionReused)
            assertEquals(0, second.dnsQueryCount)
            assertEquals(0, second.connectAttemptCount)
            assertEquals(0, second.tlsAttemptCount)
            assertNull(second.dnsMs)
            assertNull(second.dnsIpv4Count)
            assertNull(second.dnsIpv6Count)
            assertNull(second.connectMs)
            assertNull(second.tlsMs)
            assertNull(second.proxyType)
            assertEquals(1, second.requestHeadersCount)
            assertEquals(DiagnosticTransportStage.RESPONSE_BODY, second.stage)
            assertEquals(2, fixture.server.requestCount)
            assertEquals(1, fixture.connectionAttempts.size)
        }
    }

    @Test fun dnsFailureHasNoCompletedAddressesOrConnection() {
        LoopbackTlsFixture(dnsFailure = true).use { fixture ->
            val transport = OkHttpUpcAuthTransport(fixture.client, recoverCardEntry = true)
            val failure = assertThrows(UpcAuthTransportException::class.java) {
                transport.execute(observedRequest())
            }
            val snapshot = requireNotNull(failure.transport)
            assertEquals(DiagnosticTransportStage.DNS, snapshot.stage)
            assertEquals(DiagnosticTransportError.DNS, snapshot.error)
            assertEquals(1, snapshot.dnsQueryCount)
            assertNull(snapshot.dnsIpv4Count)
            assertNull(snapshot.dnsIpv6Count)
            assertNotNull(snapshot.dnsMs)
            assertNull(snapshot.connectMs)
            assertEquals(0, snapshot.connectAttemptCount)
            assertEquals(0, fixture.server.requestCount)
            assertTrue(fixture.connectionAttempts.isEmpty())
            assertNull(failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun missingResponseHeadersRemainTheResponseHeadersStage() {
        LoopbackTlsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val transport = OkHttpUpcAuthTransport(fixture.client)
            val failure = assertThrows(UpcAuthTransportException::class.java) {
                transport.execute(observedRequest())
            }
            val snapshot = requireNotNull(failure.transport)
            assertEquals(DiagnosticTransportStage.RESPONSE_HEADERS, snapshot.stage)
            assertEquals(1, snapshot.requestHeadersCount)
            assertNotNull(snapshot.headersMs)
            assertNull(snapshot.bodyMs)
            assertNull(failure.httpStatus)
            assertNull(failure.responseBytes)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test fun partialBodyPreservesActualReadCountAndReceivedStatus() {
        LoopbackTlsFixture().use { fixture ->
            val size = 64 * 1024
            fixture.server.enqueue(MockResponse().setBody("b".repeat(size))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val transport = OkHttpUpcAuthTransport(fixture.client)
            val failure = assertThrows(UpcAuthTransportException::class.java) {
                transport.execute(observedRequest())
            }
            val snapshot = requireNotNull(failure.transport)
            assertEquals(DiagnosticTransportStage.RESPONSE_BODY, snapshot.stage)
            assertNotNull(snapshot.bodyMs)
            assertEquals(200, failure.httpStatus)
            assertTrue(requireNotNull(failure.responseBytes) in 1 until size)
            assertTrue(snapshot.error in setOf(DiagnosticTransportError.PROTOCOL,
                DiagnosticTransportError.EOF, DiagnosticTransportError.IO_OTHER))
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test fun gzipLimitCountsDeliveredDecompressedBytesInsteadOfWireLength() {
        LoopbackTlsFixture().use { fixture ->
            val payload = "g".repeat(32 * 1024).toByteArray()
            val compressed = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(payload) }
            }.toByteArray()
            assertTrue(compressed.size < 1024)
            fixture.server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip")
                .setBody(Buffer().write(compressed)))
            val transport = OkHttpUpcAuthTransport(fixture.client, maxBytes = 1024)
            val failure = assertThrows(UpcAuthTransportException::class.java) {
                transport.execute(observedRequest())
            }
            assertEquals(DiagnosticTransportError.TOO_LARGE, failure.transport?.error)
            assertEquals(DiagnosticTransportStage.RESPONSE_BODY, failure.transport?.stage)
            assertTrue(requireNotNull(failure.responseBytes) > 1024)
            assertTrue(requireNotNull(failure.responseBytes) > compressed.size)
            assertEquals(200, failure.httpStatus)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test fun listenerFactoryIsCreatedOnceAndObserverFailuresCannotFailTheRequest() {
        for (failFactory in listOf(false, true)) {
            LoopbackTlsFixture().use { fixture ->
                fixture.server.enqueue(MockResponse().setBody("observer-safe"))
                val factoryCalls = AtomicInteger()
                val callbackCalls = AtomicInteger()
                val client = fixture.client.newBuilder().eventListenerFactory {
                    factoryCalls.incrementAndGet()
                    if (failFactory) error("synthetic observer secret canary")
                    object : EventListener() {
                        override fun callStart(call: Call) {
                            callbackCalls.incrementAndGet()
                            error("synthetic observer secret canary")
                        }
                    }
                }.build()
                val response = OkHttpUpcAuthTransport(client).execute(observedRequest())
                assertEquals("observer-safe", response.body.toString(Charsets.UTF_8))
                assertEquals(1, factoryCalls.get())
                assertEquals(if (failFactory) 0 else 1, callbackCalls.get())
                assertNotNull(response.transport)
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun unrelatedRequestsHaveNoSnapshotByDefault() {
        LoopbackTlsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("plain"))
            val response = OkHttpUpcAuthTransport(fixture.client).execute(
                UpcAuthHttpRequest("GET", "https://cas.upc.edu.cn/cas/login?service=synthetic"))
            assertNull(response.transport)
            assertEquals(1, fixture.callTimeouts.size)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test fun parallelRealCallsHaveIndependentAggregatesAndPreserveEachOriginalFactory() {
        LoopbackTlsFixture().use { fixture ->
            repeat(2) { fixture.server.enqueue(MockResponse().setBody("parallel").setBodyDelay(200, TimeUnit.MILLISECONDS)) }
            val started = CountDownLatch(2)
            val factories = AtomicInteger()
            val client = fixture.client.newBuilder().eventListenerFactory {
                factories.incrementAndGet()
                object : EventListener() {
                    override fun callStart(call: Call) {
                        started.countDown()
                        check(started.await(2, TimeUnit.SECONDS))
                    }
                }
            }.build()
            val transport = OkHttpUpcAuthTransport(client)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val calls = (0..1).map { executor.submit<UpcAuthHttpResponse> { transport.execute(observedRequest()) } }
                val responses = calls.map { it.get(5, TimeUnit.SECONDS) }
                responses.forEach {
                    val snapshot = requireNotNull(it.transport)
                    assertEquals(DiagnosticTransportStage.RESPONSE_BODY, snapshot.stage)
                    assertEquals(1, snapshot.requestHeadersCount)
                    assertTrue(snapshot.connectAttemptCount in 0..1)
                    assertTrue(snapshot.dnsQueryCount in 0..1)
                    assertNull(snapshot.error)
                }
                assertEquals(2, factories.get())
                assertEquals(2, fixture.server.requestCount)
            } finally { executor.shutdownNow() }
        }
    }

    private fun observedRequest() = UpcAuthHttpRequest("GET", CardAuthOrigins.ENTRY,
        collectTransportDiagnostics = true)

    private fun assertFailure(failure: UpcAuthTransportFailure, block: () -> Unit) {
        assertEquals(failure, assertThrows(UpcAuthTransportException::class.java) { block() }.failure)
    }

    /** DNS and socket routing are test-only: every permitted socket stays on loopback. */
    private class LoopbackTlsFixture(
        addresses: List<InetAddress> = listOf(SERVER_LOOPBACK),
        trustFixture: Boolean = true,
        callTimeoutMillis: Long = 0,
        dnsFailure: Boolean = false,
    ) : Closeable {
        val server = MockWebServer()
        val connectionAttempts = Collections.synchronizedList(mutableListOf<InetAddress>())
        val callTimeouts = Collections.synchronizedList(mutableListOf<Long>())
        val client: OkHttpClient

        init {
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(CERTIFICATE).build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.start(SERVER_LOOPBACK, 0)
            val builder = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .protocols(listOf(Protocol.HTTP_1_1))
                .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (dnsFailure) throw UnknownHostException("synthetic DNS secret canary 192.0.2.8")
                        if (hostname !in setOf(CardAuthOrigins.HOST, "cas.upc.edu.cn")) {
                            throw UnknownHostException("Only synthetic fixture hosts are permitted")
                        }
                        return addresses
                    }
                })
                .socketFactory(object : SocketFactory() {
                    override fun createSocket(): Socket = object : Socket() {
                        override fun connect(endpoint: SocketAddress?, timeout: Int) {
                            check(endpoint is InetSocketAddress && endpoint.port == 443 && endpoint.address in addresses)
                            check(endpoint.address.isLoopbackAddress)
                            connectionAttempts += endpoint.address
                            super.connect(InetSocketAddress(endpoint.address, server.port), timeout)
                        }
                    }
                    override fun createSocket(host: String, port: Int): Socket = unsupported()
                    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
                    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
                    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
                    private fun unsupported(): Nothing = error("Only explicitly resolved loopback sockets are permitted")
                })
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) { callTimeouts += call.timeout().timeoutNanos() }
                })
            if (trustFixture) {
                val certificates = HandshakeCertificates.Builder().addTrustedCertificate(CERTIFICATE.certificate).build()
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
        val CERTIFICATE: HeldCertificate by lazy {
            HeldCertificate.Builder()
                .commonName("UPC Pocket local test fixture")
                .addSubjectAlternativeName(CardAuthOrigins.HOST)
                .addSubjectAlternativeName("cas.upc.edu.cn")
                .build()
        }
    }
}
