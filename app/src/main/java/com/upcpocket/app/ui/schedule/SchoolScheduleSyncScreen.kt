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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.upcpocket.app.data.school.SchoolDateSource
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
                    @SuppressLint("SetJavaScriptEnabled")
                    val view = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode =
                            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        // 数据桥由 ViewModel 仅在业务页挂载；这里不预先暴露给登录页。
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = !allowedSchoolUrl(request.url.toString())

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                vm.onPageStarted()
                                vm.clearError()
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                // 登录成功后 WebView 会落下业务 Cookie；不 flush，它可能只在
                                // 内存里，进程被杀就丢，重启又要重登。
                                CookieManager.getInstance().flush()
                                vm.onPageFinished(url)
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
        OutlinedTextField(
            value = state.previewDate,
            onValueChange = { vm.setPreviewDate(it) },
            label = { Text("第 1 周周一（YYYY-MM-DD）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "日期来源：${dateSourceLabel(plan.dateSource)}。写入前会以此日期重算每节课的日期，" +
                "并保留你之后在设置里做的修正。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.makeCurrent, onCheckedChange = { vm.setMakeCurrent(it) })
            Text("把该学期设为当前学期", style = MaterialTheme.typography.bodySmall)
        }
        if (plan.sessions.isEmpty()) {
            Text(
                "该学期学校返回 0 条课程。确认后会用空课表替换该学期的学校数据。",
                color = MaterialTheme.colorScheme.error,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.confirmEmptyReplace,
                    onCheckedChange = { vm.setConfirmEmptyReplace(it) },
                )
                Text(
                    "我确认用空课表替换该学期已同步的学校课程",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (plan.emptyWeeks.isNotEmpty()) {
            Text(
                "学校明确返回空课表的周：${plan.emptyWeeks.joinToString(",")}" +
                    "（空周是合法结果，不会当作读取失败）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        /** 预览里可编辑/确认的第 1 周周一（YYYY-MM-DD）。 */
        val previewDate: String = "",
        val makeCurrent: Boolean = false,
        /** 学校返回空学期时，必须显式确认才会替换。 */
        val confirmEmptyReplace: Boolean = false,
    )

    var ui by mutableStateOf(Ui())
        private set

    private val repo = AppGraph.scheduleRepository

    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<String>>()
    private var webView: WebView? = null
    private var syncJob: Job? = null
    private var weekly: List<List<SchoolClassBlock>> = emptyList()

    val bridge = ScheduleDataBridge { id, payload -> onBridgePayload(id, payload) }

    private fun onBridgePayload(id: String, payload: String) {
        val continuation = pending.remove(id) ?: return
        if (payload.length > MAX_BRIDGE_PAYLOAD) {
            continuation.resumeWithException(
                SchoolScheduleException("学校响应过大，已停止读取"),
            )
            return
        }
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

    private var bridgeAttached = false

    /** 主框架加载失败时不要再发起“读取学期”，否则会白等一次超时。 */
    private var mainFrameFailed = false

    fun attach(view: WebView) {
        webView = view
    }

    fun detach() {
        disableBridge()
        webView = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    /** 页面开始跳转：先撤掉数据桥，并作废上一页所有未完成请求（迟到回调直接丢弃）。 */
    fun onPageStarted() {
        disableBridge()
        mainFrameFailed = false
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    /**
     * 只有 `https://app.upc.edu.cn/site/weekschedule/...` 这个业务页面才挂数据桥；
     * CAS 等登录导航页全程不接触课表数据接口。
     */
    fun onPageFinished(url: String) {
        if (isScheduleDataPage(url)) enableBridge()
        if (mainFrameFailed) return
        if (ui.phase != Phase.LOGIN || ui.loading) return
        if (!isScheduleDataPage(url)) return
        loadTerms()
    }

    private fun enableBridge() {
        if (bridgeAttached) return
        webView?.addJavascriptInterface(bridge, BRIDGE_NAME)
        bridgeAttached = true
    }

    fun disableBridge() {
        if (!bridgeAttached) return
        webView?.removeJavascriptInterface(BRIDGE_NAME)
        bridgeAttached = false
    }    fun clearError() {
        if (ui.phase == Phase.LOGIN) ui = ui.copy(error = null)
    }

    fun onPageError(message: String) {
        mainFrameFailed = true
        ui = ui.copy(error = message, loading = false)
    }

    fun reloadLogin() {
        syncJob?.cancel()
        disableBridge()
        pending.values.forEach { it.cancel() }
        pending.clear()
        ui = Ui(phase = Phase.LOGIN)
        webView?.loadUrl(SchoolScheduleProtocol.PAGE_URL)
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
        val saved = repo.semesterByCode(selected.key)
        val isCurrentTerm = ui.page?.current?.key == selected.key
        try {
            val plan = withContext(Dispatchers.Default) {
                SchoolScheduleSync.build(
                    term = selected,
                    weeklyWeeks = weekly,
                    periods = periods,
                    // 只有确认所选学期就是学校当前学期，才允许用当前周/当前学期字段推算日期。
                    schoolCurrentWeek = if (isCurrentTerm) ui.page?.currentWeek else null,
                    schoolTermStart = if (isCurrentTerm) ui.page?.currentTermStart else null,
                    // 已有本地日期（用户可能校正过）优先，重复同步不会改回推算值。
                    savedStart = saved?.startDate,
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
                previewDate = plan.weekOneStart?.toString().orEmpty(),
                makeCurrent = isCurrentTerm || (saved?.isCurrent == true),
                confirmEmptyReplace = false,
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

    fun setPreviewDate(value: String) {
        ui = ui.copy(previewDate = value.filter { it.isDigit() || it == '-' }.take(10), error = null)
    }

    fun setMakeCurrent(value: Boolean) {
        ui = ui.copy(makeCurrent = value)
    }

    fun setConfirmEmptyReplace(value: Boolean) {
        ui = ui.copy(confirmEmptyReplace = value)
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
        val start = runCatching { java.time.LocalDate.parse(ui.previewDate.trim()) }.getOrNull()
        if (start == null) {
            ui = ui.copy(error = "请填写有效的第 1 周周一日期（YYYY-MM-DD）后再确认。")
            return
        }
        if (plan.sessions.isEmpty() && !ui.confirmEmptyReplace) {
            ui = ui.copy(error = "学校返回空学期；请先勾选“确认用空课表替换”，避免误删已有学校课程。")
            return
        }
        val rebased = SchoolScheduleSync.rebase(plan, start)
        ui = ui.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val id = repo.saveSchoolTerm(
                    plan = rebased,
                    makeCurrent = ui.makeCurrent,
                    startDateOverride = start,
                )
                ui = ui.copy(
                    phase = Phase.DONE,
                    loading = false,
                    savedSemesterId = id,
                    savedSemesterName = rebased.term.displayName,
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
        if (path != SchoolScheduleProtocol.INDEX_PATH && path != SchoolScheduleProtocol.DATA_PATH) {
            throw SchoolScheduleException("只允许读取两个固定的课表端点")
        }
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
              function post(payload) {
                if (window.upcPocketBridge && window.upcPocketBridge.post) {
                  window.upcPocketBridge.post(id, JSON.stringify(payload));
                }
              }
              // 只允许最外层、且来自课表业务页的请求；登录/子框架拿不到数据桥。
              if (window.top !== window ||
                  location.origin !== 'https://app.upc.edu.cn' ||
                  !/^\/site\/weekschedule(\/|$)/.test(location.pathname)) {
                post({status: -3, error: '当前页面不是课表业务页'});
                return;
              }
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
                    post({status: r.status, body: t});
                  });
                }).catch(function(e){
                  post({status: 0, error: String(e)});
                });
              } catch (e) {
                post({status: -1, error: String(e)});
              }
            })();
        """.trimIndent()
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

private const val BRIDGE_NAME = "upcPocketBridge"

/** 具名桥类：Lint 要求被注入的方法来自一个有 `@JavascriptInterface` 的具体类型。 */
internal class ScheduleDataBridge(private val onPost: (String, String) -> Unit) {
    @JavascriptInterface
    fun post(id: String, payload: String) {
        onPost(id, payload)
    }
}

/** 单次响应上限；正常 20 周课表远小于此值。 */
private const val MAX_BRIDGE_PAYLOAD = 2 * 1024 * 1024

private fun dateSourceLabel(source: SchoolDateSource): String = when (source) {
    SchoolDateSource.SAVED -> "本地已保存的开学日（重复同步不会改回推算值）"
    SchoolDateSource.SCHOOL_PARAMS -> "学校 params.startday（当前学期线索，写入前请核对）"
    SchoolDateSource.DERIVED_CURRENT_WEEK -> "由学校当前教学周推算"
    SchoolDateSource.USER_CONFIRMED -> "你确认的日期"
    SchoolDateSource.NONE -> "学校未提供；请填写后确认"
}

/** 登录导航允许的页面：UPC 域名的 HTTPS（CAS、门户、业务页）。 */
internal fun allowedSchoolUrl(url: String): Boolean {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (!parsed.scheme.equals("https", ignoreCase = true)) return false
    val host = parsed.host?.lowercase() ?: return false
    return host == "upc.edu.cn" || host.endsWith(".upc.edu.cn")
}

/**
 * 允许读取课表数据、挂载消息桥的**唯一**来源：
 * `https://app.upc.edu.cn/site/weekschedule`（含子路径）。
 *
 * 与 [allowedSchoolUrl] 分开：登录可能经过 CAS/门户，但那些页面只负责导航，
 * 不能调用课表读取桥。
 */
internal fun isScheduleDataPage(url: String): Boolean {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (!parsed.scheme.equals("https", ignoreCase = true)) return false
    if (!parsed.host.equals("app.upc.edu.cn", ignoreCase = true)) return false
    val port = parsed.port
    if (port != -1 && port != 443) return false
    val path = parsed.path.orEmpty()
    return path == "/site/weekschedule" || path.startsWith("/site/weekschedule/")
}
