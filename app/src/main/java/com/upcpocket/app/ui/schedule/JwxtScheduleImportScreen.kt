package com.upcpocket.app.ui.schedule

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.upcpocket.app.ui.school.SchoolLoginWebViewClient
import com.upcpocket.app.ui.school.jwxtPageGet
import com.upcpocket.app.ui.school.JWXT_VIEWPORT_REPAIR
import com.upcpocket.app.data.school.isJwxtPageUrl
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.school.JwxtReadGuard
import com.upcpocket.app.data.school.JwxtScheduleProtocol
import com.upcpocket.app.data.school.SchoolScheduleSync
import com.upcpocket.app.data.school.SchoolTerm
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 教务当前页导入。
 *
 * 只做一件事：用户在 App 内自己登录、自己打开「学期理论课表」，然后点「读取当前页」。
 * App 不请求固定课表 URL、不自动跳转；读取失败就明确报错，绝不拿半份课表入库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwxtScheduleImportScreen(
    onClose: () -> Unit,
    onSynced: (Int) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val guard = remember { JwxtReadGuard() }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loginClient by remember { mutableStateOf<SchoolLoginWebViewClient?>(null) }
    var pageVersion by remember { mutableStateOf(0) }
    var pageUrl by remember { mutableStateOf("") }
    var pageLoading by remember { mutableStateOf(true) }
    var reading by remember { mutableStateOf(false) }
    var readId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<JwxtScheduleProtocol.Parsed?>(null) }
    var termInput by remember { mutableStateOf("") }
    var dateInput by remember { mutableStateOf("") }
    var makeCurrent by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    fun cancelRead(message: String?) {
        guard.cancel()
        readId = null
        reading = false
        if (message != null) error = message
    }

    fun readCurrentPage() {
        val view = webView ?: return
        if (reading) return
        val url = view.url.orEmpty()
        val version = pageVersion
        val id = UUID.randomUUID().toString()
        if (!guard.begin(url, id, SystemClock.elapsedRealtime())) {
            error = "当前不在教务业务页。请先登录并打开「学期理论课表」再读取。"
            return
        }
        error = null
        readId = id
        reading = true
        // 同步读 DOM 并把结果直接作为 evaluateJavascript 的返回值带回；
        // 不注入 JS 接口，页面（含 iframe）没有可调用的本地对象。
        view.evaluateJavascript(JwxtScheduleProtocol.extractionScript()) { raw ->
            if (readId != id) return@evaluateJavascript
            readId = null
            reading = false
            val body = JwxtScheduleProtocol.decodeEvaluateResult(raw)
            if (body == null) {
                guard.cancel()
                error = "教务页面没有返回可读数据，请重试"
                return@evaluateJavascript
            }
            val currentUrl = view.url.orEmpty()
            if (!guard.accept(id, currentUrl, SystemClock.elapsedRealtime(), body.length)) {
                error = "页面已变化或读取已失效，请回到课表页重新读取"
                return@evaluateJavascript
            }
            scope.launch {
                try {
                    val result = withContext(Dispatchers.Default) { JwxtScheduleProtocol.parse(body) }
                    if (pageVersion != version || view.url != currentUrl) return@launch
                    parsed = result
                    termInput = result.suggestedTerm?.let { term ->
                        "${term.year}-${term.year.toIntOrNull()?.plus(1) ?: term.year}-${term.term}"
                    }.orEmpty()
                    val start = result.suggestedTerm?.let { term ->
                        runCatching {
                            AppGraph.scheduleRepository.semesterByCode("school:${term.year}:${term.term}")?.startDate
                        }.getOrNull()
                    }
                    dateInput = start?.toString().orEmpty()
                    saveError = null
                } catch (e: Exception) {
                    error = e.message ?: "读取教务课表失败，请重试"
                }
            }
        }
        scope.launch {
            delay(15_500)
            if (readId == id) {
                cancelRead("读取当前页超时。请确认已打开「学期理论课表」，再点一次。")
            }
        }
    }

    // 预览盖在 WebView 上时把真实 View 隐藏，避免互操作层的绘制顺序把表格露在预览上面。
    androidx.compose.runtime.LaunchedEffect(parsed) {
        webView?.visibility = if (parsed == null) View.VISIBLE else View.INVISIBLE
    }

    DisposableEffect(Unit) {
        onDispose {
            guard.cancel()
            loginClient?.dispose(webView)
            webView?.destroy()
            webView = null
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("教务课表") }, navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (pageLoading || reading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "1. 在下面的学校页面登录；2. 进入「学期理论课表」；3. 点「读取当前页」。" +
                        "App 只读当前显示的表格，不会自己跳转或请求课表地址。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { readCurrentPage() },
                        enabled = !reading && !pageLoading &&
                            JwxtReadGuard.readableOrigin(pageUrl) != null,
                    ) {
                        Text(if (reading) "正在读取…" else "读取当前页")
                    }
                    TextButton(
                        onClick = { cancelRead(null) },
                        enabled = reading,
                    ) { Text("取消读取") }
                    TextButton(onClick = {
                        cancelRead(null)
                        webView?.let { loginClient?.load(it, JwxtScheduleProtocol.ENTRY) }
                    }, enabled = !saving && !reading) { Text("重新打开入口") }
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = {
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
                                scope = scope,
                                allowed = ::allowedJwxtUrl,
                                onLoading = { pageLoading = it },
                                onError = { error = it },
                                nativeGetUrl = ::isJwxtPageUrl,
                            ) {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                    jwxtPageGet(request) ?: super.shouldInterceptRequest(view, request)

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    pageUrl = url
                                    pageVersion++
                                    if (reading) cancelRead("页面已跳转，本次读取已取消。回到课表页后重新读取。")
                                }
                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    view.evaluateJavascript(JWXT_VIEWPORT_REPAIR, null)
                                    pageUrl = url
                                }
                                override fun onPageCommitVisible(view: WebView, url: String) {
                                    super.onPageCommitVisible(view, url)
                                    view.evaluateJavascript(JWXT_VIEWPORT_REPAIR, null)
                                }
                            }
                            loginClient = client
                            webViewClient = client
                            post { client.load(this, JwxtScheduleProtocol.ENTRY) }
                        }
                        webView = view
                        view
                    },
                )
            }

            parsed?.let { result ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PreviewContent(
                        parsed = result,
                        termInput = termInput,
                        onTermChange = { termInput = it },
                        dateInput = dateInput,
                        onDateChange = { dateInput = it.filter { c -> c.isDigit() || c == '-' }.take(10) },
                        makeCurrent = makeCurrent,
                        onMakeCurrentChange = { makeCurrent = it },
                        saving = saving,
                        error = saveError,
                        onBack = { parsed = null; saveError = null },
                        onConfirm = {
                            val term = JwxtScheduleProtocol.parseTermInput(termInput)
                            if (term == null) {
                                saveError = "学期格式应为 2026-2027-1（完整学年 + 学期号）"
                                return@PreviewContent
                            }
                            val start = JwxtScheduleProtocol.parseStartDate(dateInput)
                            if (start == null) {
                                saveError = "请填写真实的第 1 周周一日期，例如 2026-09-07"
                                return@PreviewContent
                            }
                            if (!JwxtScheduleProtocol.isMonday(dateInput)) {
                                saveError = "第 1 周必须是周一（2026-09-07 是周一，09-08 不是）"
                                return@PreviewContent
                            }
                            saving = true
                            saveError = null
                            scope.launch {
                                try {
                                    val periods = AppGraph.scheduleRepository.periods()
                                    val termValue = SchoolTerm(term.first, term.second, result.totalWeeks, "")
                                    val plan = SchoolScheduleSync.build(
                                        term = termValue,
                                        weeklyWeeks = result.blocks,
                                        periods = periods,
                                        savedStart = start,
                                    )
                                    val id = AppGraph.scheduleRepository.saveSchoolTerm(plan, makeCurrent, start)
                                    onSynced(id)
                                } catch (e: Exception) {
                                    saveError = e.message ?: "写入课表失败"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewContent(
    parsed: JwxtScheduleProtocol.Parsed,
    termInput: String,
    onTermChange: (String) -> Unit,
    dateInput: String,
    onDateChange: (String) -> Unit,
    makeCurrent: Boolean,
    onMakeCurrentChange: (Boolean) -> Unit,
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("确认导入", style = MaterialTheme.typography.titleLarge)
        Text(
            "已从当前页面读取 ${parsed.totalWeeks} 周、${parsed.courses.size} 门课。" +
                if (parsed.rejectedFrames > 0) "（已忽略 ${parsed.rejectedFrames} 个非同源框架）" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (parsed.termLabel.isNotBlank()) {
            Text("页面显示学期：${parsed.termLabel}", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = termInput,
            onValueChange = onTermChange,
            label = { Text("学期（2026-2027-1）") },
            singleLine = true,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = dateInput,
            onValueChange = onDateChange,
            label = { Text("第 1 周周一（YYYY-MM-DD）") },
            singleLine = true,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = makeCurrent, onCheckedChange = onMakeCurrentChange, enabled = !saving)
            Text("设为当前学期")
        }
        Text(
            "确认后只替换该学期的学校课程；你自己添加的日程、CSV 与显示覆盖都会保留。",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        parsed.courses.forEach { course ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(course.name, style = MaterialTheme.typography.titleSmall)
                    Text(course.weeksLabel, style = MaterialTheme.typography.bodySmall)
                    Text(
                        course.slots.joinToString("、") { slot ->
                            "周${"一二三四五六日"[slot.day - 1]} ${slot.startUnit}-${slot.endUnit}节"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (course.teacher != null || course.room != null) {
                        Text(
                            listOfNotNull(course.teacher, course.room).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onConfirm, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(if (saving) "正在写入…" else "写入课表")
        }
        TextButton(onClick = onBack, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text("返回重新读取")
        }
    }
}

internal fun allowedJwxtUrl(url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    if (uri.userInfo != null) return false
    val host = uri.host?.lowercase() ?: return false
    return when (uri.scheme?.lowercase()) {
        "https" -> (uri.port == -1 || uri.port == 443) &&
            (host == "cas.upc.edu.cn" || host == "jwxt.upc.edu.cn")
        // 学校 CAS 的 service 是 http://jwxt...，回跳必须能落回来；明文只限这一台主机的默认端口。
        "http" -> (uri.port == -1 || uri.port == 80) && host == "jwxt.upc.edu.cn"
        else -> false
    }
}
