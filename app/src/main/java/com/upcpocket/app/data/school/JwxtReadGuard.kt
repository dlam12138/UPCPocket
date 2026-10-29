package com.upcpocket.app.data.school

import java.net.URI

/**
 * 「读取当前页」的一次性授权。
 *
 * - 只接受教务业务页（精确 host、scheme、端口、路径前缀）；
 * - 每次读取一个代次，迟到/重复回调一律丢弃；
 * - 离开页面或导航即作废；
 * - 超时按**这次读取**计算，不按打开页面的时间。
 */
internal class JwxtReadGuard(
    private val timeoutMs: Long = 15_000,
    private val maxChars: Int = JwxtScheduleProtocol.MAX_PAYLOAD_CHARS,
) {
    private data class Ticket(val id: String, val origin: String, val startedAt: Long)

    private var ticket: Ticket? = null

    fun begin(url: String, id: String, now: Long): Boolean {
        val origin = readableOrigin(url) ?: return false
        if (ticket != null) return false
        ticket = Ticket(id, origin, now)
        return true
    }

    /** 校验并消费本次读取；同一代次只能成功一次。 */
    fun accept(id: String, currentUrl: String, now: Long, size: Int): Boolean {
        val active = ticket ?: return false
        if (active.id != id) return false
        ticket = null
        if (now - active.startedAt > timeoutMs) return false
        if (size <= 0 || size > maxChars) return false
        val origin = readableOrigin(currentUrl) ?: return false
        return origin == active.origin
    }

    fun isActive(id: String, now: Long): Boolean {
        val active = ticket ?: return false
        if (active.id != id) return false
        if (now - active.startedAt > timeoutMs) {
            ticket = null
            return false
        }
        return true
    }

    fun cancel() {
        ticket = null
    }

    companion object {
        /** 返回 `scheme://host:port`；不是教务业务页时返回 null。 */
        fun readableOrigin(url: String): String? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.rawUserInfo != null) return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (host != "jwxt.upc.edu.cn") return null
            val port = uri.port
            val defaultPort = when (scheme) {
                "https" -> 443
                "http" -> 80
                else -> return null
            }
            if (port != -1 && port != defaultPort) return null
            val path = uri.path.orEmpty()
            if (!path.startsWith("/jsxsd/")) return null
            // 入口本身只是一个跳 CAS 的脚本壳，不能当作可读页面。
            if (path == "/jsxsd/" || path == "/jsxsd") return null
            return "$scheme://$host:$defaultPort"
        }
    }
}
