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
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateException
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

/**
 * Per-call, in-memory counters only. Never retains a call, URL, address, TLS
 * handshake, request/response, exception or body. The outer transport freezes
 * once after its bounded body reader and close, since callEnd may also follow
 * a local TOO_LARGE rejection and must not turn that rejection into success.
 */
internal class AuthTransportTrace(private val nanoTime: () -> Long = System::nanoTime) {
    private var startedAt = nanoTime()
    private var stage = DiagnosticTransportStage.CALL_START
    private val dns = Interval()
    private val connect = Interval()
    private val tls = Interval()
    private val headers = Interval()
    private val body = Interval()
    private var dnsQueries = 0
    private var ipv4: Int? = null
    private var ipv6: Int? = null
    private var connectAttempts = 0
    private var tlsAttempts = 0
    private var requestHeaders = 0
    private var reused: Int? = null
    private var protocol: DiagnosticTransportProtocol? = null
    private var proxyType: DiagnosticTransportProxyType? = null
    private var status: Int? = null
    private var readBytes: Int? = null
    private var frozen: DiagnosticTransportSnapshot? = null

    @Synchronized fun callStart() = update {
        startedAt = nanoTime()
        stage = DiagnosticTransportStage.CALL_START
    }

    @Synchronized fun dnsStart() = update {
        stage = DiagnosticTransportStage.DNS
        dnsQueries = increment(dnsQueries)
        dns.start(nanoTime())
    }

    @Synchronized fun dnsEnd(addresses: List<InetAddress>) = update {
        dns.end(nanoTime())
        // Inspect only the address family; no address/host strings are read.
        ipv4 = add(ipv4 ?: 0, addresses.count { it is Inet4Address })
        ipv6 = add(ipv6 ?: 0, addresses.count { it is Inet6Address })
    }

    @Synchronized fun connectStart(type: Proxy.Type) = update {
        val now = nanoTime()
        // A failed TLS route is not the stage of the next TCP attempt.
        tls.end(now)
        connect.end(now)
        stage = DiagnosticTransportStage.CONNECT
        connectAttempts = increment(connectAttempts)
        // A route attempt is not yet an acquired connection.
        reused = null
        status = null
        readBytes = null
        proxyType = when (type) {
            Proxy.Type.DIRECT -> DiagnosticTransportProxyType.DIRECT
            Proxy.Type.HTTP -> DiagnosticTransportProxyType.HTTP
            Proxy.Type.SOCKS -> DiagnosticTransportProxyType.SOCKS
        }
        connect.start(now)
    }

    @Synchronized fun tlsStart() = update {
        stage = DiagnosticTransportStage.TLS
        tlsAttempts = increment(tlsAttempts)
        tls.start(nanoTime())
    }

    @Synchronized fun tlsEnd() = update { tls.end(nanoTime()) }

    @Synchronized fun connectEnd(negotiated: Protocol?) = update {
        val now = nanoTime()
        connect.end(now)
        tls.end(now)
        negotiated?.let { protocol = protocolCategory(it) }
    }

    @Synchronized fun connectFailed() = update {
        val now = nanoTime()
        tls.end(now)
        connect.end(now)
        // Not terminal: OkHttp may now try a different route.
    }

    @Synchronized fun connectionAcquired(negotiated: Protocol) = update {
        reused = if (connectAttempts == 0) 1 else 0
        protocol = protocolCategory(negotiated)
    }

    @Synchronized fun requestHeadersStart() = update {
        val now = nanoTime()
        headers.end(now)
        body.end(now)
        status = null
        readBytes = null
        stage = DiagnosticTransportStage.REQUEST_HEADERS
    }

    @Synchronized fun requestHeadersEnd() = update {
        requestHeaders = increment(requestHeaders)
        headers.start(nanoTime())
        // OkHttp fires responseHeadersStart only AFTER reading headers. A
        // failure while waiting for the first header is already this stage.
        stage = DiagnosticTransportStage.RESPONSE_HEADERS
    }

    @Synchronized fun requestBodyStart() = update { stage = DiagnosticTransportStage.REQUEST_BODY }
    @Synchronized fun requestBodyEnd() = update { stage = DiagnosticTransportStage.RESPONSE_HEADERS }

    @Synchronized fun responseHeadersStart() = update {
        stage = DiagnosticTransportStage.RESPONSE_HEADERS
        if (!headers.active) headers.start(nanoTime())
    }

    @Synchronized fun responseHeadersEnd(code: Int, negotiated: Protocol) = update {
        stage = DiagnosticTransportStage.RESPONSE_HEADERS
        headers.end(nanoTime())
        status = code.takeIf { it in 100..599 }
        readBytes = 0
        protocol = protocolCategory(negotiated)
    }

