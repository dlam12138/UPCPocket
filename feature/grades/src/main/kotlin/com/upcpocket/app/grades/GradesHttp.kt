package com.upcpocket.app.grades

import com.upcpocket.app.upcauth.UpcAuthCookie
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.URLEncoder
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal enum class GradesRemoteFailure {
    AUTH_REQUIRED,
    NETWORK,
    TIMEOUT,
    TLS,
    TOO_LARGE,
    HTTP,
    UPSTREAM,
    SCHEMA,
    PROTOCOL,
}

internal class GradesRemoteException(
    val failure: GradesRemoteFailure,
) : Exception(failure.name, null, false, false)

internal data class GradesHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    override fun toString(): String = "GradesHttpRequest([REDACTED])"
}

internal class GradesHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.lastOrNull()

    fun text(): String = body.toString(Charsets.UTF_8)
}

internal interface GradesHttpTransport {
    suspend fun execute(request: GradesHttpRequest): GradesHttpResponse
}

internal class OkHttpGradesTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val maxBytes: Long = MAX_BYTES,
) : GradesHttpTransport {

    override suspend fun execute(request: GradesHttpRequest): GradesHttpResponse =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder()
                    .url(request.url)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", GradesHost.PAGE_URL)
                request.headers.forEach { (name, value) -> builder.header(name, value) }
                if (request.body != null) {
                    builder.method(
                        request.method,
                        request.body.toRequestBody(FORM_MEDIA_TYPE),
                    )
                } else {
                    builder.method(request.method, null)
                }
                client.newCall(builder.build()).execute().use { response ->
                    val length = response.body?.contentLength() ?: 0L
                    if (length > maxBytes) throw GradesRemoteException(GradesRemoteFailure.TOO_LARGE)
                    val body = response.body?.byteStream()?.let { stream ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) {
                                throw GradesRemoteException(GradesRemoteFailure.TOO_LARGE)
                            }
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    } ?: ByteArray(0)
                    val headers = linkedMapOf<String, MutableList<String>>()
                    response.headers.names().forEach { name ->
                        headers[name.lowercase()] = response.headers.values(name).toMutableList()
                    }
                    GradesHttpResponse(response.code, headers, body)
                }
            } catch (failure: GradesRemoteException) {
                throw failure
            } catch (_: InterruptedIOException) {
                throw GradesRemoteException(GradesRemoteFailure.TIMEOUT)
            } catch (_: SSLException) {
                throw GradesRemoteException(GradesRemoteFailure.TLS)
            } catch (_: CertificateException) {
                throw GradesRemoteException(GradesRemoteFailure.TLS)
            } catch (_: ProtocolException) {
                throw GradesRemoteException(GradesRemoteFailure.PROTOCOL)
            } catch (_: IllegalArgumentException) {
                throw GradesRemoteException(GradesRemoteFailure.PROTOCOL)
            } catch (_: IOException) {
                throw GradesRemoteException(GradesRemoteFailure.NETWORK)
            } catch (_: Exception) {
                throw GradesRemoteException(GradesRemoteFailure.NETWORK)
            }
        }

    private companion object {
        const val MAX_BYTES = 4L * 1024L * 1024L
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}

internal object GradesHost {
    const val APP_HOST = "app.upc.edu.cn"
    const val PAGE_URL = "https://app.upc.edu.cn/site/score/index"
    const val INDEX_URL = "https://app.upc.edu.cn/score/wap/default/get-index"
    const val DATA_URL = "https://app.upc.edu.cn/score/wap/default/get-data"
}

internal interface GradesRemote {
    suspend fun getIndex(cookies: List<UpcAuthCookie>): GradesIndex

    suspend fun getTerm(cookies: List<UpcAuthCookie>, term: GradesTerm): List<GradesCourse>
}

/**
 * 固定主机、固定路径的成绩只读客户端。
 *
 * 每次请求只带当前成绩内存会话的 `app.upc.edu.cn` Cookie；不跟随重定向、
 * 不重试、不记录请求或响应，也不把 Cookie 交给其他主机。`get-data` 使用固定
 * 表单字段 `year` / `xq` / 空 `kcmc`，不发送任何写操作。
 */
internal class GradesApi internal constructor(
    private val transport: GradesHttpTransport = OkHttpGradesTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) : GradesRemote {

    override suspend fun getIndex(cookies: List<UpcAuthCookie>): GradesIndex {
        val response = request("GET", GradesHost.INDEX_URL, cookies)
        return try {
            GradesProtocol.parseIndex(response.text())
        } catch (failure: GradesProtocolException) {
            throw GradesRemoteException(failure.failure)
        }
    }

    override suspend fun getTerm(
        cookies: List<UpcAuthCookie>,
        term: GradesTerm,
    ): List<GradesCourse> {
        // 只读查询：方法与固定字段都来自已核对协议，不携带任何提交/编辑语义。
        val body = "year=${encode(term.year)}&xq=${encode(term.term)}&kcmc="
        val response = request("POST", GradesHost.DATA_URL, cookies, body)
        return try {
            GradesProtocol.parseTerm(response.text(), term)
        } catch (failure: GradesProtocolException) {
            throw GradesRemoteException(failure.failure)
        }
    }

    private suspend fun request(
        method: String,
        url: String,
        cookies: List<UpcAuthCookie>,
        body: String? = null,
    ): GradesHttpResponse {
        if (url != GradesHost.INDEX_URL && url != GradesHost.DATA_URL) {
            throw GradesRemoteException(GradesRemoteFailure.PROTOCOL)
        }
        val cookieHeader = cookieHeader(cookies, url)
            ?: throw GradesRemoteException(GradesRemoteFailure.AUTH_REQUIRED)
        val response = try {
            transport.execute(
                GradesHttpRequest(
                    method = method,
                    url = url,
                    headers = mapOf("Cookie" to cookieHeader),
                    body = body,
                ),
            )
        } catch (failure: GradesRemoteException) {
            throw failure
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw GradesRemoteException(GradesRemoteFailure.NETWORK)
        }
        return when {
            response.status == 200 -> response
            response.status == 401 || response.status == 403 ->
                throw GradesRemoteException(GradesRemoteFailure.AUTH_REQUIRED)
            else -> throw GradesRemoteException(GradesRemoteFailure.HTTP)
        }
    }

    private fun cookieHeader(cookies: List<UpcAuthCookie>, url: String): String? {
        val nowSeconds = clock() / 1_000L
        val path = url.removePrefix("https://${GradesHost.APP_HOST}")
            .substringBefore('?')
        return cookies.asSequence()
            .filter { it.domain == GradesHost.APP_HOST }
            .filter {
                val expiresAt = it.expiresAtEpochSeconds
                expiresAt == null || expiresAt > nowSeconds
            }
            .filter { cookiePathMatches(it.path, path) }
            .sortedByDescending { it.path.length }
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf { it.isNotEmpty() }
    }

    private fun cookiePathMatches(cookiePath: String, requestPath: String): Boolean {
        if (cookiePath == "/") return true
        if (requestPath == cookiePath) return true
        if (!requestPath.startsWith(cookiePath)) return false
        return cookiePath.endsWith("/") || requestPath[cookiePath.length] == '/'
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
