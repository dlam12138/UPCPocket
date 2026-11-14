package com.upcpocket.app.shiguang

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
import com.upcpocket.app.upcauth.ShiguangWebRequestKind
import com.upcpocket.app.upcauth.UpcShiguangWebNavigation
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ShiguangLoginDialog(
    client: ShiguangService,
    attempt: ShiguangInteractiveAttempt,
    syntheticResources: ShiguangWebResourceProvider? = null,
    onSuccess: () -> Unit,
    onFailure: (ShiguangFailure?) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var webViewAlive by remember(attempt.id) { mutableStateOf(true) }
    var generation by remember(attempt.id) { mutableIntStateOf(0) }
    val pageGate = remember(attempt.id) { ShiguangWebLoginPageGate() }
    var pageLoading by remember(attempt.id) { mutableStateOf(true) }
    var error by remember(attempt.id) { mutableStateOf<String?>(null) }
    var verificationJob by remember(attempt.id) { mutableStateOf<Job?>(null) }
    val webViewLease = remember(attempt.id) { ShiguangLoginWebViewLeaseImpl { webViewAlive = false } }
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val currentOnClose by rememberUpdatedState(onClose)

    fun isActivePage(pageGeneration: Int): Boolean =
        pageGate.isPageActive(pageGeneration, client.isInteractiveLoginCurrent(attempt.id))

    fun fail(pageGeneration: Int, message: String) {
        if (!pageGate.claimPageFailure(pageGeneration, client.isInteractiveLoginCurrent(attempt.id))) return
        pageLoading = false
        if (error == null) error = message
    }

    fun complete(resultUrl: String, view: WebView?, pageGeneration: Int) {
        if (!pageGate.claimCompletion(pageGeneration, client.isInteractiveLoginCurrent(attempt.id))) return
        pageLoading = true
        view?.stopLoading()
        verificationJob = scope.launch {
            try {
                client.completeInteractiveLogin(attempt.id, resultUrl)
                currentOnSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val reason = (failure as? ShiguangException)?.reason
                client.cancelInteractiveLogin(attempt.id)
                currentOnFailure(reason)
            }
        }
    }

    fun blocked(view: WebView?, pageGeneration: Int, mainFrame: Boolean) {
        if (!mainFrame || !isActivePage(pageGeneration)) return
        view?.stopLoading()
        fail(pageGeneration, "学校页面跳转未通过安全检查，请关闭后重试。")
    }

    fun canReopenEntry(): Boolean =
        !pageLoading &&
            !pageGate.isReloadClaimed() &&
            !pageGate.isCompletionClaimed() &&
            verificationJob?.isActive != true &&
            client.isInteractiveLoginCurrent(attempt.id)

    fun isAllowedEntry(): Boolean =
        client.inspectInteractiveNavigation(
            attempt.entryUrl,
            "GET",
            true,
            ShiguangWebRequestKind.NAVIGATION,
        ) == UpcShiguangWebNavigation.Allow

    fun reopenEntryPage() {
        if (!canReopenEntry()) return
        val oldPageGeneration = pageGate.currentGeneration
        if (!pageGate.claimReload(oldPageGeneration, client.isInteractiveLoginCurrent(attempt.id))) return
        // Snapshot state changes synchronously, closing the button before teardown.
        pageLoading = true
        if (!isAllowedEntry()) {
            pageGate.releaseReloadClaim(oldPageGeneration)
            pageLoading = false
            error = "学校登录入口未通过安全检查。"
            return
        }
        // Destroy the current WebView and release its registration before advancing the
        // generation. A failed destroy keeps the existing ownership barrier in place.
        if (!webViewLease.releaseForReload()) {
            pageGate.releaseReloadClaim(oldPageGeneration)
            pageLoading = false
            error = "旧验证页面尚未关闭，请重试或关闭。"
            return
        }
        generation = pageGate.advanceForReload()
        webViewAlive = true
        pageLoading = true
        error = null
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
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "学校验证",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (error == null) {
                    TextButton(
                        enabled = canReopenEntry(),
                        onClick = { reopenEntryPage() },
                    ) { Text("继续") }
                }
            }
            if (error == null) {
                Text(
                    "完成验证后若未自动返回，请点继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (webViewAlive) {
                    key(generation) {
                        AndroidView<android.view.View>(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                val pageGeneration = generation
                                val started = AtomicBoolean(false)
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
                                        fail(pageGeneration, "旧验证页面尚未关闭，请关闭后重试。")
                                        return@apply
                                    }
                                    val registration = client.registerInteractiveWebView(attempt.id, webViewLease)
                                    if (registration == null) {
                                        pageLoading = false
                                        fail(pageGeneration, "验证请求已失效，请返回后重新查询。")
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
                                        if (!isActivePage(pageGeneration)) return true
                                        when (val decision = client.inspectInteractiveNavigation(url, method, mainFrame, if (fromIntercept) ShiguangWebRequestKind.RESOURCE else ShiguangWebRequestKind.NAVIGATION)) {
                                            UpcShiguangWebNavigation.Allow -> return false
                                            UpcShiguangWebNavigation.Block -> {
                                                if (fromIntercept) {
                                                    view.post {
                                                        if (isActivePage(pageGeneration)) blocked(view, pageGeneration, mainFrame)
                                                    }
                                                } else {
                                                    blocked(view, pageGeneration, mainFrame)
                                                }
                                            }
                                            is UpcShiguangWebNavigation.Complete -> {
                                                if (fromIntercept) {
                                                    view.post {
                                                        if (isActivePage(pageGeneration)) complete(decision.callbackUrl, view, pageGeneration)
                                                    }
                                                } else {
                                                    complete(decision.callbackUrl, view, pageGeneration)
                                                }
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
                                            if (!isActivePage(pageGeneration)) {
                                                view.stopLoading()
                                                return
                                            }
                                            pageLoading = true
                                            handle(view, url, "GET", true, false)
                                        }

                                        override fun onPageFinished(view: WebView, url: String) {
                                            super.onPageFinished(view, url)
                                            if (isActivePage(pageGeneration) && error == null && verificationJob?.isActive != true) {
                                                pageGate.releaseReloadClaim(pageGeneration)
                                                pageLoading = false
                                            }
                                        }

                                        override fun onReceivedSslError(
                                            view: WebView,
                                            handler: SslErrorHandler,
                                            sslError: SslError?,
                                        ) {
                                            handler.cancel()
                                            fail(pageGeneration, "学校登录页证书校验失败，已停止。")
                                        }

                                        override fun onReceivedError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            webError: WebResourceError?,
                                        ) {
                                            if (!request.isForMainFrame) return
                                            fail(pageGeneration, "学校登录页加载失败，请检查网络后重试。")
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            response: WebResourceResponse,
                                        ) {
                                            if (!request.isForMainFrame || response.statusCode in setOf(401, 403)) return
                                            fail(pageGeneration, "学校登录页暂时不可用，请稍后重试。")
                                        }

                                        override fun onSafeBrowsingHit(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            threatType: Int,
                                            callback: android.webkit.SafeBrowsingResponse,
                                        ) {
                                            callback.backToSafety(true)
                                            if (request.isForMainFrame) {
                                                fail(pageGeneration, "学校页面未通过安全检查，已停止加载。")
                                            }
                                        }

                                        override fun onRenderProcessGone(
                                            view: WebView,
                                            detail: android.webkit.RenderProcessGoneDetail?,
                                        ): Boolean {
                                            if (isActivePage(pageGeneration)) {
                                                webViewAlive = false
                                                fail(pageGeneration, "登录页异常退出，请重新加载。")
                                            }
                                            return true
                                        }
                                    }

                                    fun startInitialLoad() {
                                        if (!pageGate.isCurrentGeneration(pageGeneration) || pageGate.isCompletionClaimed() || started.get() || width <= 0 || height <= 0) return
                                        if (!client.isInteractiveLoginCurrent(attempt.id)) {
                                            pageLoading = false
                                            if (error == null) error = "验证请求已失效，请返回后重新查询。"
                                            return
                                        }
                                        if (!isAllowedEntry()) {
                                            fail(pageGeneration, "学校登录入口未通过安全检查。")
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
                                onClick = { reopenEntryPage() },
                                enabled = canReopenEntry(),
                                modifier = Modifier.padding(top = 12.dp),
                            ) { Text("重新加载") }
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
internal interface ShiguangWebResourceProvider {
    fun intercept(request: WebResourceRequest): WebResourceResponse?
}

private class ShiguangLoginWebViewLeaseImpl(
    private val onDisposed: () -> Unit,
) : ShiguangWebViewLease {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ownership = ShiguangWebViewOwnership<WebView> { view ->
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
