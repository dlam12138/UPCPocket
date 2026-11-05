package com.upcpocket.app.timetable

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class TimetableHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

internal class TimetableHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.lastOrNull()
}

internal enum class TimetableTransportFailure {
    NETWORK,
    TIMEOUT,
    TLS,
    TOO_LARGE,
}

internal class TimetableTransportException(val failure: TimetableTransportFailure) :
    Exception(null, null, false, false)

internal interface TimetableHttpTransport {
    suspend fun execute(request: TimetableHttpRequest): TimetableHttpResponse
}

internal class OkHttpTimetableTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
    private val maxBytes: Long = 2L * 1024L * 1024L,
) : TimetableHttpTransport {

    override suspend fun execute(request: TimetableHttpRequest): TimetableHttpResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(request.url)
                .header("Accept", "application/json, text/plain, */*")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://app.upc.edu.cn/site/weekschedule/index")
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
                    if (length > maxBytes) {
                        throw TimetableTransportException(TimetableTransportFailure.TOO_LARGE)
                    }
                    val body = response.body?.byteStream()?.let { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var total = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) {
                                throw TimetableTransportException(
                                    TimetableTransportFailure.TOO_LARGE,
                                )
                            }
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    } ?: ByteArray(0)
                    val headers = linkedMapOf<String, MutableList<String>>()
                    response.headers.names().forEach { name ->
                        headers[name.lowercase()] = response.headers.values(name).toMutableList()
                    }
                    TimetableHttpResponse(response.code, headers, body)
                }
            } catch (failure: TimetableTransportException) {
                throw failure
            } catch (_: SocketTimeoutException) {
                throw TimetableTransportException(TimetableTransportFailure.TIMEOUT)
            } catch (_: SSLException) {
                throw TimetableTransportException(TimetableTransportFailure.TLS)
            } catch (_: IOException) {
                throw TimetableTransportException(TimetableTransportFailure.NETWORK)
            } catch (_: Exception) {
                throw TimetableTransportException(TimetableTransportFailure.NETWORK)
            }
        }
}

/** 课表业务会话 Cookie；只允许 app.upc.edu.cn。 */
@kotlinx.serialization.Serializable
data class TimetableCookie(
    val name: String,
    val value: String,
    val domain: String = "app.upc.edu.cn",
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = true,
    val expiresAtEpochSeconds: Long? = null,
) {
    init {
        require(name.matches(Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,256}"))) { "非法 Cookie 名" }
        require(value.length <= 4096 && value.all { it in '\u0021'..'\u007e' }) { "非法 Cookie 值" }
        require(domain == UpcTimetableHost.APP_HOST) { "课表 Cookie 只能属于 app.upc.edu.cn" }
        require(path.startsWith("/") && path.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "非法 Cookie 路径"
        }
        require(expiresAtEpochSeconds == null || expiresAtEpochSeconds > 0) { "非法 Cookie 到期时间" }
    }

    fun matches(host: String, path: String, secure: Boolean, nowEpochSeconds: Long): Boolean {
        if (host != domain) return false
        if (this.secure && !secure) return false
        if (expiresAtEpochSeconds != null && expiresAtEpochSeconds <= nowEpochSeconds) return false
        if (this.path == "/") return true
        if (path == this.path) return true
        if (!path.startsWith(this.path)) return false
        return this.path.endsWith("/") || path[this.path.length] == '/'
    }

    override fun toString(): String =
        "TimetableCookie(name=$name, domain=$domain, path=$path, value=[REDACTED])"
}

internal object UpcTimetableHost {
    const val APP_HOST = "app.upc.edu.cn"
    const val INDEX_URL = "https://app.upc.edu.cn/timetable/wap/default/get-index"
    const val WEEK_URL = "https://app.upc.edu.cn/timetable/wap/default/get-datatmp"
    const val PAGE_URL = "https://app.upc.edu.cn/site/weekschedule/index"
}

class TimetableApiException(
    val code: TimetableErrorCode,
    val network: Boolean = false,
) : Exception(null, null, false, false)

/** 固定主机、固定路径的只读课表客户端；不做整学期遍历或自动轮询。 */
class TimetableApi internal constructor(
    private val transport: TimetableHttpTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor() : this(OkHttpTimetableTransport())

    suspend fun getIndex(cookies: List<TimetableCookie>): TimetableIndex =
        try {
            TimetableProtocol.parseIndex(
                execute(
                    method = "GET",
                    url = UpcTimetableHost.INDEX_URL,
                    cookies = cookies,
                ),
            )
        } catch (failure: TimetableParseException) {
            throw TimetableApiException(failure.code)
        }

    suspend fun getWeek(
        cookies: List<TimetableCookie>,
        term: TimetableTerm,
        week: Int,
    ): TimetableWeek {
        val body = listOf(
            "year" to term.year,
            "term" to term.term,
            "week" to week.toString(),
            "type" to "2",
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        return try {
            TimetableProtocol.parseWeek(
                execute(
                    method = "POST",
                    url = UpcTimetableHost.WEEK_URL,
                    cookies = cookies,
                    body = body,
                    contentType = true,
                ),
                term,
                week,
            )
        } catch (failure: TimetableParseException) {
            throw TimetableApiException(failure.code)
        }
    }

    private suspend fun execute(
        method: String,
        url: String,
        cookies: List<TimetableCookie>,
        body: String? = null,
        contentType: Boolean = false,
    ): String {
        val now = clock() / 1000
        val requestPath = runCatching { java.net.URI(url).path }
            .getOrNull()
            .orEmpty()
            .ifEmpty { "/" }
        val cookieHeader = cookies
            .filter {
                it.matches(
                    UpcTimetableHost.APP_HOST,
                    requestPath,
                    secure = true,
                    nowEpochSeconds = now,
                )
            }
            .joinToString("; ") { "${it.name}=${it.value}" }
        val headers = buildMap {
            put("Accept", "application/json, text/plain, */*")
            put("X-Requested-With", "XMLHttpRequest")
            put("Referer", UpcTimetableHost.PAGE_URL)
            if (cookieHeader.isNotEmpty()) put("Cookie", cookieHeader)
            if (contentType) put("Content-Type", "application/x-www-form-urlencoded")
        }
        val response = try {
            transport.execute(TimetableHttpRequest(method, url, headers, body))
        } catch (_: TimetableTransportException) {
            throw TimetableApiException(TimetableErrorCode.UPSTREAM_ERROR, network = true)
        }
        when (response.status) {
            307, 308 -> throw TimetableApiException(TimetableErrorCode.UPSTREAM_ERROR)
            in 300..399 -> throw TimetableApiException(TimetableErrorCode.AUTH_REQUIRED)
            401 -> throw TimetableApiException(TimetableErrorCode.AUTH_REQUIRED)
            403 -> throw TimetableApiException(TimetableErrorCode.UPSTREAM_ERROR)
            200 -> Unit
            else -> throw TimetableApiException(TimetableErrorCode.UPSTREAM_ERROR)
        }
        val mediaType = response.header("Content-Type").orEmpty().lowercase()
        if (!mediaType.contains("application/json")) {
            throw TimetableApiException(TimetableErrorCode.SCHEMA_CHANGED)
        }
        val text = response.body.toString(Charsets.UTF_8)
        if (response.body.size > 2 * 1024 * 1024) {
            throw TimetableApiException(TimetableErrorCode.RESPONSE_TOO_LARGE)
        }
        return text
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
