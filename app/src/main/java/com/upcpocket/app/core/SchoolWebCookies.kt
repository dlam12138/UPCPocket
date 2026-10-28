package com.upcpocket.app.core

import android.webkit.CookieManager

/**
 * 清理 UPC 学校业务 WebView Cookie。
 *
 * 只在用户明确“退出学校登录/关闭记住登录”时调用；**不能**在某个业务的登录页
 * 成功或销毁时全局清理，否则会把另一个业务（课表/图书馆）的会话一起踢掉。
 *
 * 这里是按域名逐条过期，而不是 `removeAllCookies()`：应用里可能还有非学校站点的
 * Cookie，且系统 CookieManager 是应用级的。
 */
object SchoolWebCookies {
    private val hosts = listOf(
        "https://app.upc.edu.cn",
        "https://cas.upc.edu.cn",
        "https://i.upc.edu.cn",
        "https://libspace.upc.edu.cn",
        // 校园卡平台的登录页也在 WebView 里打开，它自己的 Cookie 同样属于学校登录。
        "https://mb-xyk.upc.edu.cn",
    )

    private val paths = listOf(
        "/", "/site", "/site/weekschedule", "/uc", "/cas", "/libseat", "/remote", "/rest",
        // 校园卡平台自己的路径前缀
        "/plat", "/plat/", "/berserker-auth", "/berserker-base", "/berserker-app",
    )

    fun clearAllSchoolCookies() {
        val manager = CookieManager.getInstance()
        hosts.forEach { base -> clearHost(manager, base) }
        manager.flush()
    }

    private fun clearHost(manager: CookieManager, base: String) {
        val header = manager.getCookie("$base/").orEmpty()
        if (header.isBlank()) return
        val names = header.split(';')
            .mapNotNull { part -> part.trim().substringBefore('=', "").trim().takeIf { it.isNotEmpty() } }
            .distinct()
        names.forEach { name ->
            paths.forEach { path ->
                manager.setCookie(base, "$name=; Max-Age=0; Path=$path")
            }
        }
    }
}
