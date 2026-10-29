package com.upcpocket.app.data.school

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Only business GET redirects are resolved here. CAS forms and password submission stay in WebView. */
internal class SchoolLoginRedirects(
    private val allowed: (String) -> Boolean,
    private val businessAuth: (String) -> Boolean,
    private val fetch: (String) -> LoginHttpPage,
) {
    fun resolve(start: String): LoginDestination {
        var current = start
        val visited = mutableSetOf<String>()
        repeat(8) {
            if (!allowed(current)) throw LoginPageException("学校登录跳转到了未允许的地址，已停止加载")
            if (!businessAuth(current)) return LoginDestination(current)
            if (!visited.add(current)) throw LoginPageException("学校登录重复跳转，请重试")
            val page = fetch(current)
            if (page.status in setOf(301, 302, 303, 307, 308)) {
                val location = page.location?.takeIf { it.isNotBlank() }
                    ?: throw LoginPageException("学校登录跳转缺少目标地址")
                current = runCatching { URI(current).resolve(location).toString() }.getOrElse {
                    throw LoginPageException("学校登录返回了无效的跳转地址")
                }
            } else if (page.status in 200..299) {
                return LoginDestination(current, page.html)
            } else {
                throw LoginPageException("${loginHost(current)} 返回 HTTP ${page.status}，请重试")
            }
        }
        throw LoginPageException("学校登录跳转次数过多，请重试")
    }
}

internal data class LoginHttpPage(val status: Int, val location: String? = null, val html: String? = null)
internal data class LoginDestination(val url: String, val html: String? = null)
internal class LoginPageException(message: String) : IOException(message)

/** Observed business authentication routes only, not a general HTTP proxy. */
internal fun isNativeSchoolAuthUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.rawUserInfo != null) return false
    val https = uri.scheme.equals("https", true) && uri.port in setOf(-1, 443)
    val http = uri.scheme.equals("http", true) && uri.port in setOf(-1, 80)
    return when (uri.host?.lowercase()) {
        "libspace.upc.edu.cn" -> https &&
            (uri.path == "/remote/static/sso/login" ||
                uri.path == "/remote/static/casAuth/getServiceByVerifyTicket/casLogin")
        "mb-xyk.upc.edu.cn" -> (https || http) &&
            (uri.path == "/berserker-auth/cas/redirect/wislearn" ||
                uri.path == "/berserker-auth/cas/login/wislearn")
        else -> false
    }
}

/** Error UI deliberately contains no query, fragment, service ticket, Cookie or server response. */
internal fun loginHost(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "学校登录页"

internal fun isCasLoginFrame(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", true) && uri.host.equals("cas.upc.edu.cn", true) &&
        uri.port in setOf(-1, 443) && uri.path in setOf(
            "/cas/cas/login-mobile.html", "/cas/cas/login-normal.html",
        )
}

internal fun isCasLoginPage(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", true) && uri.host.equals("cas.upc.edu.cn", true) &&
        uri.port in setOf(-1, 443) && uri.path == "/cas/login"
}

/** GET only, strict platform TLS, bounded response. Cookies belong exclusively to this app's WebView. */
internal fun fetchLoginPage(
    url: String,
    userAgent: String,
    getCookie: (String) -> String?,
    setCookie: (String, String) -> Unit,
): LoginHttpPage {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", userAgent)
        getCookie(url)?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
        val status = connection.responseCode
        connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
            .flatMap { it.value.orEmpty() }.forEach { setCookie(url, it) }
        val location = connection.getHeaderField("Location")
        val html = if (status in 200..299) {
            if (!connection.contentType.orEmpty().contains("text/html", true)) {
                throw LoginPageException("学校登录入口没有返回登录页面，请重试")
            }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) {
                        throw LoginPageException("学校登录页面过大，已停止加载")
                    }
                    output.write(buffer, 0, count)
                }
                val charset = connection.contentType.orEmpty().substringAfter("charset=", "UTF-8")
                    .substringBefore(';').trim().trim('"')
                output.toByteArray().toString(runCatching { java.nio.charset.Charset.forName(charset) }
                    .getOrDefault(Charsets.UTF_8))
            }
        } else null
        return LoginHttpPage(status, location, html)
    } finally {
        connection.disconnect()
    }
}
