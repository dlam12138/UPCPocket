package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.ecard.CampusCardBalance
import com.upcpocket.app.data.ecard.CampusCardOutcome
import com.upcpocket.app.data.ecard.CampusCardProtocol
import com.upcpocket.app.data.prefs.SchoolSessionStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 校园卡余额（原生只读）。
 *
 * 状态区分（都必须在界面上说得清）：
 *  - 还没查过 → 提示先登录；
 *  - 已恢复/已登录但查询失败 → 显示失败原因，并**保留上次成功的值与时间**；
 *  - 成功 → 显示卡余额、待圈存、口径与时间；
 *  - 会话失效 → 明确“需要重新登录”，不自动循环重登。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCardScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<CampusCardBalance?>(null) }
    var fetchedAt by remember { mutableStateOf<Instant?>(null) }
    var lastSuccess by remember { mutableStateOf<Pair<CampusCardBalance, Instant>?>(null) }
    var needsLogin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf(false) }

    suspend fun load(useRemember: Boolean) {
        loading = true
        error = null
        try {
            if (useRemember) AppGraph.campusCardService.restore()
            when (val outcome = AppGraph.campusCardService.refresh()) {
                is CampusCardOutcome.Success -> {
                    needsLogin = false
                    balance = outcome.balance
                    val now = Instant.now()
                    fetchedAt = now
                    lastSuccess = outcome.balance to now
                }
                CampusCardOutcome.NeedsLogin -> {
                    needsLogin = true
                    balance = null
                    fetchedAt = null
                }
                is CampusCardOutcome.Unavailable -> {
                    // 断网/维护：保留“已登录”与上次成功的值，只说明这次没更新。
                    needsLogin = false
                    error = outcome.message
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            needsLogin = false
            error = e.message ?: "查询校园卡余额失败，请稍后重试"
        } finally {
            loading = false
        }
    }

    // key 必须是常量：如果在 effect 里改自己的 key，协程会在下一个挂起点被取消，
    // 首屏就永远停在“既没登录也没错误”的空状态（真机复现过）。
    LaunchedEffect(Unit) {
        if (restored) return@LaunchedEffect
        restored = true
        val remember = AppGraph.uiPrefsStore.flow.first().rememberSchoolLogin
        val verified = remember && AppGraph.campusCardService.restore()
        val unavailable = AppGraph.campusCardService.lastFailure
        // 恢复会话时 restore() 已经验证过一次；直接用它的结果，不刚启动就连发两次请求。
        val cached = if (verified) AppGraph.campusCardService.snapshot() else null
        if (cached != null) {
            val now = Instant.now()
            balance = cached
            fetchedAt = now
            lastSuccess = cached to now
            needsLogin = false
        } else if (AppGraph.campusCardService.hasSession()) {
            load(useRemember = false)
        } else if (!unavailable.isNullOrBlank()) {
            // 有密文但这次连不上：不谎报“需要重新登录”，也不显示“未登录”。
            needsLogin = false
            error = "$unavailable。已保留上次的登录状态，联网后可直接重试。"
        } else {
            needsLogin = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校园卡余额") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "余额由学校校园卡平台（慧新E校）自己的接口返回，App 只读不改。" +
                    "不提供充值、绑定、改密或流水查询。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            val shown = balance
            val stale = lastSuccess
            val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())

            if (shown != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        shown.cards.forEach { card ->
                            Text(
                                text = card.amountYuan.toPlainString() + " 元",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = listOfNotNull(
                                    card.cardName.takeIf { it.isNotBlank() },
                                    SchoolSessionStore.maskAccount(card.account).takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (card.pendingCents != 0L) {
                                Text(
                                    text = "其中待圈存 ${card.pendingYuan.toPlainString()} 元（线上充值后需到食堂刷卡机写卡）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            if (card.lost || card.frozen) {
                                Text(
                                    text = listOfNotNull(
                                        "已挂失".takeIf { card.lost },
                                        "已冻结".takeIf { card.frozen },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Text(
                            text = "口径：${shown.basis.label}" +
                                (fetchedAt?.let { " · 读取于 ${time.format(it)}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                if (shown.basis == CampusCardProtocol.Basis.CARD_ONLY) {
                    Text(
                        text = "学校平台当前配置为“卡余额＋待圈存”，未包含电子账户；" +
                            "若你还有独立钱包余额，以学校页面显示为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (stale != null && shown == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("上次成功读取", style = MaterialTheme.typography.labelMedium)
                            stale.first.cards.forEach { card ->
                                Text(
                                    "${card.amountYuan.toPlainString()} 元 · ${card.cardName}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                time.format(stale.second) + "（不是实时值）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (needsLogin) {
                Text(
                    text = if (AppGraph.campusCardService.rememberedAccount().isNotBlank()) {
                        "上次登录的账号：" +
                            SchoolSessionStore.maskAccount(AppGraph.campusCardService.rememberedAccount()) +
                            "。会话已失效，需要重新登录。"
                    } else {
                        "需要先在学校统一认证页面登录，才能读到本人余额。"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    onClick = onLogin,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("登录学校统一认证") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { load(useRemember = false) } },
                        enabled = !loading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(if (loading) "正在查询…" else "重新查询") }
                    OutlinedButton(
                        onClick = {
                            AppGraph.campusCardService.signOut()
                            balance = null
                            fetchedAt = null
                            lastSuccess = null
                            error = null
                            needsLogin = true
                        },
                        enabled = !loading,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("退出校园卡") }
                }
                Text(
                    text = "换号：先点“退出校园卡”，再登录另一个学校账号；旧账号的令牌与余额不会保留。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "数据来源：学校校园卡平台 /berserker-app/ykt/tsm/queryCard。" +
                    "金额单位为元，服务器按“分”返回；口径取自平台自身配置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