    /** Begins at application consumption, including waiting for the first byte. */
    @Synchronized fun bodyReadStart() = update {
        stage = DiagnosticTransportStage.RESPONSE_BODY
        if (!body.active) body.start(nanoTime())
    }

    @Synchronized fun bytesRead(count: Int) = update {
        if (count > 0) {
            readBytes = ((readBytes ?: 0).toLong() + count)
                .coerceAtMost(DiagnosticEvent.MAX_RESPONSE_BYTES.toLong()).toInt()
        }
    }

    @Synchronized fun bodyReadEnd() = update { body.end(nanoTime()) }

    @Synchronized fun responseFailed() = update {
        val now = nanoTime()
        if (stage != DiagnosticTransportStage.RESPONSE_BODY) stage = DiagnosticTransportStage.RESPONSE_HEADERS
        headers.end(now)
        body.end(now)
    }

    @Synchronized fun callFinished() = update {
        val now = nanoTime()
        dns.end(now)
        connect.end(now)
        tls.end(now)
        headers.end(now)
        // Body consumption is timed by the decompressed application reader;
        // EventListener's byteCount is compressed and must never overwrite it.
    }

    @Synchronized fun httpStatus(): Int? = status
    @Synchronized fun responseBytes(): Int? = readBytes

    @Synchronized fun finish(error: DiagnosticTransportError?): DiagnosticTransportSnapshot {
        frozen?.let { return it }
        val now = nanoTime()
        return DiagnosticTransportSnapshot(
            stage = stage,
            error = error,
            totalMs = elapsed(startedAt, now) / NANOS_PER_MILLI,
            dnsMs = dns.millis(now),
            connectMs = connect.millis(now),
            tlsMs = tls.millis(now),
            headersMs = headers.millis(now),
            bodyMs = body.millis(now),
            dnsQueryCount = dnsQueries,
            dnsIpv4Count = ipv4,
            dnsIpv6Count = ipv6,
            connectAttemptCount = connectAttempts,
            tlsAttemptCount = tlsAttempts,
            requestHeadersCount = requestHeaders,
            connectionReused = reused,
            protocol = protocol,
            proxyType = proxyType,
        ).also { frozen = it }
    }

    private inline fun update(action: () -> Unit) { if (frozen == null) action() }

