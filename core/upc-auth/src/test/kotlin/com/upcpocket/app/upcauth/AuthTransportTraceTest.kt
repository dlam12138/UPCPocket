package com.upcpocket.app.upcauth

import com.upcpocket.app.platform.diagnostics.DiagnosticEvent
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportError
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportProtocol
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportProxyType
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportSnapshot
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportStage
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.Modifier
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.junit.Assert.*
import org.junit.Test

class AuthTransportTraceTest {
    @Test fun dnsFailureIncludesAnUnclosedIntervalAndLeavesAddressCountsUnknown() {
        var now = 0L
        val trace = AuthTransportTrace { now }
        trace.callStart()
        now = ms(2); trace.dnsStart()
        now = ms(32); trace.callFinished()
        val snapshot = trace.finish(DiagnosticTransportError.DNS)
        assertEquals(DiagnosticTransportStage.DNS, snapshot.stage)
        assertEquals(32L, snapshot.totalMs)
        assertEquals(30L, snapshot.dnsMs)
        assertEquals(1, snapshot.dnsQueryCount)
        assertNull(snapshot.dnsIpv4Count)
        assertNull(snapshot.dnsIpv6Count)
        assertNull(snapshot.connectMs)
        assertNull(snapshot.tlsMs)
        assertNull(trace.responseBytes())
        assertNull(trace.httpStatus())
    }

    @Test fun addressFamiliesComeOnlyFromCompletedDnsAndCountersSaturate() {
        val trace = AuthTransportTrace { 0 }
        val v4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val v6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        trace.dnsStart()
        trace.dnsEnd(listOf(v4, v6, v6))
        var snapshot = trace.finish(null)
        assertEquals(1, snapshot.dnsIpv4Count)
        assertEquals(2, snapshot.dnsIpv6Count)
        assertEquals(0L, snapshot.dnsMs)
        val saturated = AuthTransportTrace { 0 }
        repeat(80) {
            saturated.dnsStart(); saturated.dnsEnd(listOf(v4, v6))
            saturated.connectStart(Proxy.Type.DIRECT); saturated.tlsStart()
            saturated.requestHeadersStart(); saturated.requestHeadersEnd()
        }
        snapshot = saturated.finish(null)
        assertEquals(64, snapshot.dnsQueryCount)
        assertEquals(64, snapshot.dnsIpv4Count)
        assertEquals(64, snapshot.dnsIpv6Count)
        assertEquals(64, snapshot.connectAttemptCount)
        assertEquals(64, snapshot.tlsAttemptCount)
        assertEquals(64, snapshot.requestHeadersCount)
    }

    @Test fun aFailedTlsRouteDoesNotBecomeTheStageOfTheNextTcpFailure() {
        var now = 0L
        val trace = AuthTransportTrace { now }
        trace.callStart()
        now = ms(3); trace.connectStart(Proxy.Type.HTTP)
        now = ms(5); trace.tlsStart()
        now = ms(9); trace.connectFailed()
        now = ms(10); trace.connectStart(Proxy.Type.DIRECT)
        now = ms(12); trace.connectFailed()
        val snapshot = trace.finish(DiagnosticTransportError.CONNECT)
        assertEquals(DiagnosticTransportStage.CONNECT, snapshot.stage)
        assertEquals(2, snapshot.connectAttemptCount)
        assertEquals(1, snapshot.tlsAttemptCount)
        assertEquals(8L, snapshot.connectMs) // includes the 4ms TLS interval
        assertEquals(4L, snapshot.tlsMs)
        assertEquals(DiagnosticTransportProxyType.DIRECT, snapshot.proxyType)
        assertNull(snapshot.connectionReused)
    }

    @Test fun aFailedTcpOrTlsAttemptWithoutAnAcquiredConnectionHasUnknownReuse() {
        for (tlsStarted in listOf(false, true)) {
            val trace = AuthTransportTrace { 0 }
            trace.callStart()
            trace.connectStart(Proxy.Type.DIRECT)
            if (tlsStarted) trace.tlsStart()
            trace.connectFailed()
            trace.callFinished()
            val snapshot = trace.finish(if (tlsStarted) DiagnosticTransportError.TLS_HANDSHAKE
                else DiagnosticTransportError.CONNECT)
            assertNull(snapshot.connectionReused)
            assertEquals(1, snapshot.connectAttemptCount)
            assertEquals(if (tlsStarted) DiagnosticTransportStage.TLS else DiagnosticTransportStage.CONNECT,
                snapshot.stage)
        }
    }

