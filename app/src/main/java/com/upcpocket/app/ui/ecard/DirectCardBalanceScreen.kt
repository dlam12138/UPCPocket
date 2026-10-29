package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.upcpocket.app.data.ecard.*
import com.upcpocket.app.data.prefs.SchoolSessionStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 校园卡余额（只读）。
 *
 * 产品只有一个入口：输入本人学号，直接读取学校余额接口的“卡余额 + 过渡余额”。
 * 不需要登录校园卡平台，也没有流水、充值或绑定能力。
 *
 * 状态区分：成功、失败（保留上次成功值与时间）、以及“清除记录/更换学号”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCardScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember {
        DirectCardBalanceRepository(
            DirectCardBalanceStore(File(context.noBackupFilesDir, "card-balance-v1.json")),
        )
    }
    var snapshot by remember { mutableStateOf<DirectCardSnapshot?>(null) }
    // Student identifiers and balances are not saved in Activity instance state.
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            snapshot = repository.cached()
            input = snapshot?.studentId.orEmpty()
        } finally {
            loading = false
        }
    }

    fun query() {
        if (loading) return
        loading = true
        error = null
        fresh = false
        val requestedId = input.trim()
        scope.launch {
            try {
                snapshot = repository.refresh(requestedId)
                fresh = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "查询失败，请重试"
            } finally {
                loading = false
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("校园卡余额") }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        })
    }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "输入本人学号查询校园卡余额与过渡余额，两项分别展示；不需要登录，也不提供充值。",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                enabled = !loading,
                label = { Text("本人学号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = ::query,
                enabled = !loading && DirectCardBalanceProtocol.validStudentId(input.trim()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (loading) "正在读取…" else "查询 / 刷新余额") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text("本次查询失败：$it", color = MaterialTheme.colorScheme.error) }
            snapshot?.let { data ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (fresh) "本次成功读取" else "上次成功读取（未更新）",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text("学号：${SchoolSessionStore.maskAccount(data.studentId)}")
                        Text("校园卡余额", style = MaterialTheme.typography.titleMedium)
                        Text("${data.balanceText} 元", style = MaterialTheme.typography.headlineLarge)
                        Text("过渡余额：${data.transitionText} 元", style = MaterialTheme.typography.titleMedium)
                        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.systemDefault())
                        Text(
                            "读取于 ${time.format(Instant.ofEpochMilli(data.fetchedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (data.studentId != input.trim()) {
                    Text(
                        "上方仍是之前学号的记录；当前输入的学号尚未查询成功。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                "两项余额分别展示，不相加。仅保存学号、两项金额和读取时间，供离线查看。",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    loading = true
                    scope.launch {
                        try {
                            repository.clear()
                            snapshot = null
                            input = ""
                            error = null
                            fresh = false
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading,
            ) { Text("清除饭卡记录 / 更换学号") }
            Text(
                "数据来源：学校校园卡余额接口的 getCardInfo，金额按“分”返回、按元显示。" +
                    "该接口目前使用 HTTP 明文传输学号与金额；记录保存在 App 私有、不参与备份的文件中，" +
                    "未单独加密，可随时清除。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
