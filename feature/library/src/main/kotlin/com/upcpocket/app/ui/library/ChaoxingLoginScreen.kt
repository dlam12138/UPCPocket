package com.upcpocket.app.ui.library

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.chaoxing.ChaoxingWebCookies
import com.upcpocket.app.data.school.loginHost
import com.upcpocket.app.data.upc.UpcOfficialEntryLinks
import com.upcpocket.app.library.ChaoxingAuthFailure
import com.upcpocket.app.library.ChaoxingCleanupResult
import com.upcpocket.app.library.ChaoxingCleanupState
import com.upcpocket.app.library.ChaoxingLoginResult
import com.upcpocket.app.library.ChaoxingService
import java.net.URI
import kotlinx.coroutines.delay
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
    val cleanupState by service.cleanupState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webViewAlive by remember { mutableStateOf(true) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }
    var showSlowHint by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempted by remember { mutableStateOf<String?>(null) }
    var entry by remember(account.status, account.generation) {
        mutableStateOf(service.beginWebLogin())
    }

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

    if (entry == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Chaoxing 登录暂不可用", style = MaterialTheme.typography.titleMedium)
            Text(
                when (cleanupState) {
                    ChaoxingCleanupState.CLEARING ->
                        "上一次退出/换号仍在清理 Chaoxing Cookie；完成前不会发布新会话。"
                    ChaoxingCleanupState.FAILED ->
                        "上一次 Chaoxing Cookie 清理失败或超时；已阻止新登录，避免旧会话残留。"
                    ChaoxingCleanupState.IDLE ->
                        "当前账号或会话状态不可用；请先在设置中确认学校账号。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (cleanupState == ChaoxingCleanupState.FAILED) {
                Button(onClick = {
                    scope.launch {
                        if (service.retryCookieCleanup()) {
                            entry = service.beginWebLogin()
                            error = null
                        } else {
                            error = "Cookie 清理仍未成功；不会发布 Chaoxing 会话。"
                        }
                    }
                }) { Text("重试清理") }
            }
            TextButton(onClick = onBack) { Text("返回") }
        }
        return
    }

    fun tryFinish() {
        if (busy || finished) return
        val activeEntry = entry
        if (activeEntry == null) {
            error = "网页登录入口已失效或 Cookie 清理尚未完成；请返回后重新打开。"
            return
        }
        val cookie = collectChaoxingCookies(CookieManager.getInstance())
        if (!ChaoxingWebCookies.hasSessionCookie(cookie) || cookie == attempted) return
        attempted = cookie
        busy = true
        error = null
        webView?.stopLoading()
        scope.launch {
            when (val result = service.loginWithWebCookies(activeEntry, cookie)) {
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

    /**
     * 重新打开页面必须换新的 entryId 和 WebView 实例。旧页面即使稍后回调，
     * 也不能拿新入口发布会话；同时用于从 renderer 退出中恢复。
     */
    fun beginFreshLoginPage(): Boolean {
        val next = service.beginWebLogin()
        if (next == null) {
            error = when (cleanupState) {
                ChaoxingCleanupState.CLEARING ->
                    "上一次 Chaoxing Cookie 仍在清理；完成前不会重新打开登录页。"
                ChaoxingCleanupState.FAILED ->
                    "上一次 Chaoxing Cookie 清理失败；请先重试清理，不能直接重新登录。"
                ChaoxingCleanupState.IDLE ->
                    "当前账号状态不可用；请返回并确认学校账号后重试。"
            }
            pageLoading = false
            return false
        }
        entry = next
        attempted = null
        error = null
        pageLoading = true
        showSlowHint = false
        webViewAlive = true
        webViewGeneration++
        return true
    }

    fun reload() {
        if (busy) return
        beginFreshLoginPage()
    }

    fun clearOldSession() {
        if (busy) return
        busy = true
        error = null
        pageLoading = true
        webView?.stopLoading()
        scope.launch {
            when (val result = service.signOut()) {
                ChaoxingCleanupResult.Success -> {
                    beginFreshLoginPage()
                }
                is ChaoxingCleanupResult.Failed -> {
                    error = "Cookie 清理未完成；未重新加载登录页，避免绕过清理门闩。"
                    pageLoading = false
                }
            }
            busy = false
        }
    }

    LaunchedEffect(pageLoading) {
        showSlowHint = false
        if (!pageLoading) return@LaunchedEffect
        delay(10_000)
        if (pageLoading) showSlowHint = true
    }

    fun settlePageLoading(url: String) {
        pageLoading = shouldKeepLoginLoading(url)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chaoxing 网页登录")
                        Text(
                            "座位 · 官方会话",
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
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (webViewAlive) {
                    key(entry?.entryId, webViewGeneration) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                val pageEntryId = entry?.entryId
                                var seatReloadTriggered = false
                                @SuppressLint("SetJavaScriptEnabled")
                                val view = WebView(context).apply {
                                    // Compose 的 AndroidView 容器不会替 WebView 设置
                                    // MATCH_PARENT 的 layoutParams；缺省 WRAP_CONTENT 会让
                                    // CAS 的 html/body 100% 高度解析成 0，登录卡片被
                                    // 负 margin 顶出屏幕（表现为整块空白/黑屏）。
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.mixedContentMode =
                                        android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance()
                                        .setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(
                                            view: WebView,
                                            url: String,
                                            favicon: Bitmap?,
                                        ) {
                                            if (entry?.entryId != pageEntryId) return
                                            pageLoading = true
                                            showSlowHint = false
                                            if (!busy) error = null
                                        }

                                        override fun onPageCommitVisible(
                                            view: WebView,
                                            url: String,
                                        ) {
                                            if (entry?.entryId != pageEntryId) return
                                            if (url != "about:blank") settlePageLoading(url)
                                        }

                                        override fun onPageFinished(view: WebView, url: String) {
                                            if (entry?.entryId != pageEntryId) return
                                            // SSO 成功后会落在 i.chaoxing.com/base；此时还没有
                                            // office 应用的 oa_uid/oa_deptid。回到座位入口一次，
                                            // 让官方前端建立应用 Cookie，再走本人核验与发布门闩。
                                            val hasOfficeSession = ChaoxingWebCookies.hasSessionCookie(
                                                collectChaoxingCookies(CookieManager.getInstance()),
                                            )
                                            if (!seatReloadTriggered && !hasOfficeSession &&
                                                shouldReloadSeatAfterSso(url)
                                            ) {
                                                seatReloadTriggered = true
                                                view.loadUrl(UpcOfficialEntryLinks.SEAT)
                                                return
                                            }
                                            settlePageLoading(url)
                                            CookieManager.getInstance().flush()
                                            tryFinish()
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest,
                                        ): Boolean {
                                            if (entry?.entryId != pageEntryId) return true
                                            val url = request.url.toString()
                                            if (url == "about:blank" || !request.isForMainFrame) {
                                                return false
                                            }
                                            if (!isAllowedChaoxingLoginUrl(url)) {
                                                error = "登录页跳转到了未允许的地址（${loginHost(url)}），已停止加载。"
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
                                            if (entry?.entryId != pageEntryId) {
                                                handler.cancel()
                                                return
                                            }
                                            handler.cancel()
                                            error = "登录页证书校验失败，已停止；不会绕过 TLS。"
                                            pageLoading = false
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            errorResponse: WebResourceResponse,
                                        ) {
                                            if (entry?.entryId != pageEntryId) return
                                            if (request.isForMainFrame &&
                                                request.url.toString() != "about:blank"
                                            ) {
                                                error =
                                                    "登录页返回 HTTP ${errorResponse.statusCode}；请稍后重试。"
                                                pageLoading = false
                                            }
                                        }

                                        override fun onReceivedError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            webError: WebResourceError?,
                                        ) {
                                            if (entry?.entryId != pageEntryId) return
                                            if (request.isForMainFrame &&
                                                request.url.toString() != "about:blank"
                                            ) {
                                                pageLoading = false
                                                error = "登录页加载失败；请检查网络后重试。"
                                            }
                                        }

                                        override fun onRenderProcessGone(
                                            view: WebView,
                                            detail: RenderProcessGoneDetail?,
                                        ): Boolean {
                                            if (entry?.entryId != pageEntryId) return true
                                            pageLoading = false
                                            webViewAlive = false
                                            error = "登录页渲染进程异常退出；请重新加载。"
                                            return true
                                        }
                                    }
                                    // WebView 在 0×0 时加载会让 CAS 的 html/body 100% 高度
                                    // 解析成 0，登录卡片被负 margin 顶到屏幕外。等首次
                                    // 布局拿到真实尺寸再发起导航，不在未测量时抢跑。
                                    var initialLoadStarted = false
                                    fun startInitialLoad() {
                                        if (initialLoadStarted) return
                                        if (width <= 0 || height <= 0) return
                                        initialLoadStarted = true
                                        loadUrl(UpcOfficialEntryLinks.SEAT)
                                    }
                                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                        startInitialLoad()
                                    }
                                    post { startInitialLoad() }
                                }
                                webView = view
                                view
                            },
                            onRelease = { view ->
                                view.stopLoading()
                                if (webView === view) webView = null
                                view.destroy()
                            },
                        )
                    }
                }
                if (pageLoading && error == null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 3.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Column {
                                Text(
                                    "正在打开学校统一认证…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "跳转由学校与超星官方页面完成",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (showSlowHint) {
                                    TextButton(onClick = { reload() }) { Text("重新加载") }
                                }
                            }
                        }
                    }
                }
                error?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "网页登录未完成",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Button(
                                onClick = { reload() },
                                modifier = Modifier.padding(top = 12.dp),
                            ) { Text("重新加载") }
                            TextButton(onClick = { clearOldSession() }) { Text("清除旧会话") }
                            TextButton(onClick = onBack) { Text("返回") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * tyrzfw 的空白中继页只负责 JS 跳转，不能把加载提示提前关掉；否则用户会看到
 * 一段没有任何说明的空白/深色 WebView，误以为登录黑屏。
 */
internal fun shouldKeepLoginLoading(raw: String): Boolean {
    if (raw == "about:blank") return true
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    return uri.scheme.equals("https", true) &&
        uri.host?.lowercase() in setOf("tyrzfw.chaoxing.com", "app.upc.edu.cn")
}

/**
 * passport2-api/casreceive 成功后的官方落点。只在这个落点回跳一次座位入口，
 * 避免用户在 i.chaoxing.com 首页停留、而应用还没拿到 office 应用 Cookie。
 */
internal fun shouldReloadSeatAfterSso(raw: String): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", true)) return false
    if (!uri.host.equals("i.chaoxing.com", true)) return false
    return uri.port in setOf(-1, 443) &&
        (uri.path.isNullOrEmpty() || uri.path == "/" || uri.path == "/base" || uri.path == "/base/")
}

internal fun isAllowedChaoxingLoginUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null) return false
    if (uri.port != -1 && uri.port != 443) return false
    val host = uri.host?.lowercase() ?: return false
    if (host !in chaoxingLoginHosts) return false
    val path = uri.path.orEmpty()
    return when (host) {
        // 只允许座位应用自己的前端；数据接口仍由 OkHttp 单独校验。
        "office.chaoxing.com" ->
            path.startsWith("/front/third/apps/seat/") ||
                path.startsWith("/front/third/apps/seatengine/")
        // UPC 统一认证跳转链：Chaoxing 认证中继 -> 学校 CAS -> 学校应用授权。
        "tyrzfw.chaoxing.com" -> path.startsWith("/auth3/upc/")
        "cas.upc.edu.cn" -> path.startsWith("/cas/")
        "app.upc.edu.cn" -> path.startsWith("/uc/")
        // passport2-api 的 casreceive 成功页会把主框架送到 i.chaoxing.com 的 /base
        //（页面 302 到 /base；也可能先落在根路径）。这是官方登录完成后的落点，
        // 只放行该落点，不放开整个 i.chaoxing.com 站点。
        "i.chaoxing.com" -> path.isNullOrEmpty() || path == "/" ||
            path == "/base" || path.startsWith("/base/")
        else -> true
    }
}

internal val chaoxingLoginHosts = setOf(
    "office.chaoxing.com",
    "passport2.chaoxing.com",
    "passport2-api.chaoxing.com",
    "captcha.chaoxing.com",
    "office-static.chaoxing.com",
    "passport2-static.chaoxing.com",
    "tyrzfw.chaoxing.com",
    "cas.upc.edu.cn",
    "app.upc.edu.cn",
    "i.chaoxing.com",
)

/**
 * 只读取应用 WebView 的 office Chaoxing Cookie；返回值只在内存中使用，不能写日志。
 */
internal fun collectChaoxingCookies(manager: CookieManager): String =
    manager.getCookie("https://office.chaoxing.com/").orEmpty()

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
    ChaoxingAuthFailure.CLEANUP_IN_PROGRESS ->
        "Chaoxing Cookie 清理进行中；完成前不会发布新会话。"
    ChaoxingAuthFailure.COOKIE_CLEANUP_FAILED ->
        "上次 Cookie 清理失败/超时；已阻止新登录，请先重试清理。"
    ChaoxingAuthFailure.STALE_WEB_ENTRY ->
        "该网页登录页已过期（之后打开过新的登录/清理）；请重新打开登录页。"
}