    @Test fun reusedConnectionHasUnknownDnsConnectTlsAndHeaderFailureNeedsNoHeadersStart() {
        var now = 0L
        val trace = AuthTransportTrace { now }
        trace.connectionAcquired(Protocol.HTTP_2)
        now = ms(2); trace.requestHeadersStart()
        now = ms(5); trace.requestHeadersEnd()
        now = ms(15); trace.responseFailed()
        val snapshot = trace.finish(DiagnosticTransportError.EOF)
        assertEquals(DiagnosticTransportStage.RESPONSE_HEADERS, snapshot.stage)
        assertEquals(10L, snapshot.headersMs)
        assertNull(snapshot.dnsMs)
        assertNull(snapshot.connectMs)
        assertNull(snapshot.tlsMs)
        assertNull(snapshot.proxyType)
        assertNull(snapshot.dnsIpv4Count)
        assertNull(snapshot.dnsIpv6Count)
        assertEquals(0, snapshot.dnsQueryCount)
        assertEquals(0, snapshot.connectAttemptCount)
        assertEquals(1, snapshot.connectionReused)
        assertEquals(DiagnosticTransportProtocol.HTTP_2, snapshot.protocol)
        assertEquals(1, snapshot.requestHeadersCount)
        assertNull(trace.httpStatus())
    }

    @Test fun bodyFailureRetainsDeliveredBytesAndStatusWithoutBodyEnd() {
        var now = 0L
        val trace = AuthTransportTrace { now }
        trace.requestHeadersStart(); trace.requestHeadersEnd()
        now = ms(4); trace.responseHeadersEnd(200, Protocol.HTTP_1_1)
        now = ms(5); trace.bodyReadStart()
        trace.bytesRead(3); trace.bytesRead(7)
        now = ms(15); trace.responseFailed(); trace.callFinished()
        val snapshot = trace.finish(DiagnosticTransportError.EOF)
        assertEquals(DiagnosticTransportStage.RESPONSE_BODY, snapshot.stage)
        assertEquals(10L, snapshot.bodyMs)
        assertEquals(200, trace.httpStatus())
        assertEquals(10, trace.responseBytes())
        assertEquals(DiagnosticTransportError.EOF, snapshot.error)
    }

    @Test fun aNewExchangeClearsThePreviousStatusAndReadCount() {
        val trace = AuthTransportTrace { 0 }
        trace.requestHeadersStart(); trace.requestHeadersEnd()
        trace.responseHeadersEnd(302, Protocol.HTTP_1_1)
        trace.bodyReadStart(); trace.bytesRead(3); trace.bodyReadEnd()
        trace.requestHeadersStart(); trace.requestHeadersEnd()
        trace.responseFailed()
        assertEquals(DiagnosticTransportStage.RESPONSE_HEADERS, trace.finish(DiagnosticTransportError.EOF).stage)
        assertNull(trace.httpStatus())
        assertNull(trace.responseBytes())
    }

    @Test fun freezeIsOnceAndCallEndCannotEraseAnApplicationReadRejection() {
        var now = 0L
        val trace = AuthTransportTrace { now }
        trace.responseHeadersEnd(200, Protocol.HTTP_1_1)
        trace.bodyReadStart(); trace.bytesRead(Int.MAX_VALUE)
        now = ms(900_000); trace.callFinished()
        val first = trace.finish(DiagnosticTransportError.TOO_LARGE)
        trace.callStart(); trace.dnsStart(); trace.bytesRead(1)
        assertSame(first, trace.finish(null))
        assertEquals(DiagnosticTransportStage.RESPONSE_BODY, first.stage)
        assertEquals(DiagnosticTransportError.TOO_LARGE, first.error)
        assertEquals(DiagnosticTransportSnapshot.MAX_DURATION_MS, first.totalMs)
        assertEquals(DiagnosticTransportSnapshot.MAX_DURATION_MS, first.bodyMs)
        assertEquals(DiagnosticEvent.MAX_RESPONSE_BYTES, trace.responseBytes())
    }

