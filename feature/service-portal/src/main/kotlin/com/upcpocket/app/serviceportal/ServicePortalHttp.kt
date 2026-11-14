package com.upcpocket.app.serviceportal

import com.upcpocket.app.upcauth.UpcServicePortalCookie
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class ServicePortalEndpoint(val path: String) {
    CATALOG("/api/apps/list"),
    GUIDE("/api/apps/detail"),
    TODOS("/site/my-task/todo"),
    APPLICATIONS("/site/process/inst-list"),
    SESSION_CHECK("/site/message/count"),
}

internal class ServicePortalSession(
    cookies: List<UpcServicePortalCookie>,
    val owner: ServicePortalOwner,
) : CookieJar {
    private val values = mutableListOf<Cookie>()

    init {
        val nowSeconds = System.currentTimeMillis() / 1000
        try {
            cookies.asSequence()
                .filter { it.domain == UpcServicePortalCookie.HOST }
                .filter { cookie -> cookie.expiresAtEpochSeconds?.let { it > nowSeconds } != false }
                .forEach { cookie ->
                    val builder = Cookie.Builder()
                        .name(cookie.name)
                        .value(cookie.value)
                        .path(cookie.path)
                    if (cookie.hostOnly) builder.hostOnlyDomain(UpcServicePortalCookie.HOST)
                    else builder.domain(UpcServicePortalCookie.HOST)
                    if (cookie.secure) builder.secure()
                    if (cookie.httpOnly) builder.httpOnly()
                    cookie.expiresAtEpochSeconds?.let { seconds ->
                        builder.expiresAt(seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000)
                    }
                    values += builder.build()
                }
        } catch (_: IllegalArgumentException) {
            throw ServicePortalException(ServicePortalFailure.PROTOCOL)
        }
        if (values.isEmpty()) throw ServicePortalException(ServicePortalFailure.PROTOCOL)
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.scheme != "https" || url.host != UpcServicePortalCookie.HOST) return
        cookies.asSequence()
            .filter { it.domain == UpcServicePortalCookie.HOST }
            .forEach { cookie ->
                values.removeAll { it.name == cookie.name && it.path == cookie.path }
                values += cookie
            }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        if (url.scheme == "https" && url.host == UpcServicePortalCookie.HOST) {
            values.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        } else {
            emptyList()
        }

    @Synchronized
    fun clear() = values.clear()

    override fun toString(): String = "ServicePortalSession([REDACTED])"
}

internal interface ServicePortalDataSource {
    suspend fun catalog(session: ServicePortalSession?): ServicePortalCatalog
    suspend fun guide(session: ServicePortalSession?, item: ServicePortalItem): ServicePortalGuide
    suspend fun todos(session: ServicePortalSession, page: Int, pageSize: Int): ServicePortalDataPage<ServicePortalTodo>
    suspend fun applications(session: ServicePortalSession, page: Int, pageSize: Int): ServicePortalDataPage<ServicePortalApplication>
}

