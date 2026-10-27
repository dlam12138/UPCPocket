package com.upcpocket.app.ui.library

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.library.LibrarySeatProtocol
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 图书馆登录只做一件事：走学校官方 CAS 网页，拿到登录后的座位系统 token。
 *
 * 这里不解析课程/座位页面，不注入账号密码，也不把 WebView 当作功能界面。
 * token 交给 [AppGraph.librarySeatRepository] 后立即关闭 WebView 并清理 Cookie。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySeatLoginScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    fun clearWebSession() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    fun finish(token: String, alreadySeatToken: Boolean) {
        if (busy || finished) return
        busy = true
        error = null
        scope.launch {
            try {
                if (alreadySeatToken) {
                    AppGraph.librarySeatRepository.adoptSeatToken(token)
                } else {
                    AppGraph.librarySeatRepository.exchangeAppToken(token)
                }
                finished = true
                clearWebSession()
                onLoggedIn()
            } catch (e: Exception) {
                busy = false
                error = e.message ?: "登录信息交换失败，请重新登录"
            }
        }
    }

    fun capture(attempt: Int) {
        val view = webView ?: return
        if (finished) return
        view.evaluateJavascript(CAPTURE_JS) { raw ->
            val decoded = runCatching {
                (JSONTokener(raw).nextValue() as? String).orEmpty()
            }.getOrDefault("")
            val obj = runCatching { JSONObject(decoded) }.getOrNull()
            val href = obj?.optString("href").orEmpty()
            val seatToken = obj?.optString("token").orEmpty()
            if (seatToken.isNotBlank()) {
                finish(seatToken, alreadySeatToken = true)
            } else {
                val urlToken = tokenFromUrl(href)
                if (!urlToken.isNullOrBlank()) {
                    finish(urlToken, alreadySeatToken = false)
                } else if (attempt < 20 && !finished) {
                    view.postDelayed({ capture(attempt + 1) }, 450)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            clearWebSession()
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图书馆统一认证") },
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
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                text = "登录由学校统一认证页面完成。登录成功后本页立即关闭，只保留本次运行所需的会话；" +
                    "退出图书馆页会清理会话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val host = request.url.host.orEmpty()
                                // 只允许学校 CAS/座位站点；外部链接不在登录 WebView 里打开。
                                return !(host == "upc.edu.cn" || host.endsWith(".upc.edu.cn"))
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                error = null
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                capture(0)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                webError: android.webkit.WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    error = "登录页加载失败，请检查网络后重试"
                                }
                            }
                        }
                        loadUrl(LibrarySeatProtocol.LOGIN_URL)
                    }
                    webView = view
                    view
                },
            )
        }
    }
}

/** 读取页面自己的 sessionStorage；只读取座位系统 token，不读取微信或其他站点数据。 */
private const val CAPTURE_JS = """
(function () {
  try {
    return JSON.stringify({
      href: String(window.location.href || ''),
      token: String(window.sessionStorage.getItem('libseat-m-token') || '')
    });
  } catch (e) { return ''; }
})()
"""

private fun tokenFromUrl(url: String): String? {
    val query = url.substringAfter('?', "").substringBefore('#')
    val fragment = url.substringAfter('#', "")
    return listOf(query, fragment).firstNotNullOfOrNull { part ->
        part.split('&').firstOrNull { it.startsWith("token=") }
            ?.substringAfter("token=")?.takeIf { it.isNotBlank() }
    }
}
