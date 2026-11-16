package com.upcpocket.app.upcauth

import com.upcpocket.app.platform.diagnostics.DiagnosticTransportError
import com.upcpocket.app.platform.diagnostics.DiagnosticTransportSnapshot
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 认证适配使用的单次 HTTP 请求；URL 由适配内部固定，不接受外部传入。 */
internal data class UpcAuthHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val collectTransportDiagnostics: Boolean = false,
) {
    override fun toString(): String = "UpcAuthHttpRequest([REDACTED])"
}

internal class UpcAuthHttpResponse(
    val status: Int,
    /** 同名响应头按出现顺序保留。 */
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val transport: DiagnosticTransportSnapshot? = null,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.lastOrNull()
}

internal enum class UpcAuthTransportFailure {
    NETWORK,
    TIMEOUT,
    TLS,
    TOO_LARGE,
    PROTOCOL,
}

internal class UpcAuthTransportException(
    val failure: UpcAuthTransportFailure,
    val transport: DiagnosticTransportSnapshot? = null,
    val httpStatus: Int? = null,
    val responseBytes: Int? = null,
) : Exception(null, null, false, false)

internal interface UpcAuthTransport {
    @Throws(UpcAuthTransportException::class)
    fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse
}

/**
 * 固定 OkHttp 传输：不自动跟随重定向、不记录请求、不关闭证书校验。
 *
 * 重定向由认证适配逐跳验证后再显式请求，防止密码或认证头被转发到其他主机。
 */
internal class OkHttpUpcAuthTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val maxBytes: Long = MAX_BYTES,
    private val recoverCardEntry: Boolean = false,
) : UpcAuthTransport {

    // Only the public initial card entry may recover a failed connection route.
    // Ticket/code callbacks and password submissions keep the original single-call policy.
    private val cardEntryClient by lazy {
        val budgetMillis = client.callTimeoutMillis.takeIf { it > 0 }
            ?.coerceAtMost(CARD_ENTRY_CALL_TIMEOUT_MILLIS) ?: CARD_ENTRY_CALL_TIMEOUT_MILLIS
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .callTimeout(budgetMillis.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }
    private val observedClient by lazy { client.withAuthTransportTrace() }
    private val observedCardEntryClient by lazy { cardEntryClient.withAuthTransportTrace() }

    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
        val trace = if (request.collectTransportDiagnostics) runCatching { AuthTransportTrace() }.getOrNull() else null
        try {
            val builder = Request.Builder()
                .url(request.url)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("User-Agent", USER_AGENT)
            request.headers.forEach { (name, value) -> builder.header(name, value) }
            if (request.body != null) {
                builder.method(
                    request.method,
                    request.body.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                )
            } else {
                builder.method(request.method, null)
            }
            if (trace != null) builder.tag(AuthTransportTrace::class.java, trace)
            val recover = recoverCardEntry && request.method == "GET" &&
                request.body == null && request.url == CardAuthOrigins.ENTRY
            val baseClient = if (recover) cardEntryClient else client
            // A failure to install an observer drops observation, never a request.
            val requestClient = if (trace == null) baseClient else runCatching {
                if (recover) observedCardEntryClient else observedClient
            }.getOrDefault(baseClient)
            val result = requestClient.newCall(builder.build()).execute().use { response ->
                val length = response.body?.contentLength() ?: 0L
                if (length > maxBytes) throw UpcAuthTransportException(UpcAuthTransportFailure.TOO_LARGE)
                val body = response.body?.byteStream()?.let { stream ->
                    val buffer = ByteArray(8 * 1024)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0L
                    runCatching { trace?.bodyReadStart() }
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        // This is the actual, transparently decompressed read count.
                        // EventListener byteCount/Content-Length cannot replace it.
                        runCatching { trace?.bytesRead(read) }
                        if (total > maxBytes) {
                            throw UpcAuthTransportException(UpcAuthTransportFailure.TOO_LARGE)
                        }
                        out.write(buffer, 0, read)
                    }
                    runCatching { trace?.bodyReadEnd() }
                    out.toByteArray()
                } ?: ByteArray(0)
                val headers = linkedMapOf<String, MutableList<String>>()
                response.headers.names().forEach { name ->
                    headers[name.lowercase()] = response.headers.values(name).toMutableList()
                }
                UpcAuthHttpResponse(response.code, headers, body)
            }
            return UpcAuthHttpResponse(result.status, result.headers, result.body,
                runCatching { trace?.finish(null) }.getOrNull())
        } catch (failure: UpcAuthTransportException) {
            val error = when (failure.failure) {
                UpcAuthTransportFailure.TOO_LARGE -> DiagnosticTransportError.TOO_LARGE
                UpcAuthTransportFailure.TIMEOUT -> DiagnosticTransportError.TIMEOUT
                UpcAuthTransportFailure.TLS -> DiagnosticTransportError.TLS_IO
                UpcAuthTransportFailure.NETWORK -> DiagnosticTransportError.IO_OTHER
                UpcAuthTransportFailure.PROTOCOL -> DiagnosticTransportError.PROTOCOL
            }
            throw safeFailure(failure.failure, trace, error)
        } catch (failure: Exception) {
            val kind = when (failure) {
                is InterruptedIOException -> UpcAuthTransportFailure.TIMEOUT
                is SSLException, is CertificateException -> UpcAuthTransportFailure.TLS
                is ProtocolException, is IllegalArgumentException -> UpcAuthTransportFailure.PROTOCOL
                is IOException -> UpcAuthTransportFailure.NETWORK
                else -> UpcAuthTransportFailure.PROTOCOL
            }
            throw safeFailure(kind, trace, transportError(failure))
        }
    }

    private fun safeFailure(
        failure: UpcAuthTransportFailure,
        trace: AuthTransportTrace?,
        error: DiagnosticTransportError,
    ) = UpcAuthTransportException(
        failure,
        transport = runCatching { trace?.finish(error) }.getOrNull(),
        httpStatus = runCatching { trace?.httpStatus() }.getOrNull(),
        responseBytes = runCatching { trace?.responseBytes() }.getOrNull(),
    )

    private companion object {
        const val MAX_BYTES = 1024L * 1024L
        const val CARD_ENTRY_CALL_TIMEOUT_MILLIS = 20_000
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
