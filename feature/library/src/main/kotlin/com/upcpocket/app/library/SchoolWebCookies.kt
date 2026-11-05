package com.upcpocket.app.library

import android.webkit.CookieManager
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 清理 UPC 学校业务 WebView Cookie。
 *
 * 只在用户明确退出学校登录、关闭“记住登录”或统一账号换号/删号时调用；不能在某个
 * 业务的登录页成功或销毁时全局清理，否则会把图书馆等仍在使用中的会话一起踢掉。
 *
 * 这里按已核实的学校登录/回调路径和 UPC 域作用域逐条过期，而不是
 * `removeAllCookies()`：应用里可能还有非学校站点 Cookie，且系统 CookieManager 是
 * 应用级的。只读根路径无法发现 `Path=/cas`、`/libseat/` 等 Cookie，因此完成检查
 * 必须查询同样一组路径和作用域，不能以根路径为空作为成功。
 */
object SchoolWebCookies {
    private val hosts = listOf(
        "https://cas.upc.edu.cn",
        "https://libspace.upc.edu.cn",
        "https://app.upc.edu.cn",
        "https://i.upc.edu.cn",
        "https://upc.edu.cn",
    )

    /**
     * 已核实入口及其父路径：
     * - CAS `/cas/login` 与登录 iframe `/cas/cas/login-*.html`；
     * - 图书馆 `/libseat/`、`/remote/static/sso/login`、
     *   `/remote/static/casAuth/getServiceByVerifyTicket/casLogin`；
     * - 空闲教室 `/freeclass/...` 与常见学校平台 `/site`、`/uc`。
     */
    private val paths = listOf(
        "/",
        "/cas",
        "/cas/",
        "/cas/login",
        "/cas/cas",
        "/cas/cas/",
        "/cas/cas/login-mobile.html",
        "/cas/cas/login-normal.html",
        "/libseat",
        "/libseat/",
        "/remote",
        "/remote/",
        "/remote/static",
        "/remote/static/",
        "/remote/static/sso",
        "/remote/static/sso/login",
        "/remote/static/casAuth",
        "/remote/static/casAuth/getServiceByVerifyTicket",
        "/remote/static/casAuth/getServiceByVerifyTicket/casLogin",
        "/site",
        "/site/",
        "/uc",
        "/uc/",
        "/freeclass",
        "/freeclass/",
        "/freeclass/wap",
        "/freeclass/wap/default",
        "/freeclass/wap/default/all",
    )

    private data class Scope(val baseUrl: String, val host: String, val path: String) {
        val url: String get() = baseUrl + path
    }

    private val scopes = hosts.flatMap { baseUrl ->
        val host = URI(baseUrl).host ?: error("学校 Cookie 作用域缺少 host")
        paths.map { path -> Scope(baseUrl, host, path) }
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

    /**
     * 同步清理。用于旧调用点；统一账号失效协调应使用
     * [clearAllSchoolCookiesAndAwait] 并以 false 保持清理中。
     */
    fun clearAllSchoolCookies() {
        clearScopes(AndroidCookieJar)
    }

    /**
     * 清 Cookie 并在有限时间内确认同一组路径/域作用域不再返回被清理的 Cookie 名。
     *
     * CookieManager.flush() 没有完成回调；用逐 scope 读取作为可观察屏障，超时返回
     * false。只清 UPC 学校主机和 `upc.edu.cn` 域 Cookie，不触碰其他站点。
     */
    suspend fun clearAllSchoolCookiesAndAwait(timeoutMillis: Long = 3_000): Boolean =
        withContext<Boolean>(Dispatchers.Main.immediate) {
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

    /** 返回本次发现的 Cookie 名；测试与设备检查只使用合成值。 */
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

    /** 只读检查；用于确认完成屏障和测试。 */
    internal fun collectNames(jar: CookieJar): Set<String> =
        scopes.asSequence()
            .flatMap { scope -> cookieNames(jar.getCookie(scope.url).orEmpty()).asSequence() }
            .toSet()

    private fun deletionHeaders(name: String, scope: Scope): List<String> {
        val common = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; " +
            "Path=${scope.path}; Secure; HttpOnly"
        return listOf(
            // Host-only Cookie：URL 的 host 必须保持原样。
            common,
            // 显式设置到当前 host 的域 Cookie（部分 WebView 会规范化 Domain）。
            "$common; Domain=${scope.host}",
            // 学校父域 Cookie。只作用于 upc.edu.cn，不触碰其他站点。
            "$common; Domain=.upc.edu.cn",
        )
    }

    /**
     * 解析 Set-Cookie/Get-Cookie 形式的 name 列表。只做名称提取，不读取值。
     */
    internal fun cookieNames(header: String): Set<String> = header.split(';')
        .mapNotNull { part ->
            part.trim().substringBefore('=', "").trim().takeIf { it.isNotEmpty() }
        }
        .toSet()
}
