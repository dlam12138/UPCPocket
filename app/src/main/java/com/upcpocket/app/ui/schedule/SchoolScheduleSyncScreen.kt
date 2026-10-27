package com.upcpocket.app.ui.schedule

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.school.SchoolClassBlock
import com.upcpocket.app.data.school.SchoolScheduleException
import com.upcpocket.app.data.school.SchoolScheduleFormatException
import com.upcpocket.app.data.school.SchoolScheduleProtocol
import com.upcpocket.app.data.school.SchoolScheduleSync
import com.upcpocket.app.data.school.SchoolSessionExpiredException
import com.upcpocket.app.data.school.SchoolSyncPlan
import com.upcpocket.app.data.school.SchoolTerm
import com.upcpocket.app.data.school.SchoolTermPage
import com.upcpocket.app.data.school.SchoolUnitsOutOfRangeException
import com.upcpocket.app.domain.Period
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App 内“登录学校课表 → 选学期 → 逐周读取 → 预览 → 确认写入”。
 *
 * 登录与读取只发生在学校**课表页自身的 WebView 上下文**里：
 *  - WebView 只允许学校域名（upc.edu.cn 及其子域）的 HTTPS 页面，拒绝其它 scheme/来源；
 *  - 注入的是一段固定脚本，只调两个公开课表端点，把原始响应交回 Kotlin；
 *  - 不把 Cookie/Token 取出来，也不提供任意网页/任意 JS 执行接口。
 *
 * 解析、校验、事务写入全部在原生侧完成；预览确认之前不写数据库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolScheduleSyncScreen(
    onClose: () -> Unit,
    onSynced: (Int) -> Unit,
) {
    val vm: SchoolScheduleSyncViewModel = viewModel()
    val state = vm.ui
    var showPeriodEditor by remember { mutableStateOf(false) }
    val loginVisible = state.phase == SchoolScheduleSyncViewModel.Phase.LOGIN

    DisposableEffect(Unit) {
        onDispose { vm.detach() }
    }

    if (showPeriodEditor) {
        PeriodsEditDialog(
            periods = state.periods,
            onDismiss = { showPeriodEditor = false },
            onSave = { list ->
                showPeriodEditor = false
                vm.savePeriodsAndReplan(list)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步学校课表") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
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
            if (loginVisible) {
                Text(
                    "请在学校统一认证页面完成本人登录。登录只用于读取本人课表；" +
                        "App 不读取密码，也不把课程页面整套当成原生界面。登录完成后会自动读取学期列表。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            AndroidView(
                modifier = if (loginVisible) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.size(1.dp)
                },
                factory = { context ->
                    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
                    val view = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(vm.bridge, "upcPocketBridge")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = !allowedSchoolUrl(request.url.toString())

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                vm.clearError()
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                vm.onPageReady(url)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    vm.onPageError("课表页加载失败，请检查网络后重试")
                                }
                            }
                        }
                        vm.attach(this)
                        loadUrl(SchoolScheduleProtocol.PAGE_URL)
                    }
                    view
                },
            )

            if (!loginVisible) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (state.phase) {
                        SchoolScheduleSyncViewModel.Phase.TERMS -> TermsPanel(vm, state, onClose)
                        SchoolScheduleSyncViewModel.Phase.SYNCING -> SyncingPanel(vm, state)
                        SchoolScheduleSyncViewModel.Phase.PREVIEW -> PreviewPanel(vm, state, onClose, onSynced)
                        SchoolScheduleSyncViewModel.Phase.ERROR -> ErrorPanel(vm, state, onClose) {
                            showPeriodEditor = true
                        }
                        SchoolScheduleSyncViewModel.Phase.DONE -> DonePanel(state, onClose, onSynced)
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun TermsPanel(
    vm: SchoolScheduleSyncViewModel,
    state: SchoolScheduleSyncViewModel.Ui,
    onClose: () -> Unit,
) {
    val page = state.page ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("选择要同步的学期（整学期，不是当前周）", style = MaterialTheme.typography.titleMedium)
        page.terms.forEach { term ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.selectedTerm?.key == term.key,
                    onClick = { vm.selectTerm(term) },
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(term.displayName)
                    Text(
                        "${term.totalWeeks} 周" +
                            if (page.current?.key == term.key) " · 学校当前学期" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        page.currentWeek?.let {
            Text(
                "学校页面当前为第 $it 周；同步会逐周读取全部 ${state.selectedTerm?.totalWeeks ?: 0} 周。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.startSync() },
            enabled = state.selectedTerm != null && !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("开始读取整学期") }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}

@Composable
private fun SyncingPanel(vm: SchoolScheduleSyncViewModel, state: SchoolScheduleSyncViewModel.Ui) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("正在逐周读取学校课表…", style = MaterialTheme.typography.titleMedium)
        state.progress?.let { (done, total) ->
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("第 $done / $total 周")
        }
        Text(
            "任一周失败都会停止，并且不会写入半份课表；读取完成前旧课表保持不变。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { vm.cancelSync() }, modifier = Modifier.fillMaxWidth()) {
            Text("取消")
        }
    }
}

@Composable
private fun PreviewPanel(
    vm: SchoolScheduleSyncViewModel,
    state: SchoolScheduleSyncViewModel.Ui,
    onClose: () -> Unit,
    onSynced: (Int) -> Unit,
) {
    val plan = state.plan ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("同步预览", style = MaterialTheme.typography.titleMedium)
        Text("学期：${plan.term.displayName}")
        Text("周范围：第 1–${plan.weekCount} 周")
        Text("课程：${plan.courseCount} 门 · 时段：${plan.slotCount} 条（逐周计）")
        Text(
            plan.weekOneStart?.let { "第 1 周周一：$it（由学校当前周次推算，可在设置里改）" }
                ?: "开学日期：学校未提供当前周次，请在「课程表设置」里填写开学日。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (plan.emptyWeeks.isNotEmpty()) {
            Text(
                "学校明确返回空课表的周：${plan.emptyWeeks.joinToString(",")}" +
                    "（空周是合法结果，不会当作读取失败）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (plan.sessions.isEmpty()) {
            Text(
                "该学期学校返回 0 条课程。确认后会用空课表替换该学期的学校数据。",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "确认后只替换「该学期 + 学校来源」的数据：你的自定义日程和课程显示覆盖会保留。" +
                "再次同步同一学期会覆盖旧的同步结果，不会新增重复课程。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        plan.courses.take(20).forEach { course ->
            Text(
                "· ${course.name}" + (course.teachers.firstOrNull()?.let { "（$it）" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (plan.courses.size > 20) Text("… 仅列出前 20 门")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.commit() },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("确认写入本地课表") }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("放弃") }
    }
}

@Composable
private fun ErrorPanel(
    vm: SchoolScheduleSyncViewModel,
    state: SchoolScheduleSyncViewModel.Ui,
    onClose: () -> Unit,
    onOpenPeriods: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("没有完成同步", style = MaterialTheme.typography.titleMedium)
        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        Text(
            "本次读取没有写入任何数据，原有课表保持不变。",
            style = MaterialTheme.typography.bodySmall,
        )
        state.outOfRange?.let { out ->
            Button(
                onClick = {
                    vm.loadPeriods()
                    onOpenPeriods()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("扩充节次（第 ${out.requiredUnit} 节）") }
        }
        if (state.retryTerm != null) {
            Button(onClick = { vm.startSync() }, modifier = Modifier.fillMaxWidth()) {
                Text("重试本学期的逐周读取")
            }
        }
        OutlinedButton(onClick = { vm.reloadLogin() }, modifier = Modifier.fillMaxWidth()) {
            Text("重新登录学校教务")
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
    }
}

@Composable
private fun DonePanel(
    state: SchoolScheduleSyncViewModel.Ui,
    onClose: () -> Unit,
    onSynced: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("同步完成", style = MaterialTheme.typography.titleMedium)
        Text("已写入 ${state.savedSemesterName}，可直接在课表查看。")
        Button(
            onClick = { state.savedSemesterId?.let(onSynced); onClose() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("查看课表") }
    }
}

internal class SchoolScheduleSyncViewModel : ViewModel() {
    enum class Phase { LOGIN, TERMS, SYNCING, PREVIEW, ERROR, DONE }

    data class Ui(
        val phase: Phase = Phase.LOGIN,
        val page: SchoolTermPage? = null,
        val selectedTerm: SchoolTerm? = null,
        val progress: Pair<Int, Int>? = null,
        val plan: SchoolSyncPlan? = null,
        val periods: List<Period> = emptyList(),
        val error: String? = null,
        val outOfRange: SchoolUnitsOutOfRangeException? = null,
        val retryTerm: SchoolTerm? = null,
        val loading: Boolean = false,
        val savedSemesterId: Int? = null,
        val savedSemesterName: String = "",
    )

    var ui by mutableStateOf(Ui())
        private set

    private val repo = AppGraph.scheduleRepository

    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<String>>()
    private var webView: WebView? = null
    private var syncJob: Job? = null
    private var weekly: List<List<SchoolClassBlock>> = emptyList()

    val bridge = object {
        @JavascriptInterface
        fun post(id: String, payload: String) {
            val continuation = pending.remove(id) ?: return
            try {
                val obj = JSONObject(payload)
                val status = obj.optInt("status", -1)
                val body = obj.optString("body")
                if (status == 200 && body.isNotBlank()) {
                    continuation.resume(body)
                } else {
                    val detail = obj.optString("error")
                    continuation.resumeWithException(
                        SchoolScheduleException(
                            if (detail.isNotBlank()) "读取请求失败：$detail" else "学校返回 HTTP $status",
                        ),
                    )
                }
            } catch (e: Exception) {
                continuation.resumeWithException(SchoolScheduleException("读取结果无法解析"))
            }
        }
    }

    fun attach(view: WebView) {
        webView = view
    }

    fun detach() {
        webView = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    fun clearError() {
        if (ui.phase == Phase.LOGIN) ui = ui.copy(error = null)
    }

    fun onPageError(message: String) {
        ui = ui.copy(error = message)
    }

    fun reloadLogin() {
        syncJob?.cancel()
        ui = Ui(phase = Phase.LOGIN)
        webView?.loadUrl(SchoolScheduleProtocol.PAGE_URL)
    }

    fun onPageReady(url: String) {
        if (ui.phase != Phase.LOGIN || ui.loading) return
        if (!allowedSchoolUrl(url) || !url.contains("/site/weekschedule")) return
        loadTerms()
    }

    fun loadTerms() {
        ui = ui.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val body = withTimeout(25_000) {
                    fetch(SchoolScheduleProtocol.INDEX_PATH, "GET", null)
                }
                val page = withContext(Dispatchers.Default) {
                    SchoolScheduleProtocol.parseIndex(body)
                }
                ui = ui.copy(
                    phase = Phase.TERMS,
                    page = page,
                    selectedTerm = page.current ?: page.terms.first(),
                    periods = repo.periods(),
                    loading = false,
                )
            } catch (e: Exception) {
                fail(e, fromTerm = false)
            }
        }
    }

    fun selectTerm(value: SchoolTerm) {
        ui = ui.copy(selectedTerm = value, error = null)
    }

    fun loadPeriods() {
        viewModelScope.launch { ui = ui.copy(periods = repo.periods()) }
    }

    fun startSync() {
        val selected = ui.selectedTerm ?: return
        syncJob?.cancel()
        ui = ui.copy(
            phase = Phase.SYNCING,
            loading = true,
            error = null,
            outOfRange = null,
            plan = null,
            progress = 0 to selected.totalWeeks,
        )
        syncJob = viewModelScope.launch {
            val weeks = ArrayList<List<SchoolClassBlock>>()
            try {
                for (week in 1..selected.totalWeeks) {
                    ui = ui.copy(progress = (week - 1) to selected.totalWeeks)
                    val body = withTimeout(25_000) {
                        fetch(
                            SchoolScheduleProtocol.DATA_PATH,
                            "POST",
                            "year=${enc(selected.year)}&term=${enc(selected.term)}" +
                                "&week=$week&type=1",
                        )
                    }
                    weeks += withContext(Dispatchers.Default) {
                        SchoolScheduleProtocol.parseWeek(body, week)
                    }
                    ui = ui.copy(progress = week to selected.totalWeeks)
                }
                weekly = weeks
                plan(selected)
            } catch (e: Exception) {
                fail(e, fromTerm = true)
            }
        }
    }

    private suspend fun plan(selected: SchoolTerm) {
        val periods = repo.periods()
        try {
            val plan = withContext(Dispatchers.Default) {
                SchoolScheduleSync.build(
                    term = selected,
                    weeklyWeeks = weekly,
                    periods = periods,
                    schoolCurrentWeek = ui.page?.currentWeek,
                )
            }
            ui = ui.copy(
                phase = Phase.PREVIEW,
                plan = plan,
                periods = periods,
                loading = false,
                progress = null,
                retryTerm = null,
                outOfRange = null,
                error = null,
            )
        } catch (e: SchoolUnitsOutOfRangeException) {
            ui = ui.copy(
                phase = Phase.ERROR,
                loading = false,
                progress = null,
                error = e.message,
                outOfRange = e,
                retryTerm = selected,
                periods = periods,
            )
        }
    }

    fun savePeriodsAndReplan(list: List<Period>) {
        viewModelScope.launch {
            try {
                repo.savePeriods(list)
                ui = ui.copy(periods = repo.periods())
                val selected = ui.retryTerm ?: ui.selectedTerm ?: return@launch
                plan(selected)
            } catch (e: Exception) {
                ui = ui.copy(error = "保存节次失败：${e.message}")
            }
        }
    }

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        ui = ui.copy(phase = Phase.TERMS, loading = false, progress = null)
    }

    fun commit() {
        val plan = ui.plan ?: return
        val makeCurrent = ui.page?.current?.key == plan.term.key
        ui = ui.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val id = repo.saveSchoolTerm(plan, makeCurrent)
                ui = ui.copy(
                    phase = Phase.DONE,
                    loading = false,
                    savedSemesterId = id,
                    savedSemesterName = plan.term.displayName,
                )
            } catch (e: Exception) {
                ui = ui.copy(
                    phase = Phase.PREVIEW,
                    loading = false,
                    error = "写入本地失败：${e.message ?: "数据库异常"}；原有课表未改变。",
                )
            }
        }
    }

    private fun fail(e: Exception, fromTerm: Boolean) {
        if (e is TimeoutCancellationException) {
            ui = ui.copy(
                phase = Phase.ERROR,
                loading = false,
                progress = null,
                error = "读取超时，学校服务没有在 25 秒内返回；本次未写入任何数据。",
                retryTerm = if (fromTerm) ui.selectedTerm else null,
                outOfRange = null,
            )
            return
        }
        if (e is CancellationException) throw e
        val message = when (e) {
            is SchoolSessionExpiredException -> e.message
            is SchoolScheduleFormatException -> e.message
            is SchoolScheduleException -> e.message
            else -> e.message ?: "读取学校课表失败"
        }
        val sessionExpired = e is SchoolSessionExpiredException
        ui = ui.copy(
            phase = if (sessionExpired) Phase.LOGIN else Phase.ERROR,
            loading = false,
            progress = null,
            error = message,
            retryTerm = if (fromTerm && !sessionExpired) ui.selectedTerm else null,
            outOfRange = null,
        )
    }

    private suspend fun fetch(path: String, method: String, body: String?): String {
        val view = webView ?: throw SchoolScheduleException("登录页面尚未准备好")
        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { continuation ->
            pending[id] = continuation
            continuation.invokeOnCancellation { pending.remove(id) }
            val script = buildFetchScript(id, path, method, body)
            view.post { view.evaluateJavascript(script, null) }
        }
    }

    private fun buildFetchScript(id: String, path: String, method: String, body: String?): String {
        val quotedBody = if (body.isNullOrBlank()) "null" else JSONObject.quote(body)
        return """
            (function(){
              var id = ${JSONObject.quote(id)};
              var path = ${JSONObject.quote(path)};
              var method = ${JSONObject.quote(method)};
              var body = $quotedBody;
              try {
                fetch(path, {
                  method: method,
                  credentials: 'include',
                  headers: {
                    'X-Requested-With': 'XMLHttpRequest',
                    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
                  },
                  body: body
                }).then(function(r){
                  return r.text().then(function(t){
                    window.upcPocketBridge.post(id, JSON.stringify({status: r.status, body: t}));
                  });
                }).catch(function(e){
                  window.upcPocketBridge.post(id, JSON.stringify({status: 0, error: String(e)}));
                });
              } catch (e) {
                window.upcPocketBridge.post(id, JSON.stringify({status: -1, error: String(e)}));
              }
            })();
        """.trimIndent()
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

/** 只允许学校域名的 HTTPS 页面；登录链路上的 CAS 与业务页都在 `upc.edu.cn` 下。 */
internal fun allowedSchoolUrl(url: String): Boolean {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (!parsed.scheme.equals("https", ignoreCase = true)) return false
    val host = parsed.host?.lowercase() ?: return false
    return host == "upc.edu.cn" || host.endsWith(".upc.edu.cn")
}
