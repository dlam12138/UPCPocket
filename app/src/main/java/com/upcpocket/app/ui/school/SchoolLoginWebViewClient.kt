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
import com.upcpocket.app.data.school.isNativeSchoolAuthUrl
import com.upcpocket.app.data.school.loginHost
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

    fun load(view: WebView, url: String) {
        redirectJob?.cancel()
        if (!allowed(url)) {
            onError("学校登录跳转到了未允许的地址，已停止加载")
            onLoading(false)
            return
        }
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
                    onLoading(false)
                    onError("学校登录连接超时，请重试")
                } else throw e
            } catch (e: Exception) {
                if (!disposed) {
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
            if (request.isForMainFrame || isCasLoginFrame(url)) {
                onLoading(false)
                onError("已停止打开未允许的登录地址（${loginHost(url)}）")
            }
            return true
        }
        if (request.isForMainFrame && request.method == "GET" && nativeGetUrl(url)) {
            load(view, url)
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        clearWatchdog(view)
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
            onError("${loginHost(request.url.toString())} 加载失败（网络错误 ${error.errorCode}），请重试")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame || isCasLoginFrame(request.url.toString())) {
            clearWatchdog(view)
            onLoading(false)
            onError("${loginHost(request.url.toString())} 返回 HTTP ${response.statusCode}，请重试")
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        clearWatchdog(view)
        onLoading(false)
        onError("${loginHost(error.url.orEmpty())} 的安全连接校验失败，未继续登录")
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
