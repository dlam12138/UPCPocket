package com.upcpocket.app.ui.ecard

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.upcpocket.app.data.cardhistory.CardHistoryClient
import com.upcpocket.app.data.cardhistory.CardHistoryException
import com.upcpocket.app.data.cardhistory.CardHistoryFailure
import com.upcpocket.app.data.cardhistory.CardHistoryInteractiveAttempt
import com.upcpocket.app.data.cardhistory.CardHistoryWebViewLease
import com.upcpocket.app.data.cardhistory.CardHistoryWebNavigation
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CardHistoryLoginDialog(
    client: CardHistoryClient,
    attempt: CardHistoryInteractiveAttempt,
    syntheticResources: CardHistoryWebResourceProvider? = null,
    onSuccess: () -> Unit,
    onFailure: (CardHistoryFailure?) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var webViewAlive by remember(attempt.id) { mutableStateOf(true) }
    var generation by remember(attempt.id) { mutableIntStateOf(0) }
    var pageLoading by remember(attempt.id) { mutableStateOf(true) }
    var error by remember(attempt.id) { mutableStateOf<String?>(null) }
    var verificationJob by remember(attempt.id) { mutableStateOf<Job?>(null) }
    val resultClaimed = remember(attempt.id) { AtomicBoolean(false) }
    val webViewLease = remember(attempt.id) { CardHistoryLoginWebViewLeaseImpl { webViewAlive = false } }
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val currentOnClose by rememberUpdatedState(onClose)

    fun fail(message: String) {
        pageLoading = false
        error = message
    }

    fun complete(resultUrl: String, view: WebView?) {
        if (!resultClaimed.compareAndSet(false, true)) return
        pageLoading = true
        error = null
        view?.stopLoading()
        verificationJob = scope.launch {
            try {
                client.completeInteractiveLogin(attempt.id, resultUrl)
                currentOnSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val reason = (failure as? CardHistoryException)?.reason
                client.cancelInteractiveLogin(attempt.id)
                currentOnFailure(reason)
            }
        }
    }

    fun blocked(view: WebView?, mainFrame: Boolean) {
        if (!mainFrame) return
        view?.stopLoading()
        fail("学校页面跳转未通过安全检查，请关闭后重试。")
    }

    DisposableEffect(client, attempt.id) {
        onDispose {
            verificationJob?.cancel()
            client.cancelInteractiveLogin(attempt.id)
            webViewLease.dispose()
        }
    }

    Dialog(
        onDismissRequest = currentOnClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = currentOnClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                }
                Text("学校验证", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = currentOnClose) { Text("关闭") }
            }
            Text(
                "请在学校页面完成登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (webViewAlive) {
                    key(generation) {
                        AndroidView<android.view.View>(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                val pageGeneration = generation
                                val started = AtomicBoolean(false)
                                val upgradeScheduled = AtomicBoolean(false)
                                var browserAttached = false
                                val webView = WebView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = false
                                    settings.databaseEnabled = false
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.allowFileAccessFromFileURLs = false
                                    settings.allowUniversalAccessFromFileURLs = false
                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    settings.setSupportMultipleWindows(false)
                                    settings.javaScriptCanOpenWindowsAutomatically = false
                                    settings.safeBrowsingEnabled = true
                                    CookiePolicy.configureForLogin(this)
                                    if (!webViewLease.attach(this)) {
                                        pageLoading = false
                                        error = "旧验证页面尚未关闭，请关闭后重试。"
                                        return@apply
                                    }
                                    val registration = client.registerInteractiveWebView(attempt.id, webViewLease)
                                    if (registration == null) {
                                        pageLoading = false
                                        error = "验证请求已失效，请返回后重新查询。"
                                        return@apply
                                    }
                                    webViewLease.setRegistration(registration)
                                    browserAttached = true

                                    fun emptyResponse() = WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        ByteArrayInputStream(ByteArray(0)),
                                    )

                                    fun handle(
                                        view: WebView,
                                        url: String,
                                        method: String,
                                        mainFrame: Boolean,
                                        fromIntercept: Boolean,
                                    ): Boolean {
                                        when (val decision = client.inspectInteractiveNavigation(url, method, mainFrame)) {
                                            CardHistoryWebNavigation.Allow -> return false
                                            CardHistoryWebNavigation.Block -> {
                                                if (fromIntercept) view.post { blocked(view, mainFrame) }
                                                else blocked(view, mainFrame)
                                            }
                                            is CardHistoryWebNavigation.Upgrade -> {
                                                if (upgradeScheduled.compareAndSet(false, true)) {
                                                    if (fromIntercept) view.post {
                                                        if (pageGeneration == generation && client.isInteractiveLoginCurrent(attempt.id)) {
                                                            view.loadUrl(decision.url)
                                                        }
                                                    } else if (pageGeneration == generation && client.isInteractiveLoginCurrent(attempt.id)) {
                                                        view.loadUrl(decision.url)
                                                    }
                                                }
                                            }
                                            is CardHistoryWebNavigation.Complete -> {
                                                if (fromIntercept) view.post { complete(decision.url, view) }
                                                else complete(decision.url, view)
                                            }
                                        }
                                        return true
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                            handle(view, request.url.toString(), request.method, request.isForMainFrame, false)

                                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                            if (handle(view, request.url.toString(), request.method, request.isForMainFrame, true)) {
                                                return emptyResponse()
                                            }
                                            return syntheticResources?.intercept(request)
                                        }

                                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            if (pageGeneration != generation || !client.isInteractiveLoginCurrent(attempt.id)) {
                                                view.stopLoading()
                                                return
                                            }
                                            pageLoading = true
                                            error = null
                                            handle(view, url, "GET", true, false)
                                        }

                                        override fun onPageFinished(view: WebView, url: String) {
                                            super.onPageFinished(view, url)
                                            if (pageGeneration == generation && error == null && verificationJob?.isActive != true) {
                                                pageLoading = false
                                            }
                                        }

                                        override fun onReceivedSslError(
                                            view: WebView,
                                            handler: SslErrorHandler,
                                            sslError: SslError?,
                                        ) {
                                            handler.cancel()
                                            if (pageGeneration == generation) {
                                                fail("学校登录页证书校验失败，已停止。")
                                            }
                                        }

                                        override fun onReceivedError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            webError: WebResourceError?,
                                        ) {
                                            if (pageGeneration != generation || !request.isForMainFrame) return
                                            pageLoading = false
                                            error = "学校登录页加载失败，请检查网络后重试。"
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            response: WebResourceResponse,
                                        ) {
                                            if (pageGeneration != generation || !request.isForMainFrame) return
                                            pageLoading = false
                                            error = "学校登录页暂时不可用，请稍后重试。"
                                        }

                                        override fun onSafeBrowsingHit(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            threatType: Int,
                                            callback: android.webkit.SafeBrowsingResponse,
                                        ) {
                                            callback.backToSafety(true)
                                            if (pageGeneration == generation && request.isForMainFrame) {
                                                pageLoading = false
                                                error = "学校页面未通过安全检查，已停止加载。"
                                            }
                                        }

                                        override fun onRenderProcessGone(
                                            view: WebView,
                                            detail: android.webkit.RenderProcessGoneDetail?,
                                        ): Boolean {
                                            if (pageGeneration == generation) {
                                                webViewAlive = false
                                                pageLoading = false
                                                error = "登录页异常退出，请重新加载。"
                                            }
                                            return true
                                        }
                                    }

                                    fun startInitialLoad() {
                                        if (pageGeneration != generation || started.get() || width <= 0 || height <= 0) return
                                        if (!client.isInteractiveLoginCurrent(attempt.id)) {
                                            pageLoading = false
                                            error = "验证请求已失效，请返回后重新查询。"
                                            return
                                        }
                                        if (client.inspectInteractiveNavigation(attempt.entryUrl, "GET", true) != CardHistoryWebNavigation.Allow) {
                                            pageLoading = false
                                            error = "学校登录入口未通过安全检查。"
                                            return
                                        }
                                        if (!started.compareAndSet(false, true)) return
                                        loadUrl(attempt.entryUrl)
                                    }

                                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> startInitialLoad() }
                                    post { startInitialLoad() }
                                }
                                if (browserAttached) webView else android.widget.FrameLayout(context)
                            },
                            onRelease = { view ->
                                if (view is WebView) runCatching { webViewLease.release(view) }
                            },
                        )
                    }
                }
                if (pageLoading && error == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(if (verificationJob?.isActive == true) "正在核验本人账号…" else "正在打开学校页面…")
                        }
                    }
                }
                error?.let { message ->
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("学校验证未完成", style = MaterialTheme.typography.titleMedium)
                            Text(message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                            Button(
                                onClick = {
                                    // New factory creation can precede the old AndroidView's
                                    // onRelease. Finish release here before changing its key.
                                    if (webViewLease.releaseForReload()) {
                                        resultClaimed.set(false)
                                        webViewAlive = true
                                        pageLoading = true
                                        error = null
                                        generation++
                                    } else {
                                        pageLoading = false
                                        error = "旧验证页面尚未关闭，请重试或关闭。"
                                    }
                                },
                                modifier = Modifier.padding(top = 12.dp),
                            ) { Text("重新加载") }
                            TextButton(onClick = currentOnClose) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }
}

