package com.upcpocket.app.upcauth

import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticBindingSource
import com.upcpocket.app.platform.diagnostics.DiagnosticCallbackState
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticNavigationFrame
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticPhase
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.DiagnosticServiceParameter
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Result of checking a WebView request before it is allowed to leave the app. */
sealed class UpcCardWebNavigation {
    data object Allow : UpcCardWebNavigation()
    data object Block : UpcCardWebNavigation()

    class Upgrade(val url: String) : UpcCardWebNavigation() {
        override fun toString() = "UpcCardWebNavigation.Upgrade([REDACTED])"
    }

    class Complete(val url: String) : UpcCardWebNavigation() {
        override fun toString() = "UpcCardWebNavigation.Complete([REDACTED])"
    }
}

/**
 * Purpose and path restricted browser policy for the user's manual UPC card login.
 * This is intentionally narrower than [CardAuthOrigins.allowed], which protects the native
 * transport and is not a suitable allow-list for a browser with a full rendering engine.
 */
object UpcCardWebLoginPolicy {
    val entryUrl: String get() = CardAuthOrigins.ENTRY

    fun inspect(
        url: String,
        method: String,
        isMainFrame: Boolean,
        operation: DiagnosticOperation? = null,
    ): UpcCardWebNavigation {
        val decision = inspectRequest(url, method, isMainFrame)
        operation?.let { active ->
            val completed = decision is UpcCardWebNavigation.Complete
            val blocked = decision is UpcCardWebNavigation.Block
            runCatching {
                Diagnostics.record(
                    module = DiagnosticModule.CARD,
                    action = if (blocked) DiagnosticAction.AUTH_STOP else DiagnosticAction.AUTH_STAGE,
                    result = if (blocked) DiagnosticResult.BLOCKED else DiagnosticResult.OK,
                    reason = if (blocked) DiagnosticReason.POLICY else null,
                    phase = DiagnosticPhase.WEBVIEW_NAVIGATION,
                    detail = when (decision) {
                        UpcCardWebNavigation.Allow -> DiagnosticDetail.WEBVIEW_NAVIGATION_ALLOWED
                        UpcCardWebNavigation.Block -> DiagnosticDetail.WEBVIEW_NAVIGATION_BLOCKED
                        is UpcCardWebNavigation.Upgrade -> DiagnosticDetail.WEBVIEW_NAVIGATION_UPGRADED
                        is UpcCardWebNavigation.Complete -> DiagnosticDetail.WEBVIEW_CALLBACK_CONSUMED
                    },
                    operation = active,
                    serviceParameter = serviceParameterState(url),
                    callbackState = if (completed) DiagnosticCallbackState.ACCEPTED
                        else DiagnosticCallbackState.NOT_APPLICABLE,
                    navigationFrame = if (isMainFrame) DiagnosticNavigationFrame.MAIN
                        else DiagnosticNavigationFrame.SUB,
                    bindingSource = if (completed) DiagnosticBindingSource.VERIFIED_WEB
                        else DiagnosticBindingSource.NONE,
                )
            }
        }
        return decision
    }

    private fun inspectRequest(url: String, method: String, isMainFrame: Boolean): UpcCardWebNavigation {
        if (url.length > 16_384 || url.any { it <= '\u001f' || it == '\u007f' }) return UpcCardWebNavigation.Block
        val uri = runCatching { URI(url) }.getOrNull() ?: return UpcCardWebNavigation.Block
        val scheme = uri.scheme?.lowercase() ?: return UpcCardWebNavigation.Block
        val host = uri.host?.lowercase() ?: return UpcCardWebNavigation.Block
        val path = uri.rawPath.orEmpty()
        val requestMethod = method.uppercase()
        if (uri.rawUserInfo != null || uri.isOpaque || host.endsWith('.') || uri.port !in setOf(-1, 80, 443)) {
            return UpcCardWebNavigation.Block
        }

        if (scheme == "http") {
            if (isMainFrame && requestMethod == "GET" && host == CardAuthOrigins.HOST &&
                uri.port in setOf(-1, 80) && path == CALLBACK_PATH
            ) {
                return UpcCardWebNavigation.Upgrade(
                    "https://${CardAuthOrigins.HOST}$path" +
                        (uri.rawQuery?.let { "?$it" } ?: "") +
                        (uri.rawFragment?.let { "#$it" } ?: ""),
                )
            }
            return UpcCardWebNavigation.Block
        }
        if (scheme != "https" || uri.port !in setOf(-1, 443)) return UpcCardWebNavigation.Block

        if (isMainFrame && requestMethod == "GET" && url == entryUrl) return UpcCardWebNavigation.Allow

        if (isKnownResultPath(host, path) && CardCasProtocol.hasLoginResultMarker(url)) {
            return if (isMainFrame && requestMethod == "GET") UpcCardWebNavigation.Complete(url)
            else UpcCardWebNavigation.Block
        }

        if (host == CAS_HOST) {
            // The school's mobile login form can live inside its CAS iframe.
            // Only this exact form endpoint accepts POST, independently of frame type.
            if (path == CAS_LOGIN_PATH && requestMethod in setOf("GET", "POST")) {
                return UpcCardWebNavigation.Allow
            }
            if (!isMainFrame && requestMethod == "GET" && path.startsWith("/cas/")) {
                return UpcCardWebNavigation.Allow
            }
            return UpcCardWebNavigation.Block
        }

        if (host == CardAuthOrigins.HOST) {
            if (path == CALLBACK_PATH && CardCasProtocol.hasQueryParameter(url, "synjones-auth")) {
                return UpcCardWebNavigation.Block
            }
            if (isMainFrame && requestMethod == "GET" && path == CALLBACK_PATH) {
                return UpcCardWebNavigation.Allow
            }
            // Callback and login-transit resources stay on the same verified school origin.
            // A token/ticket result is intercepted above before a business page can render.
            if (!isMainFrame && requestMethod == "GET" &&
                (path.startsWith("/berserker-auth/cas/") || path.startsWith("/plat/"))
            ) {
                return UpcCardWebNavigation.Allow
            }
        }

        return UpcCardWebNavigation.Block
    }

    private fun isKnownResultPath(host: String, rawPath: String): Boolean =
        host == CardAuthOrigins.HOST &&
            (rawPath == "/plat" || rawPath == "/plat/" || rawPath.startsWith("/campus-card/"))

    private fun serviceParameterState(rawUrl: String): DiagnosticServiceParameter {
        val url = rawUrl.toHttpUrlOrNull() ?: return DiagnosticServiceParameter.UNKNOWN
        if (url.host != CAS_HOST || url.encodedPath != CAS_LOGIN_PATH) {
            return DiagnosticServiceParameter.NOT_APPLICABLE
        }
        val values = url.queryParameterValues("service")
        return when {
            values.isEmpty() -> DiagnosticServiceParameter.MISSING
            values.size != 1 -> DiagnosticServiceParameter.DUPLICATE
            else -> DiagnosticServiceParameter.PRESENT
        }
    }

    private const val CAS_HOST = "cas.upc.edu.cn"
    private const val CAS_LOGIN_PATH = "/cas/login"
    private const val CALLBACK_PATH = "/berserker-auth/cas/login/wislearn"
}
