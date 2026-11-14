package com.upcpocket.app.timetable

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticOperation
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val TIMETABLE_APP_HOST = "app.upc.edu.cn"

/**
 * 学校正常网页登录回退。
 *
 * WebView 只打开官方 CAS 课表入口；不注入 JS 接口、不读取表单值、不导出密码。
 * 页面完成时仅读取 `app.upc.edu.cn` 的 Cookie 文本，由业务层再向 get-index 验证
 * 并绑定打开时的账号/代次；验证失败不保存。
 */
internal data class CookieHeaderValue(val name: String, val value: String)

internal fun parseCookieHeaderValues(header: String?): List<CookieHeaderValue> =
    header.orEmpty()
        .split(';')
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .mapNotNull { part ->
            val index = part.indexOf('=')
            val name = if (index > 0) part.substring(0, index).trim() else ""
            if (name.isEmpty()) {
                null
            } else {
                CookieHeaderValue(
                    name = name,
                    value = part.substring(index + 1).trim(),
                )
            }
        }

/**
 * CookieManager 只返回“适用于该 URL”的 Cookie，且不暴露 path。按业务路径、
 * 页面路径、根路径的顺序读取，同名时优先选择“只在更具体路径出现”的值；
 * 若同名只有根路径值，则保留根路径值。结果仍交给 Service 按账号/代次验证。
 */
