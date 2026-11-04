package com.upcpocket.app.ui.ecard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.card.CardBalanceService
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.platform.AccountMask
import com.upcpocket.app.platform.RefreshInvalidatedException
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 校园卡余额功能区（主页上的紧凑卡片）。
 *
 * 查询目标只来自当前统一账号的受控学号，不提供独立输入框，也不从旧快照、组件
 * 参数或草稿里取目标。M5 把说明折叠到“数据说明”，但保留脱敏绑定、缓存清除、
 * 查询失败历史值、Keystore/账号归属等安全语义。
 */
@Composable
fun DirectCardBalanceSection(
    service: CardBalanceService,
    onOpenAccountSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by service.bindingStatus.collectAsStateWithLifecycle(
        initialValue = service.bindingStatus(),
    )
    val snapshot by service.snapshot.collectAsStateWithLifecycle(initialValue = null)

    var loading by remember { mutableStateOf(true) }
    var querying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            service.cached()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(status.binding) {
        error = null
        fresh = false
    }

    fun query() {
        if (loading || querying || status.binding == null) return
        querying = true
        loading = true
        error = null
        fresh = false
        scope.launch {
            try {
                service.refreshCurrent()
                fresh = true
            } catch (_: RefreshInvalidatedException) {
                // 账号切换/改密/清缓存使本次请求失效：静默丢弃，不能被“取消协程”代替。
                fresh = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.takeIf { it.isNotBlank() } ?: "查询失败，请重试"
            } finally {
                querying = false
                loading = false
            }
        }
    }

    fun clearCache() {
        if (loading || querying) return
        loading = true
        error = null
        scope.launch {
            try {
                service.clearCache()
                fresh = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "余额缓存清除未完成，请重试；学校账号未被删除。"
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
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("校园卡余额", style = MaterialTheme.typography.titleMedium)
                    BindingStatus(
                        availability = status.availability,
                        maskedStudentId = status.binding?.studentId?.let(AccountMask::mask).orEmpty(),
                    )
                }
                Spacer(Modifier.width(10.dp))
                IconBadge(
                    icon = Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    size = 40.dp,
                )
            }

            if (status.availability == CardBindingAvailability.READY) {
                Text(
                    "学校账号已保存，尚未验证学校密码；余额查询成功不代表 CAS 登录成功。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            snapshot?.let { data ->
                BalanceSnapshot(
                    data = data,
                    fresh = fresh,
                    failed = error != null,
                    bindingGeneration = status.binding?.credentialGeneration?.value,
                )
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            error?.let {
                Text(
                    "本次操作失败：$it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("card_error"),
                )
            }

            if (status.availability == CardBindingAvailability.READY) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = ::query,
                        enabled = !loading && !querying,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("card_query"),
                    ) {
                        if (querying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (querying) "正在读取…" else if (snapshot == null) "查询余额" else "刷新余额")
                    }
                    OutlinedButton(
                        onClick = onOpenAccountSettings,
                        modifier = Modifier.testTag("card_open_account"),
                    ) { Text("账号") }
                }
            } else {
                OutlinedButton(
                    onClick = onOpenAccountSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_open_account"),
                ) { Text("前往学校账号设置") }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.availability == CardBindingAvailability.READY || snapshot != null) {
                    TextButton(
                        onClick = ::clearCache,
                        enabled = !loading && !querying,
                        modifier = Modifier.testTag("card_clear_cache"),
                    ) { Text("清除余额缓存") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text("数据说明")
                    Icon(
                        imageVector = if (showDetails) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = null,
                        modifier = Modifier.padding(start = 2.dp).size(18.dp),
                    )
                }
            }

            AnimatedVisibility(visible = showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "清除余额缓存不会删除学校账号或密码；账号失效时仍由账号清理统一处理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "数据来源：学校校园卡余额 getCardInfo，金额按“分”返回、按元显示。" +
                            "该接口使用 HTTP 明文传输学号与金额；余额缓存位于 App 私有、不参与备份的目录，" +
                            "带内部账号归属与读取时间，未单独加密。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "不提供充值、转账或绑定；不显示假余额。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BindingStatus(
    availability: CardBindingAvailability,
    maskedStudentId: String,
) {
    val text = when (availability) {
        CardBindingAvailability.LOADING -> "正在读取本机账号…"
        CardBindingAvailability.NO_ACCOUNT -> "请先设置学校账号。未绑定时不发余额请求，也不显示旧学号或旧余额。"
        CardBindingAvailability.NEEDS_RECONFIGURATION ->
            "本机账号凭据无法读取，需要重新保存后才能查询余额。"
        CardBindingAvailability.CLEANING ->
            "正在完成旧账号清理，期间不查询、不显示旧身份数值。"
        CardBindingAvailability.STUDENT_ID_UNAVAILABLE ->
            "当前账号登录名不是 8–16 位数字学号，余额查询明确不可用；不会猜测或截取其他数字。"
        CardBindingAvailability.READY ->
            "查询目标：${maskedStudentId.ifBlank { "学号不可用" }}"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (
            availability == CardBindingAvailability.NEEDS_RECONFIGURATION ||
            availability == CardBindingAvailability.STUDENT_ID_UNAVAILABLE
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.testTag("card_binding_status"),
    )
}

@Composable
private fun BalanceSnapshot(
    data: DirectCardSnapshot,
    fresh: Boolean,
    failed: Boolean,
    bindingGeneration: Long?,
) {
    val currentCredential = bindingGeneration == data.credentialGeneration
    val label = when {
        failed -> "历史缓存：本次查询失败，显示上次成功读取值"
        fresh && currentCredential -> "本次成功读取"
        !currentCredential -> "同号历史余额：账号凭据已更新，尚未重新查询"
        else -> "上次成功读取（未更新）"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_snapshot"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${data.balanceText} 元",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = "过渡余额：${data.transitionText} 元",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "学号：${AccountMask.mask(data.studentId)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
            Text(
                text = "读取于 ${time.format(Instant.ofEpochMilli(data.fetchedAt))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
