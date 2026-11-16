package com.upcpocket.app.data.cardhistory

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
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** The recovery client is selected only for the card's fixed, idempotent JSON reads. */
internal object CardHistoryReadPolicy {
    fun allows(request: Request): Boolean {
        val url = request.url
        if (request.method != "GET" || request.body != null || !url.isHttps || url.host != CardHistoryProtocol.HOST ||
            url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null
        ) return false

        return when (url.encodedPath) {
            "/berserker-base/user" -> {
                url.queryParameterNames == setOf("synAccessSource") &&
                    url.queryParameterValues("synAccessSource").singleOrNull() == "h5"
            }
            "/berserker-search/search/personal/turnover",
            "/berserker-search/statistics/turnover/count" -> validTurnoverQuery(url)
            else -> false
        }
    }

    private fun validTurnoverQuery(url: HttpUrl): Boolean {
        val required = setOf("size", "current", "synAccessSource")
        val allowed = required + setOf("timeFrom", "timeTo")
        val names = url.queryParameterNames
        if (!names.containsAll(required) || names.any { it !in allowed } ||
            names.any { url.queryParameterValues(it).size != 1 } ||
            url.queryParameterValues("synAccessSource").singleOrNull() != "h5"
        ) return false

        val size = url.queryParameterValues("size").singleOrNull()?.toIntOrNull() ?: return false
        val current = url.queryParameterValues("current").singleOrNull()?.toIntOrNull() ?: return false
        if (size !in 1..100 || current < 1) return false

        val from = url.queryParameterValues("timeFrom").singleOrNull()
        val to = url.queryParameterValues("timeTo").singleOrNull()
        if ((from == null) != (to == null)) return false
        if (from != null && to != null) {
            val start = runCatching { java.time.LocalDate.parse(from) }.getOrNull() ?: return false
            val end = runCatching { java.time.LocalDate.parse(to) }.getOrNull() ?: return false
            if (start > end) return false
        }
        return true
    }
}

/** In-memory counters only; raw addresses, request data and exception text are never retained. */
internal class CardHistoryTransportTrace(private val nanoTime: () -> Long = System::nanoTime) {
    private var startedAt = nanoTime()
    private var stage = DiagnosticTransportStage.CALL_START
    private var error: DiagnosticTransportError? = null
    private val dns = Interval()
    private val connect = Interval()
    private val tls = Interval()
    private val headers = Interval()
    private val body = Interval()
    private var dnsQueries: Int? = null
    private var ipv4: Int? = null
    private var ipv6: Int? = null
    private var connectAttempts: Int? = null
    private var tlsAttempts: Int? = null
    private var requestHeaders: Int? = null
    private var connectionReused: Int? = null
    private var protocol: DiagnosticTransportProtocol? = null
    private var proxyType: DiagnosticTransportProxyType? = null
    private var coroutineCancellationObserved = false
    private var frozen: DiagnosticTransportSnapshot? = null

    @Synchronized fun callStart() = update {
        startedAt = nanoTime()
        if (!coroutineCancellationObserved) {
            stage = DiagnosticTransportStage.CALL_START
            error = null
        }
    }

    @Synchronized fun dnsStart() = update {
        stage = DiagnosticTransportStage.DNS
        dnsQueries = increment(dnsQueries ?: 0)
        dns.start(nanoTime())
    }

    @Synchronized fun dnsEnd(addresses: List<InetAddress>) = update {
        dns.end(nanoTime())
        ipv4 = add(ipv4 ?: 0, addresses.count { it is Inet4Address })
        ipv6 = add(ipv6 ?: 0, addresses.count { it is Inet6Address })
    }

    @Synchronized fun connectStart(type: Proxy.Type) = update {
        val now = nanoTime()
        tls.end(now)
        connect.end(now)
        stage = DiagnosticTransportStage.CONNECT
        connectAttempts = increment(connectAttempts ?: 0)
        connectionReused = null
        if (!coroutineCancellationObserved) error = null
        proxyType = when (type) {
            Proxy.Type.DIRECT -> DiagnosticTransportProxyType.DIRECT
            Proxy.Type.HTTP -> DiagnosticTransportProxyType.HTTP
            Proxy.Type.SOCKS -> DiagnosticTransportProxyType.SOCKS
        }
        connect.start(now)
    }