internal fun mergeCookieHeadersByTarget(headers: List<String?>): String? {
    if (headers.all { it.isNullOrBlank() }) return null
    val rootValues = parseCookieHeaderValues(headers.lastOrNull())
        .groupBy({ it.name }, { it.value })
    val merged = LinkedHashMap<String, String>()
    headers.forEachIndexed { index, header ->
        val pairs = parseCookieHeaderValues(header)
        pairs.forEach pairLoop@{ pair ->
            if (merged.containsKey(pair.name)) return@pairLoop
            val rootForName = rootValues[pair.name].orEmpty()
            val specific = if (index < headers.lastIndex) {
                pairs.asSequence()
                    .filter { it.name == pair.name && it.value !in rootForName }
                    .map { it.value }
                    .firstOrNull()
            } else {
                null
            }
            merged[pair.name] = specific ?: pair.value
        }
    }
    return merged.entries
        .joinToString("; ") { "${it.key}=${it.value}" }
        .ifBlank { null }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TimetableLoginDialog(
    url: String,
    hint: String?,
    onCookies: (String, DiagnosticOperation, (Boolean) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    val currentOnCookies by rememberUpdatedState(onCookies)
    val cookieManager = remember { CookieManager.getInstance() }
    val attempted = remember { AtomicBoolean(false) }
    var webViewAlive by remember { mutableStateOf(true) }
    var generation by remember { mutableIntStateOf(0) }
    var pageLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusHint by remember { mutableStateOf<String?>(null) }
    val diagnosticOperation = remember(generation) { Diagnostics.beginOperation() }

    fun readCookieHeader(): String? = runCatching {
        cookieManager.flush()
        mergeCookieHeadersByTarget(
            listOf(
                cookieManager.getCookie(
                    "https://$TIMETABLE_APP_HOST/timetable/wap/default/get-datatmp",
                ),
                cookieManager.getCookie(
                    "https://$TIMETABLE_APP_HOST/timetable/wap/default/get-index",
                ),
                cookieManager.getCookie(
                    "https://$TIMETABLE_APP_HOST/site/weekschedule/index",
                ),
                cookieManager.getCookie("https://$TIMETABLE_APP_HOST/"),
            ),
        )
    }.getOrNull()

    fun submitCookies(auto: Boolean) {
        val header = readCookieHeader()
        if (header.isNullOrBlank()) {
            if (!auto) {
                statusHint = "还没有检测到登录状态，请完成登录后再试。"
            }
            return
        }
        if (auto && !attempted.compareAndSet(false, true)) return
        statusHint = null
        currentOnCookies(header, diagnosticOperation) { accepted ->
            // 自动提交失败后允许后续页面完成/用户点“完成”再试；不循环认证。
            if (!accepted) attempted.set(false)
        }
    }

    fun verifyCookies() {
        submitCookies(auto = false)
    }

    fun reload() {
        attempted.set(false)
        webViewAlive = true
        pageLoading = true
        error = null
        statusHint = null
        generation += 1
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "网页登录",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    Row {
                        TextButton(onClick = { verifyCookies() }) { Text("完成") }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                }
                Text(
                    hint ?: "请在学校页面完成登录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                statusHint?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (webViewAlive) {
                        key(generation) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { context ->
                                    val pageGeneration = generation
                                    WebView(context).apply {
                                        // Compose 的 AndroidView 不会替 WebView 设置
                                        // MATCH_PARENT；缺省 WRAP_CONTENT 会让 CAS 的
                                        // html/body 高度解析为 0，移动登录 iframe 也变成
                                        // 0px，表单被顶出屏幕。必须显式补齐。
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
                                        settings.setSupportMultipleWindows(false)
                                        cookieManager.setAcceptCookie(true)
                                        cookieManager.setAcceptThirdPartyCookies(this, true)
                                        var pageStartedAt = 0L
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(
                                                view: WebView,
                                                url: String,
                                                favicon: Bitmap?,
                                            ) {
                                                if (pageGeneration != generation) return
                                                // 错误只由 reload() 清除；子框架/迟到页面
                                                // 的新导航不能让已显示的错误遮罩消失后留下空白。
                                                pageLoading = true
                                                pageStartedAt = System.currentTimeMillis()
                                            }

                                            override fun onPageFinished(
                                                view: WebView,
                                                currentUrl: String?,
                                            ) {
                                                super.onPageFinished(view, currentUrl)
                                                if (pageGeneration != generation) return
                                                pageLoading = false
                                                cookieManager.flush()
                                                val destination = currentUrl?.let(
                                                    TimetableLoginPolicy::destination,
                                                )
                                                Diagnostics.record(
                                                    module = DiagnosticModule.TIMETABLE,
                                                    action = DiagnosticAction.WEBVIEW_FINISH,
                                                    result = if (error == null) {
                                                        DiagnosticResult.OK
                                                    } else {
                                                        DiagnosticResult.FAIL
                                                    },
                                                    destination = destination,
                                                    durationMs = pageStartedAt.takeIf { it > 0 }
                                                        ?.let {
                                                            (System.currentTimeMillis() - it)
                                                                .coerceAtLeast(0)
                                                        },
                                                    operation = diagnosticOperation,
                                                )
                                                val host = currentUrl?.let {
                                                    runCatching { Uri.parse(it).host }.getOrNull()
                                                }
                                                if (host.equals(TIMETABLE_APP_HOST, ignoreCase = true)) {
                                                    // 页面脚本可能在 onPageFinished 之后才写入
                                                    // 业务 Cookie；稍等再读一次，避免过早回报。
                                                    view.postDelayed({
                                                        if (pageGeneration == generation &&
                                                            error == null
                                                        ) {
                                                            submitCookies(auto = true)
                                                        }
                                                    }, 350L)
                                                }
                                            }

                                            override fun shouldOverrideUrlLoading(
                                                view: WebView,
                                                request: WebResourceRequest,
                                            ): Boolean {
                                                if (pageGeneration != generation) return true
                                                val target = request.url.toString()
                                                if (target == "about:blank") return false
                                                val allowed = TimetableLoginPolicy.isAllowed(target)
                                                val destination =
                                                    TimetableLoginPolicy.destination(target)
                                                if (request.isForMainFrame) {
                                                    if (!allowed) {
                                                        Diagnostics.record(
                                                            module = DiagnosticModule.TIMETABLE,
                                                            action = DiagnosticAction.WEBVIEW_NAVIGATION,
                                                            result = DiagnosticResult.BLOCKED,
                                                            reason = DiagnosticReason.POLICY,
                                                            destination = destination,
                                                            operation = diagnosticOperation,
                                                        )
                                                        error = "登录页跳转到了未允许的地址" +
                                                            "（${TimetableLoginPolicy.host(target) ?: "未知"}），已停止加载。"
                                                        pageLoading = false
                                                        return true
                                                    }
                                                    Diagnostics.record(
                                                        module = DiagnosticModule.TIMETABLE,
                                                        action = DiagnosticAction.WEBVIEW_NAVIGATION,
                                                        result = DiagnosticResult.OK,
                                                        reason = DiagnosticReason.MAIN_FRAME,
                                                        destination = destination,
                                                        operation = diagnosticOperation,
                                                    )
                                                } else if (!allowed) {
                                                    // 不让未允许的 iframe/子框架继续导航；
                                                    // 子资源仍由 WebView 自己处理。
                                                    Diagnostics.record(
                                                        module = DiagnosticModule.TIMETABLE,
                                                        action = DiagnosticAction.WEBVIEW_NAVIGATION,
                                                        result = DiagnosticResult.BLOCKED,
                                                        reason = DiagnosticReason.SUB_FRAME,
                                                        destination = destination,
                                                        operation = diagnosticOperation,
                                                    )
                                                    return true
                                                } else {
                                                    Diagnostics.record(
                                                        module = DiagnosticModule.TIMETABLE,
                                                        action = DiagnosticAction.WEBVIEW_NAVIGATION,
                                                        result = DiagnosticResult.OK,
                                                        reason = DiagnosticReason.SUB_FRAME,
                                                        destination = destination,
                                                        operation = diagnosticOperation,
                                                    )
                                                }
                                                return false
                                            }

                                            override fun onReceivedSslError(
                                                view: WebView,
                                                handler: SslErrorHandler,
                                                sslError: SslError?,
                                            ) {
                                                handler.cancel()
                                                if (pageGeneration != generation) return
                                                pageLoading = false
                                                error = "登录页证书校验失败，已停止。"
                                                Diagnostics.record(
                                                    module = DiagnosticModule.TIMETABLE,
                                                    action = DiagnosticAction.WEBVIEW_SSL_REJECT,
                                                    result = DiagnosticResult.FAIL,
                                                    reason = DiagnosticReason.TLS,
                                                    operation = diagnosticOperation,
                                                )
                                            }

                                            override fun onReceivedHttpError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                errorResponse: WebResourceResponse,
                                            ) {
                                                if (pageGeneration != generation) return
                                                if (!request.isForMainFrame) return
                                                pageLoading = false
                                                error = "登录页暂时不可用，请稍后重试。"
                                                Diagnostics.record(
                                                    module = DiagnosticModule.TIMETABLE,
                                                    action = DiagnosticAction.WEBVIEW_HTTP_ERROR,
                                                    result = DiagnosticResult.FAIL,
                                                    reason = DiagnosticReason.HTTP,
                                                    destination = TimetableLoginPolicy.destination(
                                                        request.url.toString(),
                                                    ),
                                                    httpStatus = errorResponse.statusCode,
                                                    operation = diagnosticOperation,
                                                )
                                            }

                                            override fun onReceivedError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                webError: WebResourceError?,
                                            ) {
                                                if (pageGeneration != generation) return
                                                if (!request.isForMainFrame) return
                                                pageLoading = false
                                                error = "登录页加载失败；请检查网络后重试。"
                                                Diagnostics.record(
                                                    module = DiagnosticModule.TIMETABLE,
                                                    action = DiagnosticAction.WEBVIEW_MAIN_FRAME_ERROR,
                                                    result = DiagnosticResult.FAIL,
                                                    reason = DiagnosticReason.NETWORK,
                                                    destination = TimetableLoginPolicy.destination(
                                                        request.url.toString(),
                                                    ),
                                                    operation = diagnosticOperation,
                                                )
                                            }

                                            override fun onRenderProcessGone(
                                                view: WebView,
                                                detail: RenderProcessGoneDetail?,
                                            ): Boolean {
                                                if (pageGeneration != generation) return true
                                                pageLoading = false
                                                webViewAlive = false
                                                error = "登录页渲染进程异常退出；请重新加载。"
                                                Diagnostics.record(
                                                    module = DiagnosticModule.TIMETABLE,
                                                    action = DiagnosticAction.WEBVIEW_RENDERER_EXIT,
                                                    result = DiagnosticResult.FAIL,
                                                    reason = DiagnosticReason.RENDERER,
                                                    operation = diagnosticOperation,
                                                )
                                                return true
                                            }
                                        }

                                        // 等 WebView 拿到真实宽高后再导航。在 0×0 时抢跑
                                        // 会让 CAS 的 100% 高度布局固定为 0，即使之后补
                                        // layoutParams 也来不及恢复。
                                        var started = false
                                        fun startInitialLoad() {
                                            if (started) return
                                            if (width <= 0 || height <= 0) return
                                            started = true
                                            val density = resources.displayMetrics.density
                                                .coerceAtLeast(1f)
                                            Diagnostics.record(
                                                module = DiagnosticModule.TIMETABLE,
                                                action = DiagnosticAction.WEBVIEW_VIEWPORT,
                                                result = DiagnosticResult.OK,
                                                reason = DiagnosticReason.VIEWPORT,
                                                widthDp = (width / density).roundToInt(),
                                                heightDp = (height / density).roundToInt(),
                                                operation = diagnosticOperation,
                                            )
                                            if (!TimetableLoginPolicy.isAllowedEntry(url)) {
                                                pageLoading = false
                                                error = "登录入口不是允许的学校地址。"
                                                Diagnostics.record(
                                                    module = DiagnosticModule.TIMETABLE,
                                                    action = DiagnosticAction.WEBVIEW_START,
                                                    result = DiagnosticResult.BLOCKED,
                                                    reason = DiagnosticReason.POLICY,
                                                    destination = TimetableLoginPolicy
                                                        .destination(url),
                                                    operation = diagnosticOperation,
                                                )
                                                return
                                            }
                                            Diagnostics.record(
                                                module = DiagnosticModule.TIMETABLE,
                                                action = DiagnosticAction.WEBVIEW_START,
                                                result = DiagnosticResult.PENDING,
                                                destination = TimetableLoginPolicy.destination(url),
                                                operation = diagnosticOperation,
                                            )
                                            loadUrl(url)
                                        }
                                        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                            startInitialLoad()
                                        }
                                        post { startInitialLoad() }
                                    }
                                },
                                onRelease = { view ->
                                    view.stopLoading()
                                    view.destroy()
                                },
                            )
                        }
                    }
                    if (pageLoading && error == null) {
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter),
                        )
                    }
                    error?.let { message ->
                        Surface(
                            Modifier.fillMaxSize(),
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
                                TextButton(onClick = onClose) { Text("关闭") }
                            }
                        }
                    }
                }
            }
        }
    }
}
