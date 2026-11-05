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
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticDestination
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.net.URI
import kotlin.math.roundToInt
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
    val operationId = remember(entry?.entryId, webViewGeneration) {
        Diagnostics.newOperationId()
    }

    if (account.blocksPersonalSessions ||
        account.status != SchoolAccountStatus.SAVED_UNVERIFIED
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("先保存学校账号", style = MaterialTheme.typography.titleMedium)
            Text(
                "登录会绑定到本机保存的学校账号；请先在设置中保存。",
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
            Text("登录暂不可用", style = MaterialTheme.typography.titleMedium)
            Text(
                when (cleanupState) {
                    ChaoxingCleanupState.CLEARING ->
                        "正在清理上次登录，完成前不能重新登录。"
                    ChaoxingCleanupState.FAILED ->
                        "上次登录清理失败，已暂停新登录。"
                    ChaoxingCleanupState.IDLE ->
                        "账号状态不可用，请先在设置中确认。"
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
                            error = "清理仍未完成，请重试。"
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
            error = "登录入口已失效，请返回重试。"
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
                    "正在清理上次登录，完成前不能重新打开。"
                ChaoxingCleanupState.FAILED ->
                    "上次登录清理失败，请先重试清理。"
                ChaoxingCleanupState.IDLE ->
                    "账号状态不可用，请返回确认学校账号。"
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
                    error = "清理未完成，请重试。"
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
                title = { Text("学校网页登录") },
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
                                    var pageStartedAt = 0L
                                    var viewportRecorded = false
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(
                                            view: WebView,
                                            url: String,
                                            favicon: Bitmap?,
                                        ) {
                                            if (entry?.entryId != pageEntryId) return
                                            pageStartedAt = System.currentTimeMillis()
                                            pageLoading = true
                                            showSlowHint = false
                                            if (!busy) error = null
                                        }

                                        override fun onPageCommitVisible(
                                            view: WebView,
                                            url: String,
                                        ) {
                                            if (entry?.entryId != pageEntryId) return
                                            if (url != "about:blank") {
                                                if (!viewportRecorded) {
                                                    viewportRecorded = true
                                                    val density = resources.displayMetrics.density
                                                        .coerceAtLeast(1f)
                                                    Diagnostics.record(
                                                        module = DiagnosticModule.LIBRARY,
                                                        action = DiagnosticAction.WEBVIEW_VIEWPORT,
                                                        result = DiagnosticResult.OK,
                                                        reason = DiagnosticReason.VIEWPORT,
                                                        widthDp = (view.width / density)
                                                            .roundToInt(),
                                                        heightDp = (view.height / density)
                                                            .roundToInt(),
                                                        operationId = operationId,
                                                    )
                                                }
                                                settlePageLoading(url)
                                            }
                                        }

                                        override fun onPageFinished(view: WebView, url: String) {
                                            if (entry?.entryId != pageEntryId) return
                                            Diagnostics.record(
                                                module = DiagnosticModule.LIBRARY,
                                                action = DiagnosticAction.WEBVIEW_FINISH,
                                                result = if (error == null) {
                                                    DiagnosticResult.OK
                                                } else {
                                                    DiagnosticResult.FAIL
                                                },
                                                destination = chaoxingLoginDestination(url),
                                                durationMs = pageStartedAt.takeIf { it > 0 }?.let {
                                                    (System.currentTimeMillis() - it)
                                                        .coerceAtLeast(0)
                                                },
                                                operationId = operationId,
                                            )
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
                                            val destination = chaoxingLoginDestination(url)
                                            if (!isAllowedChaoxingLoginUrl(url)) {
                                                Diagnostics.record(
                                                    module = DiagnosticModule.LIBRARY,
                                                    action = DiagnosticAction.WEBVIEW_NAVIGATION,
                                                    result = DiagnosticResult.BLOCKED,
                                                    reason = DiagnosticReason.POLICY,
                                                    destination = destination,
                                                    operationId = operationId,
                                                )
                                                error = "登录页跳转到了未允许的地址（${loginHost(url)}），已停止加载。"
                                                pageLoading = false
                                                return true
                                            }
                                            Diagnostics.record(
                                                module = DiagnosticModule.LIBRARY,
                                                action = DiagnosticAction.WEBVIEW_NAVIGATION,
                                                result = DiagnosticResult.OK,
                                                reason = DiagnosticReason.MAIN_FRAME,
                                                destination = destination,
                                                operationId = operationId,
                                            )
                                            return false
                                        }

                                        override fun onReceivedSslError(
                                            view: WebView,
                                            handler: SslErrorHandler,
                                            sslError: SslError?,
                                        ) {
                                            handler.cancel()
                                            if (entry?.entryId != pageEntryId) return
                                            error = "登录页证书校验失败，已停止。"
                                            pageLoading = false
                                            Diagnostics.record(
                                                module = DiagnosticModule.LIBRARY,
                                                action = DiagnosticAction.WEBVIEW_SSL_REJECT,
                                                result = DiagnosticResult.FAIL,
                                                reason = DiagnosticReason.TLS,
                                                operationId = operationId,
                                            )
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
                                                error = "登录页暂时不可用，请稍后重试。"
                                                pageLoading = false
                                                Diagnostics.record(
                                                    module = DiagnosticModule.LIBRARY,
                                                    action = DiagnosticAction.WEBVIEW_HTTP_ERROR,
                                                    result = DiagnosticResult.FAIL,
                                                    reason = DiagnosticReason.HTTP,
                                                    destination = chaoxingLoginDestination(
                                                        request.url.toString(),
                                                    ),
                                                    httpStatus = errorResponse.statusCode,
                                                    operationId = operationId,
                                                )
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
                                                Diagnostics.record(
                                                    module = DiagnosticModule.LIBRARY,
                                                    action = DiagnosticAction.WEBVIEW_MAIN_FRAME_ERROR,
                                                    result = DiagnosticResult.FAIL,
                                                    reason = DiagnosticReason.NETWORK,
                                                    destination = chaoxingLoginDestination(
                                                        request.url.toString(),
                                                    ),
                                                    operationId = operationId,
                                                )
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
                                            Diagnostics.record(
                                                module = DiagnosticModule.LIBRARY,
                                                action = DiagnosticAction.WEBVIEW_RENDERER_EXIT,
                                                result = DiagnosticResult.FAIL,
                                                reason = DiagnosticReason.RENDERER,
                                                operationId = operationId,
                                            )
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
                                        Diagnostics.record(
                                            module = DiagnosticModule.LIBRARY,
                                            action = DiagnosticAction.WEBVIEW_START,
                                            result = DiagnosticResult.PENDING,
                                            destination = chaoxingLoginDestination(
                                                UpcOfficialEntryLinks.SEAT,
                                            ),
                                            operationId = operationId,
                                        )
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
                                    "正在打开学校登录…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "请在学校页面完成登录",
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
                            TextButton(onClick = { clearOldSession() }) { Text("清除登录") }
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

internal fun chaoxingLoginDestination(raw: String): DiagnosticDestination {
    if (raw == "about:blank") return DiagnosticDestination.UNKNOWN
    val uri = runCatching { URI(raw) }.getOrNull() ?: return DiagnosticDestination.UNKNOWN
    if (!uri.scheme.equals("https", true)) return DiagnosticDestination.UNKNOWN
    return when (uri.host?.lowercase()) {
        "cas.upc.edu.cn" -> DiagnosticDestination.CAS_LOGIN
        "passport2-api.chaoxing.com" -> DiagnosticDestination.APP_CALLBACK
        "i.chaoxing.com", "office.chaoxing.com", "app.upc.edu.cn" ->
            DiagnosticDestination.APP_PAGE
        "tyrzfw.chaoxing.com" -> DiagnosticDestination.OTHER
        else -> DiagnosticDestination.UNKNOWN
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

internal fun chaoxingLoginMessage(reason: ChaoxingAuthFailure): String = when (reason) {
    ChaoxingAuthFailure.INVALID_CREDENTIALS ->
        "账号或密码不正确，请更新后重试。"
    ChaoxingAuthFailure.INTERACTION_REQUIRED ->
        "需要验证码或二次验证，请用网页登录。"
    ChaoxingAuthFailure.ACCOUNT_UNAVAILABLE -> "没有可用的已保存账号。"
    ChaoxingAuthFailure.ACCOUNT_CHANGED -> "账号已更换，请重新登录。"
    ChaoxingAuthFailure.IDENTITY_MISMATCH -> "登录账号与已保存账号不一致，已拒绝。"
    ChaoxingAuthFailure.SESSION_EXPIRED -> "登录已失效，请重新登录。"
    ChaoxingAuthFailure.INVALID_SESSION -> "登录后没有取得有效会话。"
    ChaoxingAuthFailure.NETWORK_UNAVAILABLE -> "网络不可用，登录未完成。"
    ChaoxingAuthFailure.TLS_ERROR -> "无法安全连接学校服务，请稍后重试。"
    ChaoxingAuthFailure.UPSTREAM_ERROR -> "学校服务暂时不可用，请稍后重试。"
    ChaoxingAuthFailure.ACCESS_DENIED -> "学校未允许本次登录，请稍后重试。"
    ChaoxingAuthFailure.PROTOCOL_ERROR -> "登录返回异常，请用网页登录。"
    ChaoxingAuthFailure.CLEANUP_IN_PROGRESS ->
        "正在清理上次登录，完成前不能重新登录。"
    ChaoxingAuthFailure.COOKIE_CLEANUP_FAILED ->
        "上次登录清理失败，请先重试清理。"
    ChaoxingAuthFailure.STALE_WEB_ENTRY ->
        "登录页已过期，请重新打开。"
}
