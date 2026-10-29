package com.upcpocket.app.ui.ecard

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Card-only GET transport workaround. Password POSTs remain entirely in the official page. */
internal fun cardPageGet(request: WebResourceRequest): WebResourceResponse? {
    val address = request.url.toString()
    val uri = runCatching { URI(address) }.getOrNull() ?: return null
    if (request.method != "GET" || uri.scheme != "https" || uri.host != "mb-xyk.upc.edu.cn" ||
        uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return null
    val connection = URL(address).openConnection() as HttpURLConnection
    return try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        request.requestHeaders.forEach { (key, value) ->
            if (!key.equals("Host", true) && !key.equals("Accept-Encoding", true)) connection.setRequestProperty(key, value)
        }
        val cookies = CookieManager.getInstance()
        cookies.getCookie(address)?.let { connection.setRequestProperty("Cookie", it) }
        val status = connection.responseCode
        if (status in 300..399) return null // Existing restricted navigation owns authentication redirects.
        connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
            .flatMap { it.value.orEmpty() }.forEach { cookies.setCookie(address, it) }
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val bytes = stream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(out.size() + count <= 8 * 1024 * 1024)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: byteArrayOf()
        val type = connection.contentType.orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
        val headers = connection.headerFields.entries.filter { (key, _) ->
            key != null && key.lowercase() !in setOf("set-cookie", "content-length", "content-encoding", "transfer-encoding")
        }.associate { it.key!! to it.value.joinToString(", ") }
        WebResourceResponse(type, "UTF-8", status, "School response", headers, ByteArrayInputStream(bytes))
    } catch (_: Exception) {
        WebResourceResponse("text/plain", "UTF-8", 502, "Connection failed", emptyMap(),
            ByteArrayInputStream("校园卡页面连接失败，请重试".toByteArray()))
    } finally { connection.disconnect() }
}
