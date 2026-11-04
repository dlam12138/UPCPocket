package com.upcpocket.app.ui.library

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.nspace.NspaceWebLoginCallback
import com.upcpocket.app.library.NspaceAuthFailure
import com.upcpocket.app.library.NspaceLoginResult
import com.upcpocket.app.library.NspaceService
import com.upcpocket.app.upcauth.UpcNspaceAuthEndpoints
import java.net.URI
import kotlinx.coroutines.launch

/**
 * nspace 的官方 CAS 交互登录回退。
 *
 * 自动 CAS 遇到验证码/二次验证时，打开学校自己的入口，让用户在 WebView 内完成；
 * 应用不预填密码、不读取密码字段、不绕过验证码。只捕获 libspace 固定 `/nspace`
 * 页面回传的 app token，再交给 [NspaceService] 兑换独立业务 token 并复核本人。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NspaceWebLoginScreen(
    service: NspaceService,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val account by service.accountState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attemptedToken by remember { mutableStateOf<String?>(null) }

    if (account.blocksPersonalSessions ||
        account.status != SchoolAccountStatus.SAVED_UNVERIFIED
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("需要已保存的学校账号", style = MaterialTheme.typography.titleMedium)
            Text(
                "网页登录只把本次 nspace 会话绑定到本机已保存账号；请先在设置中保存账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBack) { Text("返回") }
        }
        return
    }

    fun finish(token: String) {
        if (busy || finished || token == attemptedToken) return
        attemptedToken = token
        busy = true
        error = null
        webView?.stopLoading()
        scope.launch {
            when (val result = service.loginWithWebToken(token)) {
                NspaceLoginResult.Success -> {
                    finished = true
                    CookieManager.getInstance().flush()
                    onLoggedIn()
                }
                is NspaceLoginResult.Failure -> {
                    busy = false
                    error = nspaceWebLoginMessage(result.reason)
                }
            }
        }
    }

    fun capture(url: String) {
        if (finished) return
        NspaceWebLoginCallback.token(url)?.let { finish(it) }
    }

    fun captureFromPage() {
        val view = webView ?: return
        view.evaluateJavascript("String(window.location.href || '')") { raw ->
            val href = raw
                ?.trim()
                ?.trim('"')
                ?.replace("\\/", "/")
                .orEmpty()
            capture(href)
            if (!finished && href.isNotBlank()) capture(view.url.orEmpty())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("nspace 网页登录")
                        Text(
                            "学校 CAS · 验证码/二次验证",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy || pageLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "请在学校页面完成正常登录（含验证码/二次验证）。应用不会读取或保存你在此输入" +
                    "的密码，只接收登录成功后 libspace /nspace 页面回传的临时 app token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        error = null
                        attemptedToken = null
                        webView?.reload()
                    }) { Text("重新加载") }
                    TextButton(onClick = onBack) { Text("返回") }
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    @SuppressLint("SetJavaScriptEnabled")
                    val view = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView,
                                url: String,
                                favicon: Bitmap?,
                            ) {
                                pageLoading = true
                                if (busy.not()) error = null
                                capture(url)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                pageLoading = false
                                CookieManager.getInstance().flush()
                                capture(url)
                                captureFromPage()
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView,
                                url: String,
                                isReload: Boolean,
                            ) {
                                capture(url)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url.toString()
                                if (!isAllowedNspaceLoginUrl(url)) {
                                    error = "已停止打开未允许的登录地址"
                                    pageLoading = false
                                    return true
                                }
                                capture(url)
                                return false
                            }

                            override fun onReceivedSslError(
                                view: WebView,
                                handler: SslErrorHandler,
                                sslError: SslError?,
                            ) {
                                handler.cancel()
                                error = "学校登录页证书校验失败，已停止；不会绕过 TLS"
                                pageLoading = false
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                webError: WebResourceError?,
                            ) {
                                if (request.isForMainFrame) {
                                    pageLoading = false
                                    error = "登录页加载失败；请检查网络后重试。"
                                }
                            }
                        }
                        post { loadUrl(UpcNspaceAuthEndpoints.ENTRY_URL) }
                    }
                    webView = view
                    view
                },
            )
        }
    }
}

internal fun isAllowedNspaceLoginUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", true) && uri.rawUserInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        (host == "upc.edu.cn" || host.endsWith(".upc.edu.cn"))
}

private fun nspaceWebLoginMessage(reason: NspaceAuthFailure): String = when (reason) {
    NspaceAuthFailure.INVALID_CREDENTIALS -> "学校账号或密码未通过；请重新登录。"
    NspaceAuthFailure.INTERACTION_REQUIRED -> "学校仍要求验证码/二次验证，请完成页面提示。"
    NspaceAuthFailure.NETWORK_UNAVAILABLE -> "网络不可用，登录未完成。"
    NspaceAuthFailure.TLS_ERROR -> "登录页证书校验失败，已停止。"
    NspaceAuthFailure.UPSTREAM_ERROR -> "学校登录服务暂时失败，请稍后重试。"
    NspaceAuthFailure.PROTOCOL_ERROR -> "登录回调格式不符合预期，未建立 nspace 会话。"
    NspaceAuthFailure.ACCOUNT_UNAVAILABLE -> "本机学校账号不可用，请先重新保存账号。"
    NspaceAuthFailure.ACCOUNT_CHANGED -> "账号已变化；旧登录结果已丢弃。"
    NspaceAuthFailure.ACCESS_DENIED -> "学校拒绝本次登录；请确认账号权限后重试。"
    NspaceAuthFailure.IDENTITY_MISMATCH -> "网页登录的账号与已保存账号不一致，已拒绝。"
    NspaceAuthFailure.SESSION_EXPIRED -> "nspace 会话已失效，请重新登录。"
}