    @Test fun allStandardErrorsIgnoreSecretsMessagesAndCauses() {
        val cases = listOf(
            UnknownHostException(CANARY) to DiagnosticTransportError.DNS,
            ConnectException(CANARY) to DiagnosticTransportError.CONNECT,
            NoRouteToHostException(CANARY) to DiagnosticTransportError.NO_ROUTE,
            InterruptedIOException(CANARY) to DiagnosticTransportError.TIMEOUT,
            SSLHandshakeException(CANARY) to DiagnosticTransportError.TLS_HANDSHAKE,
            SSLPeerUnverifiedException(CANARY) to DiagnosticTransportError.TLS_PEER_UNVERIFIED,
            SSLException(CANARY) to DiagnosticTransportError.TLS_IO,
            CertificateException(CANARY) to DiagnosticTransportError.CERTIFICATE,
            EOFException(CANARY) to DiagnosticTransportError.EOF,
            SocketException(CANARY) to DiagnosticTransportError.SOCKET,
            ProtocolException(CANARY) to DiagnosticTransportError.PROTOCOL,
            IllegalArgumentException(CANARY) to DiagnosticTransportError.INVALID_REQUEST,
            IOException(CANARY) to DiagnosticTransportError.IO_OTHER,
            RuntimeException(CANARY) to DiagnosticTransportError.OTHER,
        )
        cases.forEach { (failure, expected) ->
            failure.initCause(RuntimeException(CANARY))
            assertEquals(expected, transportError(failure))
            val safe = UpcAuthTransportException(UpcAuthTransportFailure.NETWORK,
                AuthTransportTrace { 0 }.finish(transportError(failure)))
            assertNull(safe.message)
            assertNull(safe.cause)
            assertTrue(safe.stackTrace.isEmpty())
            assertFalse(safe.transport.toString().contains("canary"))
            assertFalse(safe.transport.toString().contains("192.0.2.8"))
        }
        val hostile = object : IOException() {
            override val message: String get() = error("must not read message")
            override val cause: Throwable get() = error("must not read cause")
        }
        assertEquals(DiagnosticTransportError.IO_OTHER, transportError(hostile))
    }

    @Test fun callbacksAndOriginalObserverFailuresRemainIsolated() {
        val client = OkHttpClient()
        val request = Request.Builder().url("https://synthetic.example.test/").build()
        val call = client.newCall(request) // No execute/enqueue, no network.
        var failClock = false
        val trace = AuthTransportTrace { if (failClock) error(CANARY) else 0 }
        var called = 0
        val listener = AuthTransportEventListener(trace, object : EventListener() {
            override fun dnsStart(call: Call, domainName: String) { called++; error(CANARY) }
        })
        failClock = true
        listener.dnsStart(call, CANARY)
        assertEquals(1, called)
        failClock = false
        assertEquals(DiagnosticTransportStage.DNS, trace.finish(null).stage)
    }

