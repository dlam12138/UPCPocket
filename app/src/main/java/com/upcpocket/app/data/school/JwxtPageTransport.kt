package com.upcpocket.app.data.school

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

/** Transport for user-navigated teaching pages/resources, never CAS or password POSTs. */
internal fun isJwxtPageUrl(address: String): Boolean {
    val uri = runCatching { URI(address) }.getOrNull() ?: return false
    return uri.rawUserInfo == null && uri.host.equals("jwxt.upc.edu.cn", true) &&
        ((uri.scheme == "https" && uri.port in setOf(-1, 443)) ||
            (uri.scheme == "http" && uri.port in setOf(-1, 80))) &&
        uri.path.startsWith("/jsxsd/") && uri.normalize().path.startsWith("/jsxsd/")
}

internal data class JwxtPageResponse(
    val mime: String, val encoding: String?, val status: Int,
    val headers: Map<String, String>, val bytes: ByteArray,
)

/** Platform TLS is kept. Redirects never send the business Cookie to another origin. */
internal fun fetchJwxtResource(
    address: String,
    requestHeaders: Map<String, String>,
    getCookie: (String) -> String?,
    setCookie: (String, String) -> Unit,
    allowed: (String) -> Boolean = ::isJwxtPageUrl,
): JwxtPageResponse {
    var current = address
    val visited = mutableSetOf<String>()
    repeat(8) {
        if (!allowed(current)) throw LoginPageException("教务会话需要重新认证，请重新打开入口")
        if (!visited.add(current)) throw LoginPageException("教务页面重复跳转，请重新打开入口")
        val connection = URL(current).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            requestHeaders.filterKeys { it.lowercase() in setOf("accept", "accept-language", "user-agent", "referer", "x-requested-with") }
                .forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.setRequestProperty("Accept-Encoding", "identity")
            getCookie(current)?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
            val status = connection.responseCode
            connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
                .flatMap { it.value.orEmpty() }.forEach { setCookie(current, it) }
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")?.takeIf { it.isNotBlank() }
                    ?: throw LoginPageException("教务页面缺少跳转地址")
                val next = runCatching { URI(current).resolve(location).toString() }.getOrElse {
                    throw LoginPageException("教务页面返回无效跳转")
                }
                if (URI(current).scheme == "https" && URI(next).scheme != "https") {
                    throw LoginPageException("教务资源试图降级连接，已停止加载")
                }
                current = next
            } else {
                if (status !in 200..299 && status !in 400..599) throw LoginPageException("教务页面返回 HTTP $status")
                val type = connection.contentType.orEmpty()
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val bytes = stream?.let { source ->
                    val encoding = connection.contentEncoding.orEmpty()
                    val input = when {
                        encoding.equals("gzip", true) -> GZIPInputStream(source)
                        encoding.isBlank() || encoding.equals("identity", true) -> source
                        else -> { source.close(); throw LoginPageException("教务页面使用了不支持的压缩格式") }
                    }
                    input.use {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            if (out.size() + count > 8 * 1024 * 1024) throw LoginPageException("教务资源过大，已停止加载")
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                } ?: byteArrayOf()
                val headers = connection.headerFields.entries.filter { (key, _) ->
                    key != null && key.lowercase() !in setOf("set-cookie", "content-length", "content-encoding", "transfer-encoding", "connection", "keep-alive")
                }.associate { it.key!! to it.value.joinToString(", ") }
                val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                return JwxtPageResponse(type.substringBefore(';').ifBlank { "application/octet-stream" }, charset, status, headers, bytes)
            }
        } finally { connection.disconnect() }
    }
    throw LoginPageException("教务页面跳转次数过多")
}
