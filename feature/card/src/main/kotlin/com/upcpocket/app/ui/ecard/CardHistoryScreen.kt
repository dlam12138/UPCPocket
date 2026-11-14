package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
) {
    val owner by client.studentIdentity.collectAsStateWithLifecycle()
    val account by client.accountState.collectAsStateWithLifecycle()
    var bound by remember { mutableStateOf<BoundCardHistoryPage?>(null) }
    var rows by remember { mutableStateOf(emptyList<CardHistoryRow>()) }
    var summary by remember { mutableStateOf<CardHistorySummary?>(null) }
    var month by remember { mutableStateOf<YearMonth?>(null) }
    var monthDialog by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loginAttempt by remember { mutableStateOf<CardHistoryInteractiveAttempt?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val available = owner != null && !account.blocksPersonalSessions
    LaunchedEffect(owner, account.blocksPersonalSessions) {
        loginAttempt?.let { client.cancelInteractiveLogin(it.id) }
        loginAttempt = null
        bound = null; rows = emptyList(); summary = null; message = null
    }

    fun query(all: Boolean = false, more: Boolean = false) {
        if (loading || !available) return
        val expected = owner ?: return
        val selected = month
        val previous = bound
        val oldRows = rows
        loading = true; message = null
        scope.launch {
            try {
                if (!client.hasSession()) client.login()
                val first = if (all) client.all(selected) { loaded, total -> message = "正在加载 $loaded / $total 条" }
                    else client.page(selected, if (more && previous != null) previous.current + 1 else 1)
                val result = if (more) (oldRows + first.data.rows).distinctBy { it.id } else first.data.rows
                if (!client.isCurrent(expected)) return@launch
                bound = first; rows = result
                summary = try { client.summary(selected) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (!client.isCurrent(expected)) { bound = null; rows = emptyList(); summary = null; return@launch }
                message = if (summary == null) "流水已查询，学校收支汇总暂不可用" else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (client.isCurrent(expected)) message = when ((error as? CardHistoryException)?.reason) {
                    CardHistoryFailure.AUTHENTICATION -> "学校登录未完成，请检查学校账号。"
                    CardHistoryFailure.INVALID_CREDENTIALS -> "学校未接受账号密码，请在账号设置中核对。"
                    CardHistoryFailure.INTERACTION_REQUIRED -> "请在学校页面继续登录。"
                    CardHistoryFailure.LOGIN_REQUIRED -> "会话已过期，请再点查询重新登录。"
                    CardHistoryFailure.NETWORK -> "学校服务暂时无法连接，请稍后重试。"
                    CardHistoryFailure.ACCESS_DENIED -> "学校暂不允许登录，请稍后再试。"
                    CardHistoryFailure.UPSTREAM -> "学校登录服务暂不可用，请稍后重试。"
                    else -> "本次查询未完成，已保留本页先前结果。"
                }
            } finally { loading = false }
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
    val shown = if (visible == null) emptyList() else rows.filter { search.isBlank() || it.title.contains(search.trim(), true) || it.time.contains(search.trim()) }
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(owner?.studentId?.let(AccountMask::mask) ?: "尚未设置可用学校账号")
                if (!available) Button(onClick = accountSettings) { Text("前往学校账号设置") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = month == null, enabled = !loading && loginAttempt == null, onClick = { month = null; bound = null; rows = emptyList(); summary = null }, label = { Text("全部日期") })
                    FilterChip(selected = month != null, enabled = !loading && loginAttempt == null, onClick = { monthDialog = true }, label = { Text(month?.toString() ?: "按月") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { query() }, enabled = available && !loading && loginAttempt == null) { Text("登录并查询") }
                    OutlinedButton(onClick = { query(all = true) }, enabled = available && !loading && loginAttempt == null) { Text("加载全部") }
                }
                if (client.supportsInteractiveLogin) {
                    OutlinedButton(onClick = ::startVerification, enabled = available && !loading && loginAttempt == null) {
                        Text("网页登录")
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            }
            summary?.takeIf { visible != null }?.let { data -> item { Text("学校汇总：收入 ¥ ${money(data.incomeFen)} / 支出 ¥ ${money(data.expenseFen)}") } }
            if (visible != null) {
                item {
                    Text("已加载 ${rows.size} / ${visible.data.total} 条")
                    OutlinedTextField(search, { search = it }, enabled = !loading && loginAttempt == null, label = { Text("搜索已加载记录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                items(shown, key = { it.id }) { row -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(row.title); Text("${row.time}    ¥ ${if (row.amountFen > 0) "+" else ""}${money(row.amountFen)}")
                } } }
                if (visible.current < visible.data.pages) item { OutlinedButton(onClick = { query(more = true) }, enabled = !loading && available) { Text("加载更多") } }
            }
        }
    }
    if (monthDialog) CardHistoryMonthDialog(
        initialMonth = month ?: YearMonth.now(),
        onDismiss = { monthDialog = false },
        onConfirm = { value ->
            if (value != month) {
                month = value; bound = null; rows = emptyList(); summary = null
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
