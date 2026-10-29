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
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.ecard.CampusCardAdoptResult
import com.upcpocket.app.data.ecard.CampusCardProtocol
import com.upcpocket.app.ui.school.SchoolLoginWebViewClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import org.json.JSONTokener

/**
 * 校园卡官方登录页。
 *
 * 用户在学校官方平台页面完成登录，采用 URL 回调或同源 sessionStorage 的令牌交给 [AppGraph.campusCardService]。
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
    var pageLoading by remember { mutableStateOf(true) }
    var loginClient by remember { mutableStateOf<SchoolLoginWebViewClient?>(null) }

    var attemptedToken by remember { mutableStateOf<String?>(null) }

    fun finish(token: String) {
        if (busy || finished || token == attemptedToken) return
        attemptedToken = token
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
                        "请点击重新检测；确认余额成功后才会保存这次登录。"
                }
            }
        }
    }

    // 官方登录组件把 access_token 写入同源 sessionStorage，而不一定回带到 URL。
    // 只在该业务的 HTTPS /plat 页面读取此单一字段；不挂全域 JS bridge，不读取表单。
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        while (true) {
            delay(750)
            val origin = view.url.orEmpty()
            if (!busy && !finished && isCampusCardStoragePage(origin)) {
                view.evaluateJavascript(CARD_LOGIN_LAYOUT_REPAIR, null)
                view.evaluateJavascript("(function(){return sessionStorage.getItem('access_token')||'';})()") { raw ->
                    if (view.url == origin && isCampusCardStoragePage(view.url.orEmpty()) && raw.length <= 16_384) {
                        val token = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                        if (!token.isNullOrBlank()) finish(token)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            loginClient?.dispose(webView)
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录校园卡") },
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
                text = "在学校校园卡页面登录，可选择学号登录或统一认证。成功后返回查看余额与流水。",
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
                    attemptedToken = null
                    webView?.let { view -> loginClient?.load(view, loginUrl()) }
                }) { Text("重新加载登录页") }
            }
            TextButton(onClick = { attemptedToken = null; error = null }) { Text("已完成登录，重新检测") }
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
                        val client = object : SchoolLoginWebViewClient(
                            scope, ::allowedCampusCardUrl,
                            onLoading = { pageLoading = it }, onError = { error = it },
                            onResolved = { url ->
                                val token = campusCardTokenFromUrl(url)
                                if (token != null) finish(token)
                                token != null
                            },
                        ) {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? =
                                cardPageGet(request) ?: super.shouldInterceptRequest(view, request)

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                // 学校平台把令牌放在回跳 URL 里；页面一开始加载就能判断。
                                campusCardTokenFromUrl(url)?.let { finish(it) }
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                campusCardTokenFromUrl(url)?.let { finish(it) }
                            }
                        }
                        loginClient = client
                        webViewClient = client
                        post { client.load(this, loginUrl()) }
                    }
                    webView = view
                    view
                },
            )
        }
    }
}

/** 学校平台自己使用的登录入口；`targetUrl` 是平台首页，登录后会回带令牌。 */
internal fun loginUrl(): String = CampusCardProtocol.BASE + "/plat/?name=Login"

internal fun isCampusCardStoragePage(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.host == "mb-xyk.upc.edu.cn" &&
        uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
        (uri.path == "/plat" || uri.path.startsWith("/plat/"))
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
    if (uri.rawUserInfo != null || uri.port !in setOf(-1, if (scheme == "https") 443 else 80)) return false
    if (scheme == "https") return host == "upc.edu.cn" || host.endsWith(".upc.edu.cn")
    if (scheme == "http") {
        if (host != "mb-xyk.upc.edu.cn") return false
        val path = uri.path.orEmpty()
        // 必须按**路径段**判断，不能用 startsWith 子串："/platform-evil" 也会命中 "/plat"。
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        return segments.firstOrNull() in setOf("plat", "plat-pc", "berserker")
            || segments.firstOrNull()?.startsWith("berserker-") == true
    }
    return false
}

internal fun campusCardTokenFromUrl(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (!allowedCampusCardUrl(url) || !uri.host.equals("mb-xyk.upc.edu.cn", true)) return null
    return tokenFromUrl(url)
}

/** 从回跳 URL 的查询参数里读平台令牌；没有就返回 null。 */
internal fun tokenFromUrl(url: String): String? {
    if (url.isBlank()) return null
    // 平台自己的解析器（dist 里的 getRequest）在 `?` 出现在 `#` **之后**时，
    // 会去解析 `#` 后面的查询串。所以 #/query 与 ?query 两种形态都要认，
    // 否则“登录成功却读不到令牌”会表现成令牌无效。
    val candidates = buildList {
        url.substringAfter('?', "").substringBefore('#').takeIf { it.isNotBlank() }?.let(::add)
        val hash = url.substringAfter('#', "")
        if (hash.contains('?')) add(hash.substringAfter('?'))
    }
    candidates.forEach { query ->
        query.split('&').forEach { part ->
            val name = part.substringBefore('=', "")
            if (name != CampusCardProtocol.TOKEN_PARAM) return@forEach
            val raw = part.substringAfter('=', "")
            if (raw.isBlank()) return@forEach
            // 平台解析时会 decodeURIComponent；令牌里出现 %2B/ 等编码字符时不解码就会拿到坏值。
            val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            if (decoded.isNotBlank()) return decoded
        }
    }
    return null
}

/** Official card page clips its loaded form when its viewport roots collapse to zero. */
internal const val CARD_LOGIN_LAYOUT_REPAIR = """
(function(){
  if(location.protocol!=='https:' || location.hostname!=='mb-xyk.upc.edu.cn' ||
     !/^\/plat(?:\/|$)/.test(location.pathname)) return;
  var roots=[document.documentElement,document.body,document.querySelector('.login-page')];
  roots.forEach(function(e){
    if(e && (e.getBoundingClientRect().height<16 || e.dataset.upcPocketHeight==='1')) {
      e.style.setProperty('height',window.innerHeight+'px','important');
      e.dataset.upcPocketHeight='1';
    }
  });
})();
"""
