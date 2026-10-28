package com.upcpocket.app.ui.ecard

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
import androidx.compose.material3.CircularProgressIndicator
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
import com.upcpocket.app.data.ecard.CampusCardAdoptResult
import com.upcpocket.app.data.ecard.CampusCardProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 校园卡官方登录页。
 *
 * 只做一件事：让用户在学校自己的统一认证页面上完成登录，然后把平台**回带给自己应用**
 * 的令牌（`synjones-auth` 查询参数）交给 [AppGraph.campusCardService]。
 *
 * 边界：
 *  - 页面里的学校域名之外不放行；http 明文只在 `mb-xyk` 上暂时不可避免（平台回跳是 http），
 *    其余一律要求 https；
 *  - 不读取密码、不注入 JS 模拟提交、不读微信私有 Cookie；
 *  - 令牌验证通过才关闭本页；失败留在页面上并显示原因，不假装登录成功。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCardLoginScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun finish(token: String) {
        if (busy || finished) return
        busy = true
        error = null
        scope.launch {
            val remember = AppGraph.uiPrefsStore.flow.first().rememberSchoolLogin
            val result = runCatching {
                AppGraph.campusCardService.adoptToken(token, remember)
            }.getOrElse {
                busy = false
                error = "读取校园卡余额失败：${it.message ?: "请稍后重试"}"
                return@launch
            }
            busy = false
            when (result) {
                is CampusCardAdoptResult.Accepted -> {
                    finished = true
                    onLoggedIn()
                }
                CampusCardAdoptResult.Rejected -> {
                    // 登录页本身也可能被学校拒绝（ticket 过期），让用户再登一次而不是空转。
                    error = "学校没有接受这次登录（令牌无效或已过期），请重新登录。"
                }
                is CampusCardAdoptResult.Unavailable -> {
                    // 网络问题不等于登录失败：不说“令牌无效”，也不假装成功。
                    error = "登录已完成，但这次读不到余额：${result.message}。" +
                        "可以稍后在余额页重试，无需重复登录。"
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
                title = { Text("校园卡统一认证") },
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
                text = "登录由学校统一认证页面完成。成功后本页会立即关闭，只保留 App 自己拿到的" +
                    "校园卡平台令牌；开启“记住学校登录”时该令牌用 Keystore 加密保存。" +
                    "不保存密码，也不会读取微信中的任何数据。",
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
            if (busy) {
                CircularProgressIndicator(Modifier.padding(horizontal = 16.dp))
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
                            ): Boolean = !allowedCampusCardUrl(request.url.toString())

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                // 学校平台把令牌放在回跳 URL 里；页面一开始加载就能判断。
                                tokenFromUrl(url)?.let { finish(it) }
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                tokenFromUrl(url)?.let { finish(it) }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                webError: android.webkit.WebResourceError,
                            ) {
                                if (request.isForMainFrame && !finished) {
                                    error = "登录页加载失败，请检查网络后重试"
                                }
                            }
                        }
                        loadUrl(loginUrl())
                    }
                    webView = view
                    view
                },
            )
        }
    }
}

/** 学校平台自己使用的登录入口；`targetUrl` 是平台首页，登录后会回带令牌。 */
internal fun loginUrl(): String {
    val target = java.net.URLEncoder.encode(CampusCardProtocol.PLAT_LANDING, "UTF-8")
    return CampusCardProtocol.BASE + CampusCardProtocol.LOGIN_PATH + "?targetUrl=" + target
}

/**
 * 只放行完成登录必需的学校站点：
 *  - `https://` 任意 `*.upc.edu.cn`；
 *  - `http://mb-xyk.upc.edu.cn`（平台自己的回跳是 http，明文只限这一个主机与路径）。
 */
internal fun allowedCampusCardUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme == "https") return host == "upc.edu.cn" || host.endsWith(".upc.edu.cn")
    if (scheme == "http") {
        if (host != "mb-xyk.upc.edu.cn") return false
        val path = uri.path.orEmpty()
        return path.startsWith("/plat") || path.startsWith("/berserker")
    }
    return false
}

/** 从回跳 URL 的查询参数里读平台令牌；没有就返回 null。 */
internal fun tokenFromUrl(url: String): String? {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isBlank()) return null
    return query.split('&').firstNotNullOfOrNull { part ->
        val name = part.substringBefore('=', "")
        if (name != CampusCardProtocol.TOKEN_PARAM) return@firstNotNullOfOrNull null
        part.substringAfter('=', "").takeIf { it.isNotBlank() }
    }
}
