package com.upcpocket.app.upcauth

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
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
)

internal class UpcAuthHttpResponse(
    val status: Int,
    /** 同名响应头按出现顺序保留。 */
    val headers: Map<String, List<String>>,
    val body: ByteArray,
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

internal class UpcAuthTransportException(val failure: UpcAuthTransportFailure) :
    Exception(null, null, false, false)

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
) : UpcAuthTransport {

    override fun execute(request: UpcAuthHttpRequest): UpcAuthHttpResponse {
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
        try {
            client.newCall(builder.build()).execute().use { response ->
                val length = response.body?.contentLength() ?: 0L
                if (length > maxBytes) throw UpcAuthTransportException(UpcAuthTransportFailure.TOO_LARGE)
                val body = response.body?.byteStream()?.let { stream ->
                    val buffer = ByteArray(8 * 1024)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw UpcAuthTransportException(UpcAuthTransportFailure.TOO_LARGE)
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                val headers = linkedMapOf<String, MutableList<String>>()
                response.headers.names().forEach { name ->
                    headers[name.lowercase()] = response.headers.values(name).toMutableList()
                }
                return UpcAuthHttpResponse(response.code, headers, body)
            }
        } catch (failure: UpcAuthTransportException) {
            throw failure
        } catch (_: SocketTimeoutException) {
            throw UpcAuthTransportException(UpcAuthTransportFailure.TIMEOUT)
        } catch (_: SSLHandshakeException) {
            throw UpcAuthTransportException(UpcAuthTransportFailure.TLS)
        } catch (_: SSLException) {
            throw UpcAuthTransportException(UpcAuthTransportFailure.TLS)
        } catch (_: CertificateException) {
            throw UpcAuthTransportException(UpcAuthTransportFailure.TLS)
        } catch (_: IOException) {
            throw UpcAuthTransportException(UpcAuthTransportFailure.NETWORK)
        } catch (_: IllegalArgumentException) {
            throw UpcAuthTransportException(UpcAuthTransportFailure.PROTOCOL)
        } catch (_: Exception) {
            throw UpcAuthTransportException(UpcAuthTransportFailure.PROTOCOL)
        }
    }

    private companion object {
        const val MAX_BYTES = 1024L * 1024L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
