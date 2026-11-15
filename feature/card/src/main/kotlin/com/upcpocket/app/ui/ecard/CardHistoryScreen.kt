package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.cardhistory.*
import com.upcpocket.app.platform.AccountMask
import java.math.BigDecimal
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The host already owns native, encrypted school-account settings; this page never reads passwords. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardHistoryScreen(client: CardHistoryClient, back: () -> Unit, accountSettings: () -> Unit) {
    CardHistoryScreenContent(client, back, accountSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardHistoryScreenContent(
    client: CardHistoryClient,
    back: () -> Unit,
    accountSettings: () -> Unit,
    syntheticResources: CardHistoryWebResourceProvider? = null,
    /** Test seam signals only after the request guard and final state cleanup have run. */
    onRequestSettledForTest: ((SchoolStudentIdentity) -> Unit)? = null,
    /** Test seam signals when guarded history rows enter page state, before the summary request. */
    onRowsCommittedForTest: ((SchoolStudentIdentity) -> Unit)? = null,
) {
    val owner by client.studentIdentity.collectAsStateWithLifecycle()
    val account by client.accountState.collectAsStateWithLifecycle()
    var bound by remember { mutableStateOf<BoundCardHistoryPage?>(null) }
    var rows by remember { mutableStateOf(emptyList<CardHistoryRow>()) }
    var summary by remember { mutableStateOf<CardHistorySummary?>(null) }
    var summaryIsPrevious by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf<YearMonth?>(null) }
    var monthDialog by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loginAttempt by remember { mutableStateOf<CardHistoryInteractiveAttempt?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var requestId by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val available = owner != null && !account.blocksPersonalSessions
    LaunchedEffect(owner, account.blocksPersonalSessions) {
        requestId++
        loginAttempt?.let { client.cancelInteractiveLogin(it.id) }
        loginAttempt = null
        bound = null
        rows = emptyList()
        summary = null
        summaryIsPrevious = false
        message = null
        loading = false
    }

    fun query(all: Boolean = false, more: Boolean = false) {
        if (loading || !available) return
        val expected = owner ?: return
        val selected = month
        val previous = bound
        val oldRows = rows
        val id = ++requestId
        loading = true
        summaryIsPrevious = summary != null
        message = null
        scope.launch {
            try {
                if (!client.hasSession()) client.login()
                val first = if (all) client.all(selected) { loaded, total ->
                    if (id == requestId && client.isCurrent(expected)) message = "正在加载 $loaded / $total 条"
                }
                    else client.page(selected, if (more && previous != null) previous.current + 1 else 1)
                val result = if (more) (oldRows + first.data.rows).distinctBy { it.id } else first.data.rows
                if (id != requestId || !client.isCurrent(expected)) return@launch
                bound = first; rows = result
                onRowsCommittedForTest?.invoke(expected)
                val freshSummary = try { client.summary(selected) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (id != requestId || !client.isCurrent(expected)) {
                    return@launch
                }
                if (freshSummary != null) {
                    summary = freshSummary
                    summaryIsPrevious = false
                } else {
                    summaryIsPrevious = summary != null
                }
                message = when {
                    freshSummary != null -> null
                    summaryIsPrevious -> "流水已更新，学校汇总暂不可用，保留上次汇总"
                    else -> "流水已查询，学校收支汇总暂不可用"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (id == requestId && client.isCurrent(expected)) {
                    summaryIsPrevious = summary != null
                    message = when ((error as? CardHistoryException)?.reason) {
                        CardHistoryFailure.AUTHENTICATION -> "学校登录未完成，请检查学校账号。"
                        CardHistoryFailure.INVALID_CREDENTIALS -> "学校未接受账号密码，请在账号设置中核对。"
                        CardHistoryFailure.INTERACTION_REQUIRED -> "请在学校页面继续登录。"
                        CardHistoryFailure.LOGIN_REQUIRED -> "会话已过期，请再点查询重新登录。"
                        CardHistoryFailure.NETWORK -> "学校服务暂时无法连接，请稍后重试。"
                        CardHistoryFailure.ACCESS_DENIED -> "学校暂不允许登录，请稍后再试。"
                        CardHistoryFailure.UPSTREAM -> "学校登录服务暂不可用，请稍后重试。"
                        else -> "本次查询未完成，已保留本页先前结果。"
                    }
                }
            } finally {
                if (id == requestId) loading = false
                onRequestSettledForTest?.invoke(expected)
            }
        }
    }
    fun startVerification() {
        if (!available || loading || loginAttempt != null) return
        try {
            loginAttempt = client.beginInteractiveLogin()
            message = null
        } catch (error: CardHistoryException) {
            message = when (error.reason) {
                CardHistoryFailure.ACCOUNT_CHANGED, CardHistoryFailure.ACCOUNT_UNAVAILABLE -> "学校账号已变化，请重新查询。"
                else -> "暂时无法打开学校登录页面，请稍后重试。"
            }
        }
    }
    val visible = bound?.takeIf { client.isCurrent(it.owner) && it.month == month }
    val shown = if (visible == null) emptyList() else rows.filter {
        search.isBlank() || it.title.contains(search.trim(), true) || it.time.contains(search.trim())
    }
    fun money(fen: Long) = BigDecimal.valueOf(fen, 2).toPlainString()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("交易流水") },
            navigationIcon = {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "conditions", contentType = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        owner?.studentId?.let(AccountMask::mask) ?: "尚未设置可用学校账号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!available) {
                        Button(onClick = accountSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("前往学校账号设置")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = month == null,
                            enabled = !loading && loginAttempt == null,
                            onClick = {
                                if (month != null) {
                                    requestId++
                                    month = null
                                    bound = null
                                    rows = emptyList()
                                    summary = null
                                    summaryIsPrevious = false
                                    message = null
                                    search = ""
                                }
                            },
                            label = { Text("全部日期") },
                        )
                        FilterChip(
                            selected = month != null,
                            enabled = !loading && loginAttempt == null,
                            onClick = { monthDialog = true },
                            label = { Text(month?.toString() ?: "按月") },
                        )
                    }
                    Button(
                        onClick = { query() },
                        enabled = available && !loading && loginAttempt == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("登录并查询") }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { query(all = true) },
                            enabled = available && !loading && loginAttempt == null,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("加载全部") }
                        if (client.supportsInteractiveLogin) {
                            OutlinedButton(
                                onClick = ::startVerification,
                                enabled = available && !loading && loginAttempt == null,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) { Text("网页登录") }
                        }
                    }
                    if (loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            message ?: if (visible != null) "正在更新 · 保留当前结果" else "正在查询",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (visible != null) {
                summary?.let { data ->
                    item(key = "summary", contentType = "summary") {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (summaryIsPrevious || loading) "上次学校汇总" else "学校汇总",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text("收入 ¥ ${money(data.incomeFen)} · 支出 ¥ ${money(data.expenseFen)}")
                            }
                        }
                    }
                }
                item(key = "result-controls", contentType = "controls") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("已加载 ${rows.size} / ${visible.data.total} 条", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            enabled = !loading && loginAttempt == null,
                            label = { Text("搜索已加载记录") },
                            singleLine = true,
                            trailingIcon = {
                                if (search.isNotEmpty()) {
                                    IconButton(onClick = { search = "" }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                when {
                    rows.isEmpty() -> item(key = "empty", contentType = "empty") {
                        Text("暂无流水", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    shown.isEmpty() -> item(key = "no-match", contentType = "empty") {
                        Text("没有匹配的流水", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> items(
                        items = shown,
                        key = { it.id },
                        contentType = { "transaction" },
                    ) { row ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${row.time} · ¥ ${if (row.amountFen > 0) "+" else ""}${money(row.amountFen)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (visible.current < visible.data.pages) {
                    item(key = "load-more", contentType = "actions") {
                        OutlinedButton(
                            onClick = { query(more = true) },
                            enabled = !loading && available && loginAttempt == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("加载更多") }
                    }
                }
            }
        }
    }
    if (monthDialog) CardHistoryMonthDialog(
        initialMonth = month ?: YearMonth.now(),
        onDismiss = { monthDialog = false },
        onConfirm = { value ->
            if (value != month) {
                requestId++
                month = value
                bound = null
                rows = emptyList()
                summary = null
                summaryIsPrevious = false
                message = null
                search = ""
            }
            monthDialog = false
        },
    )

    loginAttempt?.let { attempt ->
        CardHistoryLoginDialog(
            client = client,
            attempt = attempt,
            syntheticResources = syntheticResources,
            onSuccess = {
                loginAttempt = null
                query()
            },
            onFailure = { reason ->
                loginAttempt = null
                message = when (reason) {
                    CardHistoryFailure.ACCOUNT_CHANGED, CardHistoryFailure.ACCOUNT_UNAVAILABLE -> "学校账号已变化，请重新查询。"
                    CardHistoryFailure.NETWORK -> "学校服务暂时无法连接，请稍后重试。"
                    CardHistoryFailure.PROTOCOL -> "学校登录结果未通过校验，请重新开始网页登录。"
                    CardHistoryFailure.INVALID_CREDENTIALS -> "学校未完成登录，请检查账号后重试。"
                    CardHistoryFailure.ACCESS_DENIED -> "学校暂不允许登录，请稍后再试。"
                    CardHistoryFailure.UPSTREAM -> "学校登录服务暂不可用，请稍后重试。"
                    else -> "学校登录没有完成，请重新开始。"
                }
            },
            onClose = {
                client.cancelInteractiveLogin(attempt.id)
                loginAttempt = null
            },
        )
    }
}