private object CookiePolicy {
    fun configureForLogin(webView: WebView) {
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }
}

/** Test-only seam for serving synthetic HTML through the real WebView callbacks. */
internal fun interface CardHistoryWebResourceProvider {
    fun intercept(request: WebResourceRequest): WebResourceResponse?
}

private class CardHistoryLoginWebViewLeaseImpl(
    private val onDisposed: () -> Unit,
) : CardHistoryWebViewLease {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ownership = CardHistoryWebViewOwnership<WebView> { view ->
        runCatching { view.stopLoading() }
        view.destroy()
    }

    fun attach(view: WebView): Boolean = ownership.attach(view)

    fun setRegistration(registration: AutoCloseable?) {
        ownership.setRegistration(registration)
    }

    fun release(view: WebView) {
        ownership.release(view)
    }

    fun releaseForReload(): Boolean = runCatching { ownership.releaseForReload() }.isSuccess

    override fun dispose() {
        if (Looper.myLooper() == Looper.getMainLooper()) runCatching(::closeOnMain)
        else mainHandler.post { runCatching(::closeOnMain) }
    }

    override suspend fun disposeAndAwait(): Boolean = runCatching {
        withContext(Dispatchers.Main.immediate) { closeOnMain() }
    }.isSuccess

    private fun closeOnMain() {
        ownership.close()
        runCatching(onDisposed)
    }
}
