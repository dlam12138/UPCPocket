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
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.library.LibrarySeatProtocol
import com.upcpocket.app.library.LibraryAccountAuthFailure
import com.upcpocket.app.library.LibraryAccountLoginResult
import com.upcpocket.app.library.LibraryService
import com.upcpocket.app.ui.school.SchoolLoginWebViewClient
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 图书馆登录只做一件事：走学校官方 CAS 网页，拿到登录后的座位系统 token。
 *
 * 这里不解析课程/座位页面，不注入账号密码，也不把 WebView 当作功能界面。
 * token 交给 [LibraryService] 后立即关闭 WebView 并清理 Cookie。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySeatLoginScreen(
    service: LibraryService,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val accountState by service.accountState.collectAsStateWithLifecycle()
    if (accountState.blocksPersonalSessions) {
        LibraryAccountBlocked(accountState, onBack)
        return
    }
    val loginContext = remember(
        accountState.status,
        accountState.generation,
        accountState.cleanupRequired,
    ) {
        service.captureLoginContext()
    }
    if (loginContext == null) {
        LibraryAccountBlocked(accountState, onBack)
        return
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }
    var loginClient by remember { mutableStateOf<SchoolLoginWebViewClient?>(null) }

    fun clearWebSession() {
        // 只清本业务的 Cookie；不能 removeAllCookies，否则会把统一认证及其它业务会话一起清掉。
        LibrarySeatCookies.clearSeatCookies()
    }

    fun finish(token: String, alreadySeatToken: Boolean) {
        if (busy || finished) return
        busy = true
        error = null
        scope.launch {
            try {
                when (val result = service.completeWebLogin(loginContext, token, alreadySeatToken)) {
                    LibraryAccountLoginResult.Success -> {
                        finished = true
                        clearWebSession()
                        onLoggedIn()
                    }
                    is LibraryAccountLoginResult.Failure -> {
                        busy = false
                        error = libraryAuthMessage(result.reason)
                        if (result.reason == LibraryAccountAuthFailure.IDENTITY_MISMATCH &&
                            service.isLoginContextCurrent(loginContext)
                        ) {
                            // 只清这个旧 WebView 里的另一个账号 Cookie；若期间已有新登录，
                            // 旧页面不能再动全局 WebView 会话。
                            clearWebSession()
                        }
                    }
                }
            } catch (e: Exception) {
                busy = false
                error = e.message ?: "登录信息交换失败，请重新登录"
            }
        }
    }

    fun capture(attempt: Int) {
        val view = webView ?: return
        if (finished || !isLibraryTokenPage(view.url.orEmpty())) return
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
            loginClient?.dispose(webView)
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
            if (busy || pageLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                text = "这是自动登录的验证码/二次验证回退页面。登录后即可在应用内查看和预约座位；" +
                    "已保存统一账号时会话绑定到该账号，未保存时只在本次运行内有效。",
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
                TextButton(onClick = {
                    webView?.let { view -> loginClient?.load(view, LibrarySeatProtocol.LOGIN_URL) }
                }) { Text("重新加载登录页") }
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
                        val client = object : SchoolLoginWebViewClient(
                            scope, ::allowedLibraryLoginUrl,
                            onLoading = { pageLoading = it }, onError = { error = it },
                            onResolved = { url ->
                                val token = if (isLibraryTokenPage(url)) tokenFromUrl(url) else null
                                if (token != null) finish(token, alreadySeatToken = false)
                                token != null
                            },
                        ) {
                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                capture(0)
                            }
                        }
                        loginClient = client
                        webViewClient = client
                        post { client.load(this, LibrarySeatProtocol.LOGIN_URL) }
                    }
                    webView = view
                    view
                },
            )
        }
    }
}

internal fun allowedLibraryLoginUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", true) && uri.rawUserInfo == null &&
        uri.port in setOf(-1, 443) && (host == "upc.edu.cn" || host.endsWith(".upc.edu.cn"))
}

internal fun isLibraryTokenPage(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    return allowedLibraryLoginUrl(url) && uri.host.equals("libspace.upc.edu.cn", true) &&
        (uri.path == "/libseat" || uri.path.orEmpty().startsWith("/libseat/"))
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

/**
 * 只清理图书馆座位系统自己的 Cookie。
 *
 * Android 的 CookieManager 是应用级的：`removeAllCookies()` 会把统一认证
 * 会话一并清掉。这里按 libspace 域名逐个过期，别的业务不受影响；
 * 不声称 WebView Cookie 已加密（它由系统 WebView 管理，见交付说明）。
 */
internal object LibrarySeatCookies {
    private const val BASE = "https://libspace.upc.edu.cn"
    private val paths = listOf("/", "/libseat", "/libseat/", "/remote", "/rest")

    fun clearSeatCookies() {
        val manager = CookieManager.getInstance()
        val header = manager.getCookie("$BASE/libseat/").orEmpty()
        if (header.isBlank()) {
            manager.flush()
            return
        }
        val names = header.split(';')
            .mapNotNull { part ->
                part.trim().substringBefore('=', "").trim()
                    .takeIf { it.isNotEmpty() && it != "token" }
            }
            .distinct() + "token"
        names.forEach { name ->
            paths.forEach { path ->
                manager.setCookie(BASE, "$name=; Max-Age=0; Path=$path")
            }
        }
        manager.flush()
    }
}