/** Fixed GET-only business surface. No arbitrary host, path, URL, or method is accepted. */
internal class ServicePortalApi(
    private val baseClient: OkHttpClient = DEFAULT_CLIENT,
) : ServicePortalDataSource {
    override suspend fun catalog(session: ServicePortalSession?): ServicePortalCatalog =
        ServicePortalProtocol.catalog(get(session, ServicePortalEndpoint.CATALOG), scope = if (session == null) ServicePortalScope.PUBLIC else ServicePortalScope.ACCOUNT)

    override suspend fun guide(session: ServicePortalSession?, item: ServicePortalItem): ServicePortalGuide =
        ServicePortalProtocol.guide(
            get(session, ServicePortalEndpoint.GUIDE, mapOf("id" to item.detailId.toString(), "userview" to "1")),
            item,
            if (session == null) ServicePortalScope.PUBLIC else ServicePortalScope.ACCOUNT,
        )

    override suspend fun todos(
        session: ServicePortalSession,
        page: Int,
        pageSize: Int,
    ): ServicePortalDataPage<ServicePortalTodo> = ServicePortalProtocol.todos(
        get(session, ServicePortalEndpoint.TODOS, personalParams(page, pageSize)),
        page,
        pageSize,
    )

    override suspend fun applications(
        session: ServicePortalSession,
        page: Int,
        pageSize: Int,
    ): ServicePortalDataPage<ServicePortalApplication> = ServicePortalProtocol.applications(
        get(session, ServicePortalEndpoint.APPLICATIONS, personalParams(page, pageSize)),
        page,
        pageSize,
    )

    internal fun requestFor(endpoint: ServicePortalEndpoint, parameters: Map<String, String> = emptyMap()): Request {
        val url = (BASE_URL + endpoint.path).toHttpUrl().newBuilder().apply {
            parameters.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
    }

    private suspend fun get(
        session: ServicePortalSession?,
        endpoint: ServicePortalEndpoint,
        parameters: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val client = baseClient.newBuilder()
            .cookieJar(session ?: CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        try {
            client.newCall(requestFor(endpoint, parameters)).execute().use { response ->
                when {
                    response.code == 401 -> throw ServicePortalException(ServicePortalFailure.AUTH_REQUIRED)
                    response.code == 403 -> throw ServicePortalException(ServicePortalFailure.ACCESS_DENIED)
                    response.code in 300..399 -> throw redirectFailure(response.header("Location"))
                    response.code >= 500 -> throw ServicePortalException(ServicePortalFailure.UPSTREAM)
                    response.code != 200 -> throw ServicePortalException(ServicePortalFailure.UPSTREAM)
                }
                val body = response.body ?: throw ServicePortalException(ServicePortalFailure.PROTOCOL)
                val contentType = body.contentType()
                if (contentType?.subtype?.lowercase()?.contains("json") != true || body.contentLength() > MAX_RESPONSE_BYTES) {
                    throw ServicePortalException(ServicePortalFailure.PROTOCOL)
                }
                val source = body.source()
                source.request(MAX_RESPONSE_BYTES + 1)
                if (source.buffer.size > MAX_RESPONSE_BYTES) throw ServicePortalException(ServicePortalFailure.PROTOCOL)
                val text = source.readString(contentType.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                // The parser validates the success envelope and each endpoint's data shape.
                text
            }
        } catch (failure: ServicePortalException) {
            throw failure
        } catch (_: SSLException) {
            throw ServicePortalException(ServicePortalFailure.TLS)
        } catch (_: IOException) {
            throw ServicePortalException(ServicePortalFailure.NETWORK)
        }
    }

    private fun redirectFailure(location: String?): ServicePortalException {
        val target = runCatching { URI(BASE_URL).resolve(location.orEmpty()) }.getOrNull()
        val isLogin = target?.scheme == "https" &&
            ((target.host == UpcServicePortalCookie.HOST && target.path == "/site/login/cas-login") ||
                (target.host == "cas.upc.edu.cn" && target.path == "/cas/login"))
        return ServicePortalException(if (isLogin) ServicePortalFailure.AUTH_REQUIRED else ServicePortalFailure.UPSTREAM)
    }

    internal fun personalParams(page: Int, pageSize: Int): Map<String, String> {
        if (page !in 1..MAX_PAGE || pageSize !in 1..MAX_PAGE_SIZE) {
            throw ServicePortalException(ServicePortalFailure.INVALID_INPUT)
        }
        return linkedMapOf("p" to page.toString(), "page_size" to pageSize.toString(), "status" to "1")
    }

    private companion object {
        const val BASE_URL = "https://${UpcServicePortalCookie.HOST}"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024L
        const val MAX_PAGE = 50
        const val MAX_PAGE_SIZE = 50
        val DEFAULT_CLIENT = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
