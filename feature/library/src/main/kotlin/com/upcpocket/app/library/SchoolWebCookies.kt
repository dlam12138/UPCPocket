package com.upcpocket.app.library

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 清理 UPC 学校业务 WebView Cookie。
 *
 * 只在用户明确退出学校登录、关闭“记住登录”或统一账号换号/删号时调用；不能在某个
 * 业务的登录页成功或销毁时全局清理，否则会把图书馆等仍在使用中的会话一起踢掉。
 *
 * 这里是按域名逐条过期，而不是 `removeAllCookies()`：应用里可能还有非学校站点的
 * Cookie，且系统 CookieManager 是应用级的。换号清理必须等待可观察的完成结果，
 * 失败时账号层保持“清理中”，不能提前允许新账号登录。
 */
object SchoolWebCookies {
    private val hosts = listOf(
        "https://app.upc.edu.cn",
        "https://cas.upc.edu.cn",
        "https://i.upc.edu.cn",
        "https://libspace.upc.edu.cn",
    )

    private val paths = listOf(
        "/", "/site", "/uc", "/cas", "/libseat", "/remote", "/rest",
    )

    fun clearAllSchoolCookies() {
        val manager = CookieManager.getInstance()
        hosts.forEach { base -> clearHost(manager, base) }
        manager.flush()
    }

    /**
     * 清 Cookie 并在有限时间内确认学校域名不再返回 Cookie 名。
     *
     * CookieManager.flush() 没有完成回调，因此用逐域名读取作为可观察的屏障；
     * 超时返回 false，账号层会保留清理中状态并允许用户重试。
     */
    suspend fun clearAllSchoolCookiesAndAwait(timeoutMillis: Long = 3_000): Boolean =
        withContext<Boolean>(Dispatchers.Main.immediate) {
            val manager = CookieManager.getInstance()
            try {
                hosts.forEach { base -> clearHost(manager, base) }
                manager.flush()
            } catch (_: Exception) {
                return@withContext false
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
            var confirmed = false
            while (!confirmed && android.os.SystemClock.elapsedRealtime() < deadline) {
                val remaining = hosts.any { base ->
                    cookieNames(manager.getCookie("$base/").orEmpty()).isNotEmpty()
                }
                if (!remaining) {
                    confirmed = true
                } else {
                    delay(50)
                }
            }
            confirmed
        }

    private fun clearHost(manager: CookieManager, base: String) {
        val header = manager.getCookie("$base/").orEmpty()
        if (header.isBlank()) return
        val names = cookieNames(header)
        names.forEach { name ->
            paths.forEach { path ->
                manager.setCookie(base, "$name=; Max-Age=0; Path=$path")
            }
        }
    }

    internal fun cookieNames(header: String): Set<String> = header.split(';')
        .mapNotNull { part ->
            part.trim().substringBefore('=', "").trim().takeIf { it.isNotEmpty() }
        }
        .toSet()
}
