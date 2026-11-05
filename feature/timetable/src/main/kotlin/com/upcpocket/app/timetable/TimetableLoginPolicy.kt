package com.upcpocket.app.timetable

import com.upcpocket.app.platform.diagnostics.DiagnosticDestination
import java.net.URI

/**
 * The timetable web login may only navigate to the official CAS form and the
 * two app endpoints that complete the timetable service callback.
 *
 * Subresources are not routed through this policy; only document navigations
 * are. The policy deliberately does not include any Chaoxing host: the
 * timetable and the library use different authentication chains.
 */
internal object TimetableLoginPolicy {
    private const val CAS_HOST = "cas.upc.edu.cn"
    private const val APP_HOST = "app.upc.edu.cn"

    fun isAllowed(raw: String): Boolean {
        if (raw == "about:blank") return true
        return isAllowedEntry(raw)
    }

    /** A dialog entry must be a real official page, never about:blank. */
    fun isAllowedEntry(raw: String): Boolean {
        if (raw == "about:blank") return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        if (path.isEmpty() || path.contains("..") || path.contains('\\')) return false
        return when (host) {
            CAS_HOST -> path.startsWith("/cas/")
            APP_HOST -> path == "/a_upc/api/cas/login" ||
                path.startsWith("/site/weekschedule/") ||
                path.startsWith("/timetable/wap/default/")
            else -> false
        }
    }

    fun host(raw: String): String? =
        runCatching { URI(raw).host?.takeIf { it.isNotBlank() } }.getOrNull()

    /** Coarse fixed destination for diagnostics; never the raw URL. */
    fun destination(raw: String): DiagnosticDestination {
        if (raw == "about:blank") return DiagnosticDestination.UNKNOWN_DESTINATION
        val uri = runCatching { URI(raw) }.getOrNull() ?: return DiagnosticDestination.UNKNOWN_DESTINATION
        if (!uri.scheme.equals("https", true)) return DiagnosticDestination.UNKNOWN_DESTINATION
        val path = uri.path.orEmpty()
        return when (uri.host?.lowercase()) {
            CAS_HOST -> if (path.endsWith("login-mobile.html")) {
                DiagnosticDestination.CAS_MOBILE
            } else {
                DiagnosticDestination.CAS_LOGIN
            }
            APP_HOST -> when {
                path == "/a_upc/api/cas/login" -> DiagnosticDestination.APP_CALLBACK
                path.startsWith("/site/weekschedule/") -> DiagnosticDestination.APP_PAGE
                path.startsWith("/timetable/wap/default/") -> DiagnosticDestination.APP_API
                else -> DiagnosticDestination.OTHER
            }
            else -> DiagnosticDestination.UNKNOWN_DESTINATION
        }
    }
}
