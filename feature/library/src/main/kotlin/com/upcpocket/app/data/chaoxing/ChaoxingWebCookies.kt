package com.upcpocket.app.data.chaoxing

import android.webkit.CookieManager
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 清理应用 WebView 中的 Chaoxing 会话 Cookie。
 *
 * 只在用户退出、账号换号/删号或会话失效时调用；不触碰系统浏览器、默认 Edge 或
 * 其他站点 Cookie。CookieManager 是应用级的，因此按已用 host/path 逐条过期，
 * 并保留可观察的完成屏障。
 */
object ChaoxingWebCookies {
    private val hosts = listOf(
        "https://office.chaoxing.com",
        "https://reserve.chaoxing.com",
        "https://passport2.chaoxing.com",
        "https://passport2-api.chaoxing.com",
    )

    private val paths = listOf(
        "/",
        "/login",
        "/data/apps",
        "/front/third/apps/seat",
        "/front/third/apps/seat/index",
        "/front/third/apps/seatengine",
        "/front/third/apps/seatengine/index",
        "/front/web/apps/reservepc",
        "/front/web/apps/reservepc/index",
    )

    private data class Scope(val host: String, val path: String) {
        val url: String get() = "https://$host$path"
    }

    private val scopes = hosts.flatMap { base ->
        val host = URI(base).host ?: error("Chaoxing Cookie 作用域缺少 host")
        paths.map { path -> Scope(host, path) }
    }

    internal interface CookieJar {
        fun getCookie(url: String): String?
        fun setCookie(url: String, cookie: String)
        fun flush()
    }

    private object AndroidCookieJar : CookieJar {
        private val manager get() = CookieManager.getInstance()
        override fun getCookie(url: String): String? = manager.getCookie(url)
        override fun setCookie(url: String, cookie: String) = manager.setCookie(url, cookie)
        override fun flush() = manager.flush()
    }

    /** 同步清理；账号失效协调应使用 [clearAllAndAwait]。 */
    fun clearAll() {
        clearScopes(AndroidCookieJar)
    }

    suspend fun clearAllAndAwait(timeoutMillis: Long = 3_000): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val jar = AndroidCookieJar
            val names = clearScopes(jar)
            if (names.isEmpty()) return@withContext true
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
            var confirmed = false
            while (!confirmed && android.os.SystemClock.elapsedRealtime() < deadline) {
                val remaining = collectNames(jar)
                confirmed = names.none { it in remaining }
                if (!confirmed) delay(50)
            }
            confirmed
        }

    internal fun clearScopes(jar: CookieJar): Set<String> {
        val observed = linkedMapOf<String, MutableSet<Scope>>()
        scopes.forEach { scope ->
            cookieNames(jar.getCookie(scope.url).orEmpty()).forEach { name ->
                observed.getOrPut(name) { linkedSetOf() }.add(scope)
            }
        }
        observed.forEach { (name, nameScopes) ->
            nameScopes.forEach { scope ->
                deletionHeaders(name, scope).forEach { header ->
                    jar.setCookie(scope.url, header)
                }
            }
        }
        jar.flush()
        return observed.keys
    }

    internal fun collectNames(jar: CookieJar): Set<String> =
        scopes.asSequence()
            .flatMap { scope -> cookieNames(jar.getCookie(scope.url).orEmpty()).asSequence() }
            .toSet()

    private fun deletionHeaders(name: String, scope: Scope): List<String> {
        val common = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; " +
            "Path=${scope.path}; Secure"
        return listOf(
            common,
            "$common; Domain=${scope.host}",
            "$common; Domain=.chaoxing.com",
        )
    }

    internal fun cookieNames(header: String): Set<String> = header.split(';')
        .mapNotNull { part ->
            part.trim().substringBefore('=', "").trim().takeIf { it.isNotEmpty() }
        }
        .toSet()

    /** 只检查 Cookie 名，不读取或返回任何值。 */
    fun hasSessionCookie(header: String): Boolean {
        val names = cookieNames(header)
        return "oa_uid" in names && "oa_deptid" in names
    }
}