    private class Interval {
        private var started: Long? = null
        private var accumulated: Long? = null
        val active: Boolean get() = started != null
        fun start(now: Long) {
            end(now)
            if (accumulated == null) accumulated = 0
            started = now
        }
        fun end(now: Long) {
            started?.let { accumulated = sum(accumulated ?: 0, elapsed(it, now)) }
            started = null
        }
        fun millis(now: Long): Long? = accumulated?.let {
            sum(it, started?.let { start -> elapsed(start, now) } ?: 0) / NANOS_PER_MILLI
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_NANOS = DiagnosticTransportSnapshot.MAX_DURATION_MS * NANOS_PER_MILLI
        fun elapsed(start: Long, end: Long): Long = (end - start).coerceIn(0, MAX_NANOS)
        fun sum(left: Long, right: Long): Long = (left + right).coerceAtMost(MAX_NANOS)
        fun increment(count: Int): Int = add(count, 1)
        fun add(left: Int, right: Int): Int = (left.toLong() + right)
            .coerceAtMost(DiagnosticTransportSnapshot.MAX_COUNT.toLong()).toInt()
        fun protocolCategory(value: Protocol): DiagnosticTransportProtocol = when (value) {
            Protocol.HTTP_1_1 -> DiagnosticTransportProtocol.HTTP_1_1
            Protocol.HTTP_2 -> DiagnosticTransportProtocol.HTTP_2
            else -> DiagnosticTransportProtocol.OTHER
        }
    }
}

/** Only standard types are examined: no class names, messages, causes or stack frames. */
internal fun transportError(failure: Throwable): DiagnosticTransportError = when (failure) {
    is UnknownHostException -> DiagnosticTransportError.DNS
    is ConnectException -> DiagnosticTransportError.CONNECT
    is NoRouteToHostException -> DiagnosticTransportError.NO_ROUTE
    // Also covers OkHttp's plain InterruptedIOException call deadline wrapper.
    // This category alone does not prove which deadline or interruption fired.
    is InterruptedIOException -> DiagnosticTransportError.TIMEOUT
    is SSLHandshakeException -> DiagnosticTransportError.TLS_HANDSHAKE
    is SSLPeerUnverifiedException -> DiagnosticTransportError.TLS_PEER_UNVERIFIED
    is SSLException -> DiagnosticTransportError.TLS_IO
    is CertificateException -> DiagnosticTransportError.CERTIFICATE
    is EOFException -> DiagnosticTransportError.EOF
    is ProtocolException -> DiagnosticTransportError.PROTOCOL
    is SocketException -> DiagnosticTransportError.SOCKET
    is IllegalArgumentException -> DiagnosticTransportError.INVALID_REQUEST
    is IOException -> DiagnosticTransportError.IO_OTHER
    else -> DiagnosticTransportError.OTHER
}

/** Captures the original factory before replacement and creates it exactly once per call. */
internal fun OkHttpClient.withAuthTransportTrace(): OkHttpClient {
    val originalFactory = eventListenerFactory
    return newBuilder().eventListenerFactory { call ->
        val original = runCatching { originalFactory.create(call) }.getOrDefault(EventListener.NONE)
        val trace = call.request().tag(AuthTransportTrace::class.java)
        if (trace == null) original else AuthTransportEventListener(trace, original)
    }.build()
}

/** All 4.12 callbacks are forwarded; observation failures cannot affect a request. */
internal class AuthTransportEventListener(
    private val trace: AuthTransportTrace,
    private val original: EventListener,
) : EventListener() {
    private inline fun observe(action: () -> Unit) { runCatching(action) }
    private inline fun forward(action: () -> Unit) { runCatching(action) }
    override fun callStart(call: Call) { observe { trace.callStart() }; forward { original.callStart(call) } }
    override fun proxySelectStart(call: Call, url: HttpUrl) { forward { original.proxySelectStart(call, url) } }
    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) { forward { original.proxySelectEnd(call, url, proxies) } }
    override fun dnsStart(call: Call, domainName: String) { observe { trace.dnsStart() }; forward { original.dnsStart(call, domainName) } }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { observe { trace.dnsEnd(inetAddressList) }; forward { original.dnsEnd(call, domainName, inetAddressList) } }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { observe { trace.connectStart(proxy.type()) }; forward { original.connectStart(call, inetSocketAddress, proxy) } }
    override fun secureConnectStart(call: Call) { observe { trace.tlsStart() }; forward { original.secureConnectStart(call) } }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { observe { trace.tlsEnd() }; forward { original.secureConnectEnd(call, handshake) } }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { observe { trace.connectEnd(protocol) }; forward { original.connectEnd(call, inetSocketAddress, proxy, protocol) } }
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) { observe { trace.connectFailed() }; forward { original.connectFailed(call, inetSocketAddress, proxy, protocol, ioe) } }
    override fun connectionAcquired(call: Call, connection: Connection) { observe { trace.connectionAcquired(connection.protocol()) }; forward { original.connectionAcquired(call, connection) } }
    override fun connectionReleased(call: Call, connection: Connection) { forward { original.connectionReleased(call, connection) } }
    override fun requestHeadersStart(call: Call) { observe { trace.requestHeadersStart() }; forward { original.requestHeadersStart(call) } }
    override fun requestHeadersEnd(call: Call, request: Request) { observe { trace.requestHeadersEnd() }; forward { original.requestHeadersEnd(call, request) } }
    override fun requestBodyStart(call: Call) { observe { trace.requestBodyStart() }; forward { original.requestBodyStart(call) } }
    override fun requestBodyEnd(call: Call, byteCount: Long) { observe { trace.requestBodyEnd() }; forward { original.requestBodyEnd(call, byteCount) } }
    override fun requestFailed(call: Call, ioe: IOException) { forward { original.requestFailed(call, ioe) } }
    override fun responseHeadersStart(call: Call) { observe { trace.responseHeadersStart() }; forward { original.responseHeadersStart(call) } }
    override fun responseHeadersEnd(call: Call, response: Response) { observe { trace.responseHeadersEnd(response.code, response.protocol) }; forward { original.responseHeadersEnd(call, response) } }
    override fun responseBodyStart(call: Call) { forward { original.responseBodyStart(call) } }
    override fun responseBodyEnd(call: Call, byteCount: Long) { forward { original.responseBodyEnd(call, byteCount) } }
    override fun responseFailed(call: Call, ioe: IOException) { observe { trace.responseFailed() }; forward { original.responseFailed(call, ioe) } }
    override fun callEnd(call: Call) { observe { trace.callFinished() }; forward { original.callEnd(call) } }
    override fun callFailed(call: Call, ioe: IOException) { observe { trace.callFinished() }; forward { original.callFailed(call, ioe) } }
    override fun canceled(call: Call) { forward { original.canceled(call) } }
    override fun satisfactionFailure(call: Call, response: Response) { forward { original.satisfactionFailure(call, response) } }
    override fun cacheHit(call: Call, response: Response) { forward { original.cacheHit(call, response) } }
    override fun cacheMiss(call: Call) { forward { original.cacheMiss(call) } }
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) { forward { original.cacheConditionalHit(call, cachedResponse) } }
}
