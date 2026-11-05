package com.upcpocket.app.ui.school

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.upcpocket.app.data.school.LoginPageException
import com.upcpocket.app.data.school.SchoolLoginRedirects
import com.upcpocket.app.data.school.fetchLoginPage
import com.upcpocket.app.data.school.isCasLoginFrame
import com.upcpocket.app.data.school.isCasLoginPage
import com.upcpocket.app.data.school.isNativeSchoolAuthUrl
import com.upcpocket.app.data.school.loginHost
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticDestination
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Shared loading/error handling; never bypasses TLS and never reads a password field. */
internal open class SchoolLoginWebViewClient(
    private val scope: CoroutineScope,
    private val allowed: (String) -> Boolean,
    private val onLoading: (Boolean) -> Unit,
    private val onError: (String?) -> Unit,
    private val onResolved: (String) -> Boolean = { false },
    private val nativeGetUrl: (String) -> Boolean = ::isNativeSchoolAuthUrl,
) : WebViewClient() {
    private var redirectJob: Job? = null
    private var watchdog: Runnable? = null
    private var disposed = false
    private var pageStartedAt = 0L
    private var viewportRecorded = false
    private val operationId = Diagnostics.newOperationId()

    fun load(view: WebView, url: String) {
        redirectJob?.cancel()
        if (!allowed(url)) {
            Diagnostics.record(
                module = DiagnosticModule.LIBRARY,
                action = DiagnosticAction.WEBVIEW_NAVIGATION,
                result = DiagnosticResult.BLOCKED,
                reason = DiagnosticReason.POLICY,
                destination = schoolLoginDestination(url),
                operationId = operationId,
            )
            onError("学校登录跳转到了未允许的地址，已停止加载")
            onLoading(false)
            return
        }
        Diagnostics.record(
            module = DiagnosticModule.LIBRARY,
            action = DiagnosticAction.WEBVIEW_START,
            result = DiagnosticResult.PENDING,
            destination = schoolLoginDestination(url),
            operationId = operationId,
        )
        onError(null)
        onLoading(true)
        // Normal GETs to business auth endpoints work through Android's HTTP stack on this
        // device where Chromium resets the same connection. Preserve business Cookies and
        // the server-provided service URL, then let the official CAS page handle the form.
        val userAgent = view.settings.userAgentString
        redirectJob = scope.launch {
            try {
                val target = withTimeout(25_000) {
                    withContext(Dispatchers.IO) {
                        val cookies = CookieManager.getInstance()
                        SchoolLoginRedirects(allowed, nativeGetUrl) { address ->
                            fetchLoginPage(address, userAgent, cookies::getCookie) { origin, cookie ->
                                cookies.setCookie(origin, cookie)
                            }
                        }.resolve(url).also { cookies.flush() }
                    }
                }
                if (disposed) return@launch
                if (onResolved(target.url)) {
                    onLoading(false)
                } else if (target.html != null) {
                    view.loadDataWithBaseURL(target.url, target.html, "text/html", "UTF-8", target.url)
                } else {
                    view.loadUrl(target.url)
                }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException && !disposed) {
                    Diagnostics.record(
                        module = DiagnosticModule.LIBRARY,
                        action = DiagnosticAction.WEBVIEW_MAIN_FRAME_ERROR,
                        result = DiagnosticResult.TIMEOUT,
                        reason = DiagnosticReason.NETWORK,
                        destination = schoolLoginDestination(url),
                        operationId = operationId,
                    )
                    onLoading(false)
                    onError("学校登录连接超时，请重试")
                } else throw e
            } catch (e: Exception) {
                if (!disposed) {
                    Diagnostics.recordException(
                        module = DiagnosticModule.LIBRARY,
                        action = DiagnosticAction.WEBVIEW_MAIN_FRAME_ERROR,
                        result = DiagnosticResult.FAIL,
                        reason = DiagnosticReason.NETWORK,
                        failure = e,
                        operationId = operationId,
                    )
                    onLoading(false)
                    onError(if (e is LoginPageException) e.message else
                        "无法连接 ${loginHost(url)}，请检查网络后重试")
                }
            }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (!allowed(url)) {
            Diagnostics.record(
                module = DiagnosticModule.LIBRARY,
                action = DiagnosticAction.WEBVIEW_NAVIGATION,
                result = DiagnosticResult.BLOCKED,
                reason = DiagnosticReason.POLICY,
                destination = schoolLoginDestination(url),
                operationId = operationId,
            )
            if (request.isForMainFrame || isCasLoginFrame(url)) {
                onLoading(false)
                onError("已停止打开未允许的登录地址（${loginHost(url)}）")
            }
            return true
        }
        if (request.isForMainFrame) {
            Diagnostics.record(
                module = DiagnosticModule.LIBRARY,
                action = DiagnosticAction.WEBVIEW_NAVIGATION,
                result = DiagnosticResult.OK,
                reason = DiagnosticReason.MAIN_FRAME,
                destination = schoolLoginDestination(url),
                operationId = operationId,
            )
        }
        if (request.isForMainFrame && request.method == "GET" && nativeGetUrl(url)) {
            load(view, url)
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        clearWatchdog(view)
        pageStartedAt = System.currentTimeMillis()
        if (!viewportRecorded && url != "about:blank") {
            viewportRecorded = true
            val density = view.resources.displayMetrics.density.coerceAtLeast(1f)
            Diagnostics.record(
                module = DiagnosticModule.LIBRARY,
                action = DiagnosticAction.WEBVIEW_VIEWPORT,
                result = DiagnosticResult.OK,
                reason = DiagnosticReason.VIEWPORT,
                widthDp = (view.width / density).roundToInt(),
                heightDp = (view.height / density).roundToInt(),
                operationId = operationId,
            )
        }
        onError(null)
        onLoading(true)
        watchdog = Runnable {
            if (!disposed) {
                onLoading(false)
                onError("${loginHost(url)} 加载超过 25 秒，可重试；已显示的登录表单仍可操作")
            }
        }.also { view.postDelayed(it, 25_000) }
    }

    override fun onPageFinished(view: WebView, url: String) {
        clearWatchdog(view)
        onLoading(false)
        Diagnostics.record(
            module = DiagnosticModule.LIBRARY,
            action = DiagnosticAction.WEBVIEW_FINISH,
            result = DiagnosticResult.OK,
            destination = schoolLoginDestination(url),
            durationMs = pageStartedAt.takeIf { it > 0 }?.let {
                (System.currentTimeMillis() - it).coerceAtLeast(0)
            },
            operationId = operationId,
        )
        CookieManager.getInstance().flush()
        repairCasViewport(view)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        super.onPageCommitVisible(view, url)
        repairCasViewport(view)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        repairCasViewport(view)
    }

    private fun repairCasViewport(view: WebView) {
        // Redirects from loadDataWithBaseURL may retain the entry URL in callback
        // arguments. Check the executing document's exact origin/path in the script,
        // rather than deciding from that stale URL. Commit/history also cover pages
        // whose load-finished notification is delayed by school subresources.
        if (!disposed) view.evaluateJavascript(CAS_VIEWPORT_REPAIR, null)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame || isCasLoginFrame(request.url.toString())) {
            clearWatchdog(view)
            onLoading(false)
            Diagnostics.record(
                module = DiagnosticModule.LIBRARY,
                action = DiagnosticAction.WEBVIEW_MAIN_FRAME_ERROR,
                result = DiagnosticResult.FAIL,
                reason = DiagnosticReason.NETWORK,
                destination = schoolLoginDestination(request.url.toString()),
                operationId = operationId,
            )
            onError("${loginHost(request.url.toString())} 加载失败（网络错误 ${error.errorCode}），请重试")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame || isCasLoginFrame(request.url.toString())) {
            clearWatchdog(view)
            onLoading(false)
            Diagnostics.record(
                module = DiagnosticModule.LIBRARY,
                action = DiagnosticAction.WEBVIEW_HTTP_ERROR,
                result = DiagnosticResult.FAIL,
                reason = DiagnosticReason.HTTP,
                destination = schoolLoginDestination(request.url.toString()),
                httpStatus = response.statusCode,
                operationId = operationId,
            )
            onError("学校页面暂时不可用，请重试")
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        clearWatchdog(view)
        onLoading(false)
        Diagnostics.record(
            module = DiagnosticModule.LIBRARY,
            action = DiagnosticAction.WEBVIEW_SSL_REJECT,
            result = DiagnosticResult.FAIL,
            reason = DiagnosticReason.TLS,
            operationId = operationId,
        )
        onError("${loginHost(error.url.orEmpty())} 的安全连接校验失败，未继续登录")
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: android.webkit.RenderProcessGoneDetail?,
    ): Boolean {
        clearWatchdog(view)
        onLoading(false)
        Diagnostics.record(
            module = DiagnosticModule.LIBRARY,
            action = DiagnosticAction.WEBVIEW_RENDERER_EXIT,
            result = DiagnosticResult.FAIL,
            reason = DiagnosticReason.RENDERER,
            operationId = operationId,
        )
        onError("登录页渲染进程异常退出；请重新加载。")
        return true
    }

    fun dispose(view: WebView?) {
        disposed = true
        redirectJob?.cancel()
        view?.let(::clearWatchdog)
    }

    private fun clearWatchdog(view: WebView) {
        watchdog?.let { view.removeCallbacks(it) }
        watchdog = null
    }
}

private fun schoolLoginDestination(raw: String): DiagnosticDestination {
    if (raw == "about:blank") return DiagnosticDestination.UNKNOWN
    if (isCasLoginFrame(raw) || isCasLoginPage(raw)) return DiagnosticDestination.CAS_LOGIN
    val host = runCatching { URI(raw).host?.lowercase() }.getOrNull()
    return when (host) {
        "libspace.upc.edu.cn", "app.upc.edu.cn" -> DiagnosticDestination.APP_PAGE
        else -> DiagnosticDestination.OTHER
    }
}

/**
 * Reproduced on this WebView: CAS #loginIframe height=0 despite viewport=720 and
 * two already-loaded inputs. Changing only the iframe height makes the official form visible.
 * Limit repair to the exact CAS document and an actually collapsed/previously repaired iframe.
 */
internal const val CAS_VIEWPORT_REPAIR = """
(function () {
  if (location.origin !== 'https://cas.upc.edu.cn' || location.pathname !== '/cas/login') return;
  if (window.__upcPocketCasResize) { window.__upcPocketCasResize(); return; }
  function repair() {
    var f = document.getElementById('loginIframe');
    var h = Math.round(window.innerHeight);
    if (!f || h < 100 || getComputedStyle(f).display === 'none') return;
    if (f.getBoundingClientRect().height >= 16 && f.dataset.upcPocketAutoHeight !== '1') return;
    f.dataset.upcPocketAutoHeight = '1';
    document.documentElement.style.minHeight = h + 'px';
    document.body.style.minHeight = h + 'px';
    f.style.setProperty('height', h + 'px', 'important');
    f.style.setProperty('min-height', h + 'px', 'important');
    f.style.width = '100%';
  }
  window.__upcPocketCasResize = repair;
  window.addEventListener('resize', repair);
  repair();
  setTimeout(repair, 250);
  setTimeout(repair, 1000);
})();
"""