    @Test fun concurrentCallsNeverShareCountersOrStages() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<DiagnosticTransportSnapshot> {
                val trace = AuthTransportTrace { 0 }
                trace.dnsStart(); trace.dnsEnd(emptyList())
                trace.connectStart(Proxy.Type.SOCKS)
                trace.finish(DiagnosticTransportError.CONNECT)
            }
            val second = executor.submit<DiagnosticTransportSnapshot> {
                val trace = AuthTransportTrace { 0 }
                trace.connectionAcquired(Protocol.HTTP_2)
                trace.requestHeadersStart(); trace.requestHeadersEnd()
                trace.responseHeadersEnd(200, Protocol.HTTP_2)
                trace.bodyReadStart(); trace.bytesRead(2); trace.bodyReadEnd()
                trace.finish(null)
            }
            val a = first.get(2, TimeUnit.SECONDS)
            val b = second.get(2, TimeUnit.SECONDS)
            assertEquals(DiagnosticTransportStage.CONNECT, a.stage)
            assertEquals(DiagnosticTransportProxyType.SOCKS, a.proxyType)
            assertEquals(1, a.dnsQueryCount)
            assertEquals(1, a.connectAttemptCount)
            assertEquals(0, b.dnsQueryCount)
            assertEquals(0, b.connectAttemptCount)
            assertNull(b.proxyType)
            assertEquals(DiagnosticTransportStage.RESPONSE_BODY, b.stage)
            assertNull(b.error)
        } finally { executor.shutdownNow() }
    }

    @Test fun everyOkHttp412CallbackIsOverriddenAndForwardedExactlyOnce() {
        val callbackMethods = EventListener::class.java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
        }
        val overrides = AuthTransportEventListener::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(29, callbackMethods.size)
        assertTrue(callbackMethods.all { it.name in overrides })
        val counts = mutableMapOf<String, Int>()
        fun mark(name: String) { counts[name] = (counts[name] ?: 0) + 1 }
        val delegate = object : EventListener() {
            override fun callStart(call: Call) { mark("callStart") }
            override fun proxySelectStart(call: Call, url: HttpUrl) { mark("proxySelectStart") }
            override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) { mark("proxySelectEnd") }
            override fun dnsStart(call: Call, domainName: String) { mark("dnsStart") }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { mark("dnsEnd") }
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { mark("connectStart") }
            override fun secureConnectStart(call: Call) { mark("secureConnectStart") }
            override fun secureConnectEnd(call: Call, handshake: Handshake?) { mark("secureConnectEnd") }
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { mark("connectEnd") }
            override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) { mark("connectFailed") }
            override fun connectionAcquired(call: Call, connection: Connection) { mark("connectionAcquired") }
            override fun connectionReleased(call: Call, connection: Connection) { mark("connectionReleased") }
            override fun requestHeadersStart(call: Call) { mark("requestHeadersStart") }
            override fun requestHeadersEnd(call: Call, request: Request) { mark("requestHeadersEnd") }
            override fun requestBodyStart(call: Call) { mark("requestBodyStart") }
            override fun requestBodyEnd(call: Call, byteCount: Long) { mark("requestBodyEnd") }
            override fun requestFailed(call: Call, ioe: IOException) { mark("requestFailed") }
            override fun responseHeadersStart(call: Call) { mark("responseHeadersStart") }
            override fun responseHeadersEnd(call: Call, response: Response) { mark("responseHeadersEnd") }
            override fun responseBodyStart(call: Call) { mark("responseBodyStart") }
            override fun responseBodyEnd(call: Call, byteCount: Long) { mark("responseBodyEnd") }
            override fun responseFailed(call: Call, ioe: IOException) { mark("responseFailed") }
            override fun callEnd(call: Call) { mark("callEnd") }
            override fun callFailed(call: Call, ioe: IOException) { mark("callFailed") }
            override fun canceled(call: Call) { mark("canceled") }
            override fun satisfactionFailure(call: Call, response: Response) { mark("satisfactionFailure") }
            override fun cacheHit(call: Call, response: Response) { mark("cacheHit") }
            override fun cacheMiss(call: Call) { mark("cacheMiss") }
            override fun cacheConditionalHit(call: Call, cachedResponse: Response) { mark("cacheConditionalHit") }
        }
        val request = Request.Builder().url("https://synthetic.example.test/").build()
        val call = OkHttpClient().newCall(request)
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("").build()
        val endpoint = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 443)
        val connection = object : Connection {
            override fun route(): Route = error("must not read route")
            override fun socket(): Socket = error("must not read socket")
            override fun handshake(): Handshake? = error("must not read handshake")
            override fun protocol() = Protocol.HTTP_1_1
        }
        val listener = AuthTransportEventListener(AuthTransportTrace { 0 }, delegate)
        callbackMethods.forEach { method ->
            val args = method.parameterTypes.map { type ->
                when (type) {
                    Call::class.java -> call
                    HttpUrl::class.java -> request.url
                    String::class.java -> CANARY
                    InetSocketAddress::class.java -> endpoint
                    Proxy::class.java -> Proxy.NO_PROXY
                    Protocol::class.java -> Protocol.HTTP_1_1
                    Handshake::class.java -> null
                    IOException::class.java -> IOException(CANARY)
                    Connection::class.java -> connection
                    Request::class.java -> request
                    Response::class.java -> response
                    java.lang.Long.TYPE -> 123L
                    List::class.java -> emptyList<Any>()
                    else -> error("Unknown callback argument")
                }
            }.toTypedArray()
            method.invoke(listener, *args)
        }
        assertEquals(callbackMethods.map { it.name }.toSet(), counts.keys)
        assertTrue(counts.values.all { it == 1 })
    }

    private companion object {
        const val CANARY = "account_canary password_canary cookie_canary token_canary https://secret.example/?ticket=canary 192.0.2.8 <html>body_canary</html> order_canary"
        fun ms(value: Long): Long = value * 1_000_000L
    }
}