    @Synchronized fun tlsStart() = update {
        stage = DiagnosticTransportStage.TLS
        tlsAttempts = increment(tlsAttempts ?: 0)
        tls.start(nanoTime())
    }

    @Synchronized fun tlsEnd() = update { tls.end(nanoTime()) }

    @Synchronized fun connectEnd(negotiated: Protocol?) = update {
        val now = nanoTime()
        connect.end(now)
        tls.end(now)
        if (!coroutineCancellationObserved) error = null
        negotiated?.let { protocol = protocolCategory(it) }
    }

    @Synchronized fun connectFailed(failure: IOException) = update {
        val now = nanoTime()
        tls.end(now)
        connect.end(now)
        if (!coroutineCancellationObserved) error = transportError(failure)
        if (failure is SSLException) stage = DiagnosticTransportStage.TLS
        else stage = DiagnosticTransportStage.CONNECT
    }

    @Synchronized fun connectionAcquired(negotiated: Protocol) = update {
        connectionReused = if ((connectAttempts ?: 0) == 0) 1 else 0
        protocol = protocolCategory(negotiated)
        if (!coroutineCancellationObserved) error = null
    }

    @Synchronized fun requestHeadersStart() = update {
        val now = nanoTime()
        headers.end(now)
        body.end(now)
        stage = DiagnosticTransportStage.REQUEST_HEADERS
        requestHeaders = increment(requestHeaders ?: 0)
    }

    @Synchronized fun requestHeadersEnd() = update {
        headers.start(nanoTime())
        stage = DiagnosticTransportStage.RESPONSE_HEADERS
    }

    @Synchronized fun requestBodyStart() = update { stage = DiagnosticTransportStage.REQUEST_BODY }

    @Synchronized fun requestBodyEnd() = update { stage = DiagnosticTransportStage.RESPONSE_HEADERS }

    @Synchronized fun responseHeadersStart() = update {
        stage = DiagnosticTransportStage.RESPONSE_HEADERS
        if (!headers.active) headers.start(nanoTime())
    }

    @Synchronized fun responseHeadersEnd(negotiated: Protocol) = update {
        stage = DiagnosticTransportStage.RESPONSE_HEADERS
        headers.end(nanoTime())
        protocol = protocolCategory(negotiated)
        if (!coroutineCancellationObserved) error = null
    }

    /** The accepted response reader owns this interval, including a wait for its first byte. */
    @Synchronized fun responseBodyReadStart() = update {
        stage = DiagnosticTransportStage.RESPONSE_BODY
        if (!body.active) body.start(nanoTime())
    }

    @Synchronized fun responseBodyReadEnd() = update {
        body.end(nanoTime())
        stage = DiagnosticTransportStage.RESPONSE_BODY
    }

    @Synchronized fun responseFailed(failure: IOException) = update {
        val now = nanoTime()
        if (stage != DiagnosticTransportStage.RESPONSE_BODY) stage = DiagnosticTransportStage.RESPONSE_HEADERS
        headers.end(now)
        body.end(now)
        if (!coroutineCancellationObserved) error = transportError(failure)
    }

    @Synchronized fun responseTooLarge() = update {
        stage = DiagnosticTransportStage.RESPONSE_BODY
        if (!coroutineCancellationObserved) error = DiagnosticTransportError.TOO_LARGE
    }

    @Synchronized fun callEnd() = update {
        closeIntervals(nanoTime())
        if (!coroutineCancellationObserved) error = null
    }

    @Synchronized fun callFailed(failure: IOException) = update {
        closeIntervals(nanoTime())
        if (!coroutineCancellationObserved) error = transportError(failure)
    }

    /** Only the coroutine cancellation handler calls this; OkHttp also cancels Calls on timeout. */
    @Synchronized fun coroutineCancelled() = update {
        coroutineCancellationObserved = true
        error = DiagnosticTransportError.CANCELLED
    }

