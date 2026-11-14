package com.upcpocket.app.upcauth

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only CAS pages render in the WebView; a single business ticket is consumed natively. */
object UpcShiguangWebLoginPolicy {
    val entryUrl: String = UpcShiguangAuthEndpoints.LOGIN_URL

    fun inspect(
        url: String,
        method: String,
        isMainFrame: Boolean,
        kind: ShiguangWebRequestKind,
    ): UpcShiguangWebNavigation {
        if (url.length > 16_384 || url.any { it <= ' ' || it == '\u007f' || it == '\\' }) return blocked()
        val uri = runCatching { URI(url) }.getOrNull() ?: return blocked()
        val path = uri.rawPath ?: return blocked()
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null ||
            path.contains('%') || path.contains("//") || path.split('/').any { it == "." || it == ".." }
        ) return blocked()
        if (method != "GET" && method != "POST") return blocked()

        if (uri.host == UpcShiguangAuthEndpoints.HOST) {
            if (method != "GET" || (kind == ShiguangWebRequestKind.RESOURCE && !isMainFrame)) return blocked()
            val callback = upgradeShiguangTicketCallback(url) ?: return blocked()
            return UpcShiguangWebNavigation.Complete(callback.toString())
        }
        if (uri.scheme != "https" || uri.host != UpcShiguangAuthEndpoints.CAS_HOST) return blocked()
        val parsed = url.toHttpUrlOrNull() ?: return blocked()
        if (path == "/cas/login") {
            // CAS forms can omit the service in their action; supplied service bytes must remain exact.
            val withService = if ("service" in parsed.queryParameterNames) parsed else
                parsed.newBuilder().addQueryParameter("service", UpcShiguangAuthEndpoints.SERVICE).build()
            return if (casService.isCasLoginUrl(withService)) UpcShiguangWebNavigation.Allow else blocked()
        }
        // Only read requests under the fixed CAS root are needed for styles, scripts and challenges.
        if (method == "GET" && path.startsWith("/cas/") && path.length > "/cas/".length &&
            ("service" !in parsed.queryParameterNames ||
                parsed.queryParameterValues("service") == listOf(UpcShiguangAuthEndpoints.SERVICE))
        ) return UpcShiguangWebNavigation.Allow
        return blocked()
    }

    private val casService = UpcCasService(
        UpcShiguangAuthEndpoints.CAS_HOST, UpcShiguangAuthEndpoints.SERVICE,
        UpcShiguangAuthEndpoints.HOST, "/caslogin.jsp", setOf("ticket"),
    )

    private fun blocked() = UpcShiguangWebNavigation.Block
}
