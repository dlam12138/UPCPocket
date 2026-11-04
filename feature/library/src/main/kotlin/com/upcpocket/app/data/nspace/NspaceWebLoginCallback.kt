package com.upcpocket.app.data.nspace

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 只解析学校 nspace 页面回传的 app token。
 *
 * 允许 query 或 hash 路由里的 `token`；只接受固定 libspace 主机和 `/nspace` 路径。
 * 不读取 sessionStorage、Cookie 或页面其他内容，避免把业务 token 当 app token。
 */
object NspaceWebLoginCallback {
    private const val HOST = "libspace.upc.edu.cn"

    fun isAllowedPage(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("https", true) &&
            uri.host.equals(HOST, true) &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            (uri.path == "/nspace" || uri.path?.startsWith("/nspace/") == true)
    }

    fun token(url: String): String? {
        if (!isAllowedPage(url)) return null
        val uri = parse(url) ?: return null
        val candidates = buildList {
            uri.rawQuery?.let(::add)
            uri.rawFragment?.let { fragment ->
                add(fragment.substringAfter('?', ""))
            }
        }
        return candidates.asSequence()
            .flatMap { it.split('&').asSequence() }
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter('=')
            ?.let { decode(it) }
            ?.takeIf { it.isNotBlank() && it.length <= 4096 && it.none { c -> c.isISOControl() } }
    }

    private fun parse(url: String): URI? =
        runCatching { URI(url) }.getOrNull()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
            .getOrDefault("")
}