    @Synchronized fun snapshot(): DiagnosticTransportSnapshot {
        frozen?.let { return it }
        val now = nanoTime()
        return DiagnosticTransportSnapshot(
            stage = stage,
            totalMs = elapsed(startedAt, now) / NANOS_PER_MILLI,
            error = error,
            dnsMs = dns.millis(now),
            connectMs = connect.millis(now),
            tlsMs = tls.millis(now),
            headersMs = headers.millis(now),
            bodyMs = body.millis(now),
            dnsQueryCount = dnsQueries ?: 0,
            dnsIpv4Count = ipv4,
            dnsIpv6Count = ipv6,
            connectAttemptCount = connectAttempts ?: 0,
            tlsAttemptCount = tlsAttempts ?: 0,
            requestHeadersCount = requestHeaders ?: 0,
            connectionReused = connectionReused,
            protocol = protocol,
            proxyType = proxyType,
        ).also { frozen = it }
    }

    private fun closeIntervals(now: Long) {
        dns.end(now)
        connect.end(now)
        tls.end(now)
        headers.end(now)
        body.end(now)
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
            sum(it, started?.let { startedAt -> elapsed(startedAt, now) } ?: 0) / NANOS_PER_MILLI
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

private fun transportError(failure: IOException): DiagnosticTransportError = when (failure) {
    is UnknownHostException -> DiagnosticTransportError.DNS
    is NoRouteToHostException -> DiagnosticTransportError.NO_ROUTE
    is ConnectException -> DiagnosticTransportError.CONNECT
    is InterruptedIOException -> DiagnosticTransportError.TIMEOUT
    is SSLHandshakeException -> DiagnosticTransportError.TLS_HANDSHAKE
    is SSLPeerUnverifiedException -> DiagnosticTransportError.TLS_PEER_UNVERIFIED
    is SSLException -> DiagnosticTransportError.TLS_IO
    is EOFException -> DiagnosticTransportError.EOF
    is ProtocolException -> DiagnosticTransportError.PROTOCOL
    is SocketException -> DiagnosticTransportError.SOCKET
    else -> DiagnosticTransportError.IO_OTHER
}

private fun OkHttpClient.withCardHistoryTransportTrace(): OkHttpClient {
    val originalFactory = eventListenerFactory
    return newBuilder().eventListenerFactory { call ->
        val original = runCatching { originalFactory.create(call) }.getOrDefault(EventListener.NONE)
        val trace = call.request().tag(CardHistoryTransportTrace::class.java)
        if (trace == null) original else CardHistoryTransportEventListener(trace, original)
    }.build()
}

/** Observe fixed numeric fields while preserving any listener supplied with the base client. */
private class CardHistoryTransportEventListener(
    private val trace: CardHistoryTransportTrace,
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
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) { observe { trace.connectFailed(ioe) }; forward { original.connectFailed(call, inetSocketAddress, proxy, protocol, ioe) } }
    override fun connectionAcquired(call: Call, connection: Connection) { observe { trace.connectionAcquired(connection.protocol()) }; forward { original.connectionAcquired(call, connection) } }
    override fun connectionReleased(call: Call, connection: Connection) { forward { original.connectionReleased(call, connection) } }
    override fun requestHeadersStart(call: Call) { observe { trace.requestHeadersStart() }; forward { original.requestHeadersStart(call) } }
    override fun requestHeadersEnd(call: Call, request: Request) { observe { trace.requestHeadersEnd() }; forward { original.requestHeadersEnd(call, request) } }
    override fun requestBodyStart(call: Call) { observe { trace.requestBodyStart() }; forward { original.requestBodyStart(call) } }
    override fun requestBodyEnd(call: Call, byteCount: Long) { observe { trace.requestBodyEnd() }; forward { original.requestBodyEnd(call, byteCount) } }
    override fun responseHeadersStart(call: Call) { observe { trace.responseHeadersStart() }; forward { original.responseHeadersStart(call) } }
    override fun responseHeadersEnd(call: Call, response: Response) { observe { trace.responseHeadersEnd(response.protocol) }; forward { original.responseHeadersEnd(call, response) } }
    override fun responseBodyStart(call: Call) { forward { original.responseBodyStart(call) } }
    override fun responseBodyEnd(call: Call, byteCount: Long) { forward { original.responseBodyEnd(call, byteCount) } }
    override fun responseFailed(call: Call, ioe: IOException) { observe { trace.responseFailed(ioe) }; forward { original.responseFailed(call, ioe) } }
    override fun callEnd(call: Call) { observe { trace.callEnd() }; forward { original.callEnd(call) } }
    override fun callFailed(call: Call, ioe: IOException) { observe { trace.callFailed(ioe) }; forward { original.callFailed(call, ioe) } }
    // Call.cancel() is also OkHttp's timeout mechanism; only coroutine cancellation means CANCELLED.
    override fun canceled(call: Call) { forward { original.canceled(call) } }
    override fun satisfactionFailure(call: Call, response: Response) { forward { original.satisfactionFailure(call, response) } }
    override fun cacheHit(call: Call, response: Response) { forward { original.cacheHit(call, response) } }
    override fun cacheMiss(call: Call) { forward { original.cacheMiss(call) } }
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) { forward { original.cacheConditionalHit(call, cachedResponse) } }
}

