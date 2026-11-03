package com.upcpocket.app.timetable

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 学校正常网页登录回退。
 *
 * WebView 只打开官方 CAS 课表入口；不注入 JS 接口、不读取表单值、不导出密码。
 * 页面完成时仅读取 `app.upc.edu.cn` 的 Cookie 文本，由业务层再向 get-index 验证
 * 并绑定打开时的账号/代次；验证失败不保存。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TimetableLoginDialog(
    url: String,
    hint: String?,
    onCookies: (String) -> Unit,
    onClose: () -> Unit,
) {
    val currentOnCookies by rememberUpdatedState(onCookies)
    val attempted = remember { AtomicBoolean(false) }
    val cookieManager = remember { CookieManager.getInstance() }
    val targetHost = remember { "app.upc.edu.cn" }

    fun verifyCookies() {
        val header = runCatching {
            cookieManager.getCookie("https://app.upc.edu.cn/")
        }.getOrNull()
        if (!header.isNullOrBlank()) {
            attempted.set(true)
            currentOnCookies(header)
        }
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
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "学校登录",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    Row {
                        TextButton(onClick = { verifyCookies() }) { Text("验证当前登录") }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                }
                Text(
                    hint ?: "请在此完成学校正常登录；验证码/二次验证按学校页面操作。登录完成后会自动验证。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.setSupportMultipleWindows(false)
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url ?: return true
                                    val host = target.host.orEmpty()
                                    val allowed = target.scheme == "https" &&
                                        host in setOf("cas.upc.edu.cn", "app.upc.edu.cn")
                                    // 只允许本次认证实际需要的两个学校 HTTPS 域；
                                    // 避免登录页把 WebView 带到其他站点。
                                    return !allowed
                                }

                                override fun onPageFinished(view: WebView?, currentUrl: String?) {
                                    super.onPageFinished(view, currentUrl)
                                    val host = currentUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                                    if (host != targetHost) return
                                    val header = runCatching {
                                        cookieManager.getCookie("https://app.upc.edu.cn/")
                                    }.getOrNull()
                                    if (!header.isNullOrBlank()) {
                                        cookieManager.flush()
                                        if (attempted.compareAndSet(false, true)) {
                                            currentOnCookies(header)
                                        }
                                    }
                                }
                            }
                            loadUrl(url)
                        }
                    },
                )
            }
        }
    }
}
