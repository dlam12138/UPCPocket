package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.data.cardhistory.CardHistoryReadTransport
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportError
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportStage
import java.io.Closeable
import java.io.IOException
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
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardRechargeReadTransportTest {
    @Test
    fun `preparation policy accepts only the five fixed read paths and exact query shapes`() {
        val allowed = listOf(
            campus("/berserker-base/user?synAccessSource=h5"),
            campus("/berserker-app/frontInfo?synAccessSource=h5"),
            campus("/berserker-app/ykt/tsm/queryCard?scene=recharge"),
            campus("/charge/feeitem/singleFeeitem?feeitemid=1234567890"),
            Request.Builder().url(CardRechargeProtocol.SIGNER_SOURCE).build(),
        )
        allowed.forEach { assertTrue(CardRechargePreparationReadPolicy.allows(it)) }

        val denied = listOf(
            campus("/berserker-base/user?synAccessSource=h5&extra=1"),
            campus("/berserker-base/user?synAccessSource=h5&synAccessSource=h5"),
            campus("/berserker-app/frontInfo?synAccessSource=h5&extra=1"),
            campus("/berserker-app/ykt/tsm/queryCard?scene=recharge&scene=recharge"),
            campus("/charge/feeitem/singleFeeitem?feeitemid=1&feeitemid=2"),
            campus("/charge/feeitem/singleFeeitem?feeitemid=12345678901"),
            campus("/charge/feeitem/singleFeeitem?feeitemid=not-numeric"),
            campus("/charge/pay/getpayinfo?orderid=123"),
            campus("/charge/order/personal_data?orderid=123"),
            campus("/payment/index?orderid=123"),
            campus("/berserker-auth/cas/login?ticket=fixture"),
            Request.Builder().url("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=fixture").build(),
            campus("/charge/order/thirdOrder").newBuilder().post("x".toRequestBody()).build(),
            Request.Builder().url("https://outside.example.test/berserker-base/user?synAccessSource=h5")
                .header("synjones-auth", "bearer fixture-token").header("synAccessSource", "h5").build(),
            Request.Builder().url("https://user@mb-xyk.upc.edu.cn/berserker-base/user?synAccessSource=h5")
                .header("synjones-auth", "bearer fixture-token").header("synAccessSource", "h5").build(),
            Request.Builder().url("https://mb-xyk.upc.edu.cn:444/berserker-base/user?synAccessSource=h5")
                .header("synjones-auth", "bearer fixture-token").header("synAccessSource", "h5").build(),
            Request.Builder().url("https://mb-xyk.upc.edu.cn/berserker-base/user?synAccessSource=h5#part")
                .header("synjones-auth", "bearer fixture-token").header("synAccessSource", "h5").build(),
            campus("/berserker-base/user?synAccessSource=h5").newBuilder()
                .removeHeader("synjones-auth").build(),
            campus("/berserker-base/user?synAccessSource=h5").newBuilder()
                .addHeader("synjones-auth", "bearer second-token").build(),
            campus("/berserker-base/user?synAccessSource=h5").newBuilder()
                .header("Cookie", "session=fixture").build(),
            campus("/berserker-base/user?synAccessSource=h5").newBuilder()
                .header("Host", "outside.example.test").build(),
            Request.Builder().url(CardRechargeProtocol.SIGNER_SOURCE + "?cache=1").build(),
            Request.Builder().url(CardRechargeProtocol.SIGNER_SOURCE)
                .header("synjones-auth", "bearer fixture-token").header("synAccessSource", "h5").build(),
        )
        denied.forEach { assertFalse(CardRechargePreparationReadPolicy.allows(it)) }
    }

    @Test
    fun `production recharge transport sends the session only to fixed campus reads`() = runBlocking {
        LoopbackTlsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("{\"code\":200}"))
            fixture.server.enqueue(MockResponse().setBody("synthetic-signer"))
            val transport = RechargeHttpTransport(fixture.client)

            val identity = transport.preparationGet(
                "${CardRechargeProtocol.BASE}/berserker-base/user?synAccessSource=h5",
                "fixture-token",
            ) {}
            val identityRequest = fixture.server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(identityRequest)
            assertEquals(200, identity.status)
            assertEquals("bearer fixture-token", identityRequest!!.getHeader("synjones-auth"))
            assertEquals("h5", identityRequest.getHeader("synAccessSource"))

            val signer = transport.preparationGet(CardRechargeProtocol.SIGNER_SOURCE, null) {}
            val signerRequest = fixture.server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(signerRequest)
            assertEquals(200, signer.status)
            assertNull(signerRequest!!.getHeader("synjones-auth"))
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test
    fun `recovery stays one call uses the shorter injected budget and retains TLS client policy`() = runBlocking {
        LoopbackTlsFixture(listOf(UNREACHABLE_LOOPBACK, SERVER_LOOPBACK)).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("{\"code\":200}"))
            val request = campus("/berserker-base/user?synAccessSource=h5")
            val prepared = CardHistoryReadTransport(
                fixture.client,
                { CardRechargePreparationReadPolicy.allows(it) },
                connectTimeoutCapMs = TimeUnit.SECONDS.toMillis(10),
            ).prepare(request)
            assertTrue(prepared.client.retryOnConnectionFailure)
            assertFalse(prepared.client.followRedirects)
            assertFalse(prepared.client.followSslRedirects)
            assertSameAuthenticatorNone(prepared.client)
            assertEquals(TimeUnit.SECONDS.toMillis(20), prepared.client.callTimeoutMillis.toLong())
            val shortBase = fixture.client.newBuilder()
                .connectTimeout(400, TimeUnit.MILLISECONDS)
                .callTimeout(250, TimeUnit.MILLISECONDS)
                .build()
            val shortPrepared = CardHistoryReadTransport(
                shortBase,
                { CardRechargePreparationReadPolicy.allows(it) },
                connectTimeoutCapMs = TimeUnit.SECONDS.toMillis(10),
            ).prepare(request)
            assertEquals(400, shortPrepared.client.connectTimeoutMillis)
            assertEquals(250, shortPrepared.client.callTimeoutMillis)
            val unboundedConnectBase = fixture.client.newBuilder()
                .connectTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val boundedConnect = CardHistoryReadTransport(
                unboundedConnectBase,
                { CardRechargePreparationReadPolicy.allows(it) },
                connectTimeoutCapMs = TimeUnit.SECONDS.toMillis(10),
            ).prepare(request)
            assertEquals(TimeUnit.SECONDS.toMillis(10).toInt(), boundedConnect.client.connectTimeoutMillis)

            val response = CardRechargeReadTransport(fixture.client).get(request)

            assertEquals(200, response.status)
            assertEquals("{\"code\":200}", response.body)
            assertEquals(1, fixture.server.requestCount)
            assertEquals(listOf(UNREACHABLE_LOOPBACK, SERVER_LOOPBACK), fixture.connectionAttempts)
            assertEquals(listOf(TimeUnit.SECONDS.toNanos(20)), fixture.callTimeouts)
            assertEquals(2, response.transportSnapshot?.connectAttemptCount)
            assertEquals(1, response.transportSnapshot?.requestHeadersCount)
            assertEquals(DiagnosticTransportStage.RESPONSE_BODY, response.transportSnapshot?.stage)
        }

        LoopbackTlsFixture(callTimeoutMillis = 250).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("{\"code\":200}").setBodyDelay(2, TimeUnit.SECONDS))
            val failure = runCatching {
                CardRechargeReadTransport(fixture.client).get(campus("/berserker-base/user?synAccessSource=h5"))
            }.exceptionOrNull()

            val readFailure = failure as? CardRechargeReadFailure ?: error("Expected bounded read failure")
            assertEquals("P-NETWORK", readFailure.stage)
            assertEquals(1, fixture.server.requestCount)
            assertEquals(listOf(TimeUnit.MILLISECONDS.toNanos(250)), fixture.callTimeouts)
            assertEquals(DiagnosticTransportError.TIMEOUT, readFailure.transport?.error)
            assertTrue(requireNotNull(readFailure.transport?.totalMs) < 1_500)
        }
    }

    @Test
    fun `cancelling while reading the response body cancels the same call and reports its trace`() = runBlocking {
        LoopbackTlsFixture().use { fixture ->
            fixture.server.enqueue(
                MockResponse().setBody("{\"code\":200}").throttleBody(1, 1, TimeUnit.SECONDS),
            )
            val transport = CardRechargeReadTransport(fixture.client)
            val observed = AtomicReference<com.upcpocket.app.platform.diagnostics.DiagnosticTransportSnapshot?>()
            val read = async(Dispatchers.IO) {
                transport.get(campus("/berserker-base/user?synAccessSource=h5")) { observed.set(it) }
            }

            assertNotNull(fixture.server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(fixture.responseBodyStarted.await(2, TimeUnit.SECONDS))
            read.cancelAndJoin()

            assertTrue(fixture.cancelledCalls.await(2, TimeUnit.SECONDS))
            assertTrue(fixture.responseBodyFinished.await(2, TimeUnit.SECONDS))
            assertEquals(1, fixture.server.requestCount)
            assertEquals(DiagnosticTransportError.CANCELLED, observed.get()?.error)
        }
    }

    @Test
    fun `final request policy rejects an interceptor rewrite before DNS or dispatch`() = runBlocking {
        LoopbackTlsFixture().use { fixture ->
            val rewritten = fixture.client.newBuilder().addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .url("https://outside.example.test/berserker-base/user?synAccessSource=h5")
                        .build(),
                )
            }.build()
            val failure = runCatching {
                CardRechargeReadTransport(rewritten)
                    .get(campus("/berserker-base/user?synAccessSource=h5"))
            }.exceptionOrNull()

            val readFailure = failure as? CardRechargeReadFailure ?: error("Expected origin policy failure")
            assertEquals("P-ORIGIN", readFailure.stage)
            assertTrue(fixture.connectionAttempts.isEmpty())
            assertEquals(0, fixture.server.requestCount)
            assertEquals(0, readFailure.transport?.requestHeadersCount)
        }
    }

    @Test
    fun `preparation response body stays bounded`() = runBlocking {
        LoopbackTlsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody("x".repeat(2_000_001)))
            val failure = runCatching {
                CardRechargeReadTransport(fixture.client)
                    .get(campus("/berserker-base/user?synAccessSource=h5"))
            }.exceptionOrNull()

            val readFailure = failure as? CardRechargeReadFailure ?: error("Expected body limit failure")
            assertEquals("P-SIZE", readFailure.stage)
            assertEquals(DiagnosticTransportError.TOO_LARGE, readFailure.transport?.error)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun `redirect authentication rejection 407 408 and immediate 503 never follow up`() = runBlocking {
        val cases = listOf(
            302 to MockResponse().setResponseCode(302).setHeader("Location", "https://outside.example.test/"),
            401 to MockResponse().setResponseCode(401),
            407 to MockResponse().setResponseCode(407),
            408 to MockResponse().setResponseCode(408),
            503 to MockResponse().setResponseCode(503).setHeader("Retry-After", "0"),
        )
        for ((status, mock) in cases) {
            LoopbackTlsFixture().use { fixture ->
                fixture.server.enqueue(mock)
                val originAuth = AtomicInteger()
                val proxyAuth = AtomicInteger()
                val injected = fixture.client.newBuilder()
                    .authenticator(Authenticator { _, _ -> originAuth.incrementAndGet(); null })
                    .proxyAuthenticator(Authenticator { _, _ -> proxyAuth.incrementAndGet(); null })
                    .build()
                val result = runCatching {
                    CardRechargeReadTransport(injected)
                        .get(campus("/berserker-base/user?synAccessSource=h5"))
                }

                assertEquals(1, fixture.server.requestCount)
                assertEquals(0, originAuth.get())
                assertEquals(0, proxyAuth.get())
                val snapshot = result.getOrNull()?.transportSnapshot
                    ?: (result.exceptionOrNull() as? CardRechargeReadFailure)?.transport
                assertEquals(1, snapshot?.requestHeadersCount)
                if (status == 407) assertTrue(result.isFailure)
                else assertEquals(status, result.getOrThrow().status)
            }
        }
    }

    @Test
    fun `preparation dispatch binds original endpoint parameters and session headers`() = runBlocking {
        suspend fun assertRejected(
            original: Request,
            networkInterceptor: Boolean,
            rewrite: (Request) -> Request,
            replacementRemainsAllowed: Boolean,
        ) {
            LoopbackTlsFixture().use { fixture ->
                val replacement = rewrite(original)
                if (replacementRemainsAllowed) {
                    assertTrue(CardRechargePreparationReadPolicy.allows(replacement))
                }
                val builder = fixture.client.newBuilder()
                if (networkInterceptor) {
                    builder.addNetworkInterceptor { chain -> chain.proceed(rewrite(chain.request())) }
                } else {
                    builder.addInterceptor { chain -> chain.proceed(rewrite(chain.request())) }
                }

                val failure = runCatching {
                    CardRechargeReadTransport(builder.build()).get(original)
                }.exceptionOrNull()

                val readFailure = failure as? CardRechargeReadFailure
                    ?: error("Expected the original request binding to reject an interceptor rewrite")
                assertEquals("P-ORIGIN", readFailure.stage)
                assertEquals(0, fixture.server.requestCount)
            }
        }

        val identity = campus("/berserker-base/user?synAccessSource=h5")
        assertRejected(identity, networkInterceptor = false, rewrite = {
            it.newBuilder().url("${CardRechargeProtocol.BASE}/berserker-app/frontInfo?synAccessSource=h5").build()
        }, replacementRemainsAllowed = true)
        assertRejected(campus("/charge/feeitem/singleFeeitem?feeitemid=1"), networkInterceptor = true, rewrite = {
            it.newBuilder().url("${CardRechargeProtocol.BASE}/charge/feeitem/singleFeeitem?feeitemid=2").build()
        }, replacementRemainsAllowed = true)
        assertRejected(identity, networkInterceptor = false, rewrite = {
            it.newBuilder().header("synjones-auth", "bearer replacement-token").build()
        }, replacementRemainsAllowed = true)
        assertRejected(identity, networkInterceptor = true, rewrite = {
            it.newBuilder().header("synAccessSource", "other").build()
        }, replacementRemainsAllowed = false)
        assertRejected(identity, networkInterceptor = true, rewrite = {
            it.newBuilder().method("POST", "synthetic".toRequestBody()).build()
        }, replacementRemainsAllowed = false)
    }

    private fun campus(path: String): Request = Request.Builder()
        .url("https://mb-xyk.upc.edu.cn$path")
        .header("User-Agent", "UPC Pocket synthetic test")
        .header("Accept", "text/html,application/json")
        .header("Referer", "https://mb-xyk.upc.edu.cn/campus-card/")
        .header("Cache-Control", "no-store")
        .header("synjones-auth", "bearer fixture-token")
        .header("synAccessSource", "h5")
        .build()

    private fun assertSameAuthenticatorNone(client: OkHttpClient) {
        assertEquals(Authenticator.NONE, client.authenticator)
        assertEquals(Authenticator.NONE, client.proxyAuthenticator)
    }

    /** DNS and sockets route only to loopback; TLS verifies the fixed school hostname. */
    private class LoopbackTlsFixture(
        private val addresses: List<InetAddress> = listOf(SERVER_LOOPBACK),
        callTimeoutMillis: Long = 0,
    ) : Closeable {
        val server = MockWebServer()
        val connectionAttempts = Collections.synchronizedList(mutableListOf<InetAddress>())
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
                        if (hostname != SCHOOL_HOST) throw UnknownHostException("Synthetic host blocked")
                        return addresses
                    }
                })
                .socketFactory(object : SocketFactory() {
                    override fun createSocket(): Socket = object : Socket() {
                        override fun connect(endpoint: SocketAddress?, timeout: Int) {
                            check(endpoint is InetSocketAddress && endpoint.port == 443 && endpoint.address in addresses)
                            check(endpoint.address.isLoopbackAddress)
                            connectionAttempts += endpoint.address
                            val targetPort = if (endpoint.address == SERVER_LOOPBACK) server.port else endpoint.port
                            super.connect(InetSocketAddress(endpoint.address, targetPort), timeout)
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
            val trust = HandshakeCertificates.Builder()
                .addTrustedCertificate(CERTIFICATE.certificate)
                .build()
            builder.sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            client = builder.build()
        }

        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    private companion object {
        const val SCHOOL_HOST = "mb-xyk.upc.edu.cn"
        val SERVER_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val UNREACHABLE_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
        val CERTIFICATE: HeldCertificate by lazy {
            HeldCertificate.Builder()
                .commonName("UPC Pocket preparation fixture")
                .addSubjectAlternativeName(SCHOOL_HOST)
                .build()
        }
    }
}