internal class CardHistoryReadTransport(
    http: OkHttpClient,
    private val requestPolicy: (Request) -> Boolean = { CardHistoryReadPolicy.allows(it) },
    private val connectTimeoutCapMs: Long? = null,
) {
    /** Normalize caller clients so an accidental future request remains one-shot and redirect-free. */
    private val base = http.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .apply {
            // Run after supplied app interceptors, before DNS and OkHttp follow-ups.
            interceptors().add(CardHistoryApplicationRequestPolicy(requestPolicy))
            // Suppress status-code retries after other network interceptors have observed the response.
            networkInterceptors().add(0, CardHistoryHttpFollowUpPolicy())
            // Validate the final network request after supplied network interceptors.
            networkInterceptors().add(CardHistoryNetworkRequestPolicy(requestPolicy))
        }
        .build()

    private val recovery = base.newBuilder().apply {
        retryOnConnectionFailure(true)
        callTimeout(
            (base.callTimeoutMillis.takeIf { it > 0 }?.toLong() ?: CARD_HISTORY_GET_TIMEOUT_MS)
                .coerceAtMost(CARD_HISTORY_GET_TIMEOUT_MS),
            TimeUnit.MILLISECONDS,
        )
        connectTimeoutCapMs?.let { cap ->
            val configured = base.connectTimeoutMillis.toLong()
            connectTimeout(if (configured == 0L) cap else configured.coerceAtMost(cap), TimeUnit.MILLISECONDS)
        }
    }.build()
        .withCardHistoryTransportTrace()

    data class PreparedCall(
        val client: OkHttpClient,
        val request: Request,
        val trace: CardHistoryTransportTrace?,
    )

    fun prepare(request: Request): PreparedCall {
        val trace = if (requestPolicy(request)) CardHistoryTransportTrace() else null
        val taggedRequest = trace?.let {
            request.newBuilder().tag(CardHistoryTransportTrace::class.java, it).build()
        } ?: request
        return PreparedCall(if (trace == null) base else recovery, taggedRequest, trace)
    }
}

internal const val CARD_HISTORY_GET_TIMEOUT_MS = 20_000L

private class CardHistoryApplicationRequestPolicy(
    private val requestPolicy: (Request) -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!requestPolicy(request)) throw CardHistoryRequestPolicyException()
        return chain.proceed(request)
    }
}

private class CardHistoryNetworkRequestPolicy(
    private val requestPolicy: (Request) -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!requestPolicy(request)) throw CardHistoryRequestPolicyException()
        return chain.proceed(request)
    }
}

internal class CardHistoryRequestPolicyException : ProtocolException()

/** OkHttp also treats 408 and Retry-After: 0 on 503 as HTTP follow-ups when retries are enabled. */
private class CardHistoryHttpFollowUpPolicy : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val retryAfter = response.header("Retry-After")
        val retryImmediately = retryAfter != null && retryAfter.matches(Regex("\\d+")) && retryAfter.toIntOrNull() == 0
        return when {
            response.code == 408 && (retryAfter == null || retryImmediately) ->
                response.newBuilder().header("Retry-After", "1").build()
            response.code == 503 && retryImmediately ->
                response.newBuilder().header("Retry-After", "1").build()
            else -> response
        }
    }
}
