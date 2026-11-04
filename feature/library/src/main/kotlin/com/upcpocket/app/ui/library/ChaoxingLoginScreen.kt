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
import com.upcpocket.app.data.chaoxing.ChaoxingWebCookies
import com.upcpocket.app.data.upc.UpcOfficialEntryLinks
import com.upcpocket.app.library.ChaoxingAuthFailure
import com.upcpocket.app.library.ChaoxingLoginResult
import com.upcpocket.app.library.ChaoxingService
import java.net.URI
import kotlinx.coroutines.launch

/**
 * Chaoxing 受限官方 WebView 登录。
 *
 * 只加载固定学校/应用入口，让用户在学校/Chaoxing 官方页面完成登录；应用不预填、
 * 不读取密码字段，也不注入脚本。登录完成后只读取应用 WebView 的 Cookie 名/值，
 * 交给 [ChaoxingService] 与服务端本人学号核验后发布内存会话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaoxingLoginScreen(
    service: ChaoxingService,
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
    var attempted by remember { mutableStateOf<String?>(null) }

    if (account.blocksPersonalSessions ||
        account.status != SchoolAccountStatus.SAVED_UNVERIFIED
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("需要已保存的学校账号", style = MaterialTheme.typography.titleMedium)
            Text(
                "网页登录只把本次 Chaoxing 会话绑定到本机已保存账号；请先在设置中保存账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBack) { Text("返回") }
        }
        return
    }

    fun tryFinish() {
        if (busy || finished) return
        val cookie = collectChaoxingCookies(CookieManager.getInstance())
        if (!ChaoxingWebCookies.hasSessionCookie(cookie) || cookie == attempted) return
        attempted = cookie
        busy = true
        error = null
        webView?.stopLoading()
        scope.launch {
            when (val result = service.loginWithWebCookies(cookie)) {
                ChaoxingLoginResult.Success -> {
                    finished = true
                    CookieManager.getInstance().flush()
                    onLoggedIn()
                }
                is ChaoxingLoginResult.Failure -> {
                    busy = false
                    error = chaoxingLoginMessage(result.reason)
                }
            }
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
                        Text("Chaoxing 网页登录")
                        Text(
                            "座位 / 研讨间 · 官方会话",
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
                "请在学校/Chaoxing 官方页面完成正常登录。应用不会读取或保存你输入的密码，" +
                    "只把登录后的 Chaoxing Cookie 保存在当前内存会话，并核对学校返回的本人学号。",
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
                        attempted = null
                        webView?.reload()
                    }) { Text("重新加载") }
                    TextButton(onClick = {
                        ChaoxingWebCookies.clearAll()
                        CookieManager.getInstance().flush()
                        error = null
                        attempted = null
                        webView?.loadUrl(UpcOfficialEntryLinks.SEAT)
                    }) { Text("清除旧会话") }
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
                                if (!busy) error = null
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                pageLoading = false
                                CookieManager.getInstance().flush()
                                tryFinish()
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url.toString()
                                if (!isAllowedChaoxingLoginUrl(url)) {
                                    error = "已停止打开未允许的登录地址"
                                    pageLoading = false
                                    return true
                                }
                                return false
                            }

                            override fun onReceivedSslError(
                                view: WebView,
                                handler: SslErrorHandler,
                                sslError: SslError?,
                            ) {
                                handler.cancel()
                                error = "Chaoxing 登录页证书校验失败，已停止；不会绕过 TLS"
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
                        post { loadUrl(UpcOfficialEntryLinks.SEAT) }
                    }
                    webView = view
                    view
                },
            )
        }
    }
}

internal fun isAllowedChaoxingLoginUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null) return false
    if (uri.port != -1 && uri.port != 443) return false
    val host = uri.host?.lowercase() ?: return false
    return host in chaoxingLoginHosts
}

internal val chaoxingLoginHosts = setOf(
    "office.chaoxing.com",
    "reserve.chaoxing.com",
    "passport2.chaoxing.com",
    "passport2-api.chaoxing.com",
    "captcha.chaoxing.com",
    "office-static.chaoxing.com",
)

/**
 * 合并 office/reserve 两个 host 的 Cookie，按名称去重，保留先出现的值。
 * 返回值只在内存中使用，不能写日志。
 */
internal fun collectChaoxingCookies(manager: CookieManager): String {
    val office = manager.getCookie("https://office.chaoxing.com/").orEmpty()
    val reserve = manager.getCookie("https://reserve.chaoxing.com/").orEmpty()
    return mergeCookieHeaders(office, reserve)
}

internal fun mergeCookieHeaders(first: String, second: String): String {
    val result = linkedMapOf<String, String>()
    listOf(first, second).forEach { header ->
        header.split(';').forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty() || '=' !in trimmed) return@forEach
            val name = trimmed.substringBefore('=').trim()
            val value = trimmed.substringAfter('=').trim()
            if (name.isNotEmpty() && name !in result) result[name] = value
        }
    }
    return result.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

private fun chaoxingLoginMessage(reason: ChaoxingAuthFailure): String = when (reason) {
    ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE -> "本机学校账号不可用，请先重新保存账号。"
    ChaoxingAuthFailure.ACCOUNT_CHANGED -> "账号已变化；本次登录结果未发布。"
    ChaoxingAuthFailure.IDENTITY_MISMATCH -> "Chaoxing 返回的本人学号与当前账号不一致，已拒绝。"
    ChaoxingAuthFailure.SESSION_EXPIRED -> "Chaoxing 会话未建立或已失效，请重新登录。"
    ChaoxingAuthFailure.INVALID_SESSION -> "登录后没有取得有效 Chaoxing 会话。"
    ChaoxingAuthFailure.NETWORK_UNAVAILABLE -> "网络不可用，登录未完成。"
    ChaoxingAuthFailure.PROTOCOL_ERROR -> "Chaoxing 登录页/身份响应不符合已验证契约。"
}
