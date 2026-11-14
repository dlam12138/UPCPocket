package com.upcpocket.app.shiguang

import com.upcpocket.app.upcauth.UpcShiguangCookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal enum class ShiguangEndpoint(val path: String) {
    ACTIVITIES("/actvMng/actvMngFront!jumpActvCampListPageForWeb.action"),
    PARTICIPATIONS("/actvMng/actvMngFront!jumpActvMyJoinListPageForWeb.action"),
    ACTIVITY_DETAIL("/actvMng/actvMngFront!jumpActvCampViewPageForWeb.action"),
    PRACTICE_DETAIL("/actvMng/actvMngFront!jumpSocialPracticeViewPageForWeb.action"),
    SUMMARY("/reportCardMng/reportCardMngFront!jumpReportCardFirstForWeb.action"),
}

internal class ShiguangSession(cookies: List<UpcShiguangCookie>, val identityVerified: Boolean) : CookieJar {
    private val values = cookies.map { c -> Cookie.Builder().name(c.name).value(c.value)
        .hostOnlyDomain("dekt.upc.edu.cn").path(c.path).apply {
            if (c.secure) secure(); if (c.httpOnly) httpOnly()
            c.expiresAtEpochSeconds?.let { expiresAt(it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000) }
        }.build() }.toMutableList()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.scheme != "https" || url.host != "dekt.upc.edu.cn") return
        cookies.filter { it.domain == "dekt.upc.edu.cn" }.forEach { cookie ->
            values.removeAll { it.name == cookie.name && it.path == cookie.path }
            values.add(cookie)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        if (url.scheme == "https" && url.host == "dekt.upc.edu.cn")
            values.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) } else emptyList()
    @Synchronized fun clear() { values.clear() }
    override fun toString() = "ShiguangSession([REDACTED])"
}

internal interface ShiguangDataSource {
    suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean): ShiguangPage
    suspend fun detail(session: ShiguangSession, item: ShiguangActivity): ShiguangDetail
    suspend fun summary(session: ShiguangSession): ShiguangSummary
}

/** Fixed GET-only surface. No URL/method supplied by a page or model, no write endpoint. */
internal class ShiguangApi(private val client: OkHttpClient = baseClient) : ShiguangDataSource {
    override suspend fun page(session: ShiguangSession, page: Int, keyword: String, mine: Boolean): ShiguangPage {
        require(page in 1..if (mine) 500 else 5000)
        require(keyword.length <= 40 && (keyword.isEmpty() || keyword.isNotBlank()))
        val endpoint = if (mine) ShiguangEndpoint.PARTICIPATIONS else ShiguangEndpoint.ACTIVITIES
        val params = linkedMapOf("page" to page.toString())
        if (!mine) { params["oper"] = "1"; if (keyword.isNotEmpty()) params["searchWord"] = keyword }
        return ShiguangProtocol.list(get(session, endpoint, params), page)
    }
    override suspend fun detail(session: ShiguangSession, item: ShiguangActivity): ShiguangDetail {
        require(item.id.matches(Regex("[0-9]{1,20}")))
        val endpoint = if (item.kind == ShiguangKind.ACTIVITY) ShiguangEndpoint.ACTIVITY_DETAIL else ShiguangEndpoint.PRACTICE_DETAIL
        return ShiguangProtocol.detail(get(session, endpoint, mapOf("actvModel.activityId" to item.id)))
    }
    override suspend fun summary(session: ShiguangSession): ShiguangSummary = ShiguangProtocol.summary(get(session, ShiguangEndpoint.SUMMARY))

    private suspend fun get(session: ShiguangSession, endpoint: ShiguangEndpoint, params: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        if ((endpoint == ShiguangEndpoint.PARTICIPATIONS || endpoint == ShiguangEndpoint.SUMMARY) && !session.identityVerified)
            throw ShiguangException(ShiguangFailure.IDENTITY_UNVERIFIED)
        val url = ("https://dekt.upc.edu.cn" + endpoint.path).toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val client = client.newBuilder().cookieJar(session).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        try {
            client.newCall(Request.Builder().url(url).get()
                .header("Accept", ACCEPT).header("Accept-Language", ACCEPT_LANGUAGE)
                .header("User-Agent", USER_AGENT).build()).execute().use { response ->
                when {
                    response.code == 401 -> throw ShiguangException(ShiguangFailure.AUTH_REQUIRED)
                    response.code in 300..399 -> {
                        val target = response.header("Location").orEmpty()
                        throw ShiguangException(if ("/cas/login" in target || "caslogin.jsp" in target) ShiguangFailure.AUTH_REQUIRED else ShiguangFailure.UPSTREAM)
                    }
                    response.code == 403 -> throw ShiguangException(ShiguangFailure.ACCESS_DENIED)
                    response.code != 200 -> throw ShiguangException(ShiguangFailure.UPSTREAM)
                }
                val body = response.body ?: throw ShiguangException(ShiguangFailure.PROTOCOL)
                val subtype = body.contentType()?.subtype.orEmpty()
                val isJson = subtype.contains("json")
                val limit = if (isJson) 200_000L else MAX_BYTES
                if ((!isJson && !subtype.contains("html")) || body.contentLength() > limit)
                    throw ShiguangException(ShiguangFailure.PROTOCOL)
                val source = body.source()
                source.request(limit + 1)
                if (source.buffer.size > limit) throw ShiguangException(ShiguangFailure.PROTOCOL)
                val html = source.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                if (isJson) {
                    val root = runCatching { Json.parseToJsonElement(html) as? JsonObject }.getOrNull()
                    val sessionOut = (root?.get("sessionOutFlag") as? JsonPrimitive)?.content
                    throw ShiguangException(if (sessionOut.equals("true", ignoreCase = true)) ShiguangFailure.AUTH_REQUIRED else ShiguangFailure.PROTOCOL)
                }
                if (ShiguangProtocol.isLogin(html)) throw ShiguangException(ShiguangFailure.AUTH_REQUIRED)
                html
            }
        } catch (_: SSLException) { throw ShiguangException(ShiguangFailure.TLS) }
        catch (_: IOException) { throw ShiguangException(ShiguangFailure.NETWORK) }
    }
    private companion object {
        const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0"
        const val MAX_BYTES = 3_000_000L
        val baseClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }
}
