package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.ecard.DirectCardBalanceProtocol
import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.ui.components.GlassCard
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 校园卡余额功能区（直接嵌在首页，不是二级页面）。
 *
 * 只做一件事：输入本人学号 → 查询/刷新 → 余额与过渡余额。
 * 学号通过 [AppGraph.cardInputRepository] 独立持久保存：没有查询成功也能在重启后回填；
 * 余额快照另存于 `card-balance-v1.json`，两者互不覆盖。输入变化时旧快照仍带原学号与时间。
 */
@Composable
fun DirectCardBalanceSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repository = remember {
        DirectCardBalanceRepository(
            DirectCardBalanceStore(File(context.noBackupFilesDir, "card-balance-v1.json")),
        )
    }
    val savedId by AppGraph.cardInputRepository.studentId
        .collectAsStateWithLifecycle(initialValue = null)

    var input by rememberSaveable { mutableStateOf("") }
    var hydrated by rememberSaveable { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<DirectCardSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persist(value: String) {
        scope.launch { AppGraph.cardInputRepository.save(value) }
    }

    // 只回填一次；用户已经输入（或旋转后由 rememberSaveable 恢复）时不覆盖。
    LaunchedEffect(savedId) {
        val saved = savedId ?: return@LaunchedEffect
        if (!hydrated && input.isBlank()) input = saved
        hydrated = true
    }
    LaunchedEffect(Unit) {
        try {
            snapshot = repository.cached()
        } finally {
            loading = false
        }
    }

    fun query() {
        if (loading) return
        val requestedId = input.trim()
        loading = true
        error = null
        fresh = false
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

    fun clear() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                repository.clear()
                AppGraph.cardInputRepository.clear()
                snapshot = null
                input = ""
                error = null
                fresh = false
            } finally {
                loading = false
            }
        }
    }

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "输入本人学号查询余额与过渡余额，两项分别展示；不需要登录，也不提供充值。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { raw ->
                    val clean = raw.filter { it.isDigit() }.take(16)
                    if (clean != input) {
                        input = clean
                        fresh = false
                        persist(clean)
                    }
                },
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

            val shown = snapshot?.takeIf { input.isNotBlank() }
            shown?.let { data ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (fresh) "本次成功读取" else "上次成功读取（未更新）",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text("学号：${SchoolSessionStore.maskAccount(data.studentId)}")
                        Text("校园卡余额", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${data.balanceText} 元",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            "过渡余额：${data.transitionText} 元",
                            style = MaterialTheme.typography.titleMedium,
                        )
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
                        "上方是学号 ${SchoolSessionStore.maskAccount(data.studentId)} 的旧记录；" +
                            "当前输入的学号尚未查询成功。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                "两项余额分别展示，不相加。学号与读取时间保存在本机，供离线查看。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (input.isNotBlank() || snapshot != null) {
                TextButton(onClick = ::clear, enabled = !loading) {
                    Text("清除学号与余额记录")
                }
            }
            Text(
                "数据来源：学校校园卡余额接口的 getCardInfo，金额按“分”返回、按元显示。" +
                    "该接口目前使用 HTTP 明文传输学号与金额；记录保存在 App 私有、不参与备份的文件中，" +
                    "未单独加密，可随时清除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
