package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 校园卡余额功能区（直接嵌在首页，不是二级页面）。
 *
 * 查询目标只来自当前统一账号的受控学号，不提供独立输入框，也不从旧快照、组件参数
 * 或草稿里取目标。清除按钮只清余额缓存，不删除学校账号或密码。
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
            Text(
                "余额查询目标来自当前统一学校账号；不需要登录，也不提供充值。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (status.availability) {
                CardBindingAvailability.LOADING -> {
                    Text("正在读取本机账号…", modifier = Modifier.testTag("card_binding_status"))
                }
                CardBindingAvailability.NO_ACCOUNT -> {
                    Text(
                        "请先设置学校账号。未绑定时不发余额请求，也不显示旧学号或旧余额。",
                        modifier = Modifier.testTag("card_binding_status"),
                    )
                }
                CardBindingAvailability.NEEDS_RECONFIGURATION -> {
                    Text(
                        "本机账号凭据无法读取，需要重新保存后才能查询余额。",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("card_binding_status"),
                    )
                }
                CardBindingAvailability.CLEANING -> {
                    Text(
                        "正在完成旧账号清理，期间不查询、不显示旧身份数值。",
                        modifier = Modifier.testTag("card_binding_status"),
                    )
                }
                CardBindingAvailability.STUDENT_ID_UNAVAILABLE -> {
                    Text(
                        "当前账号登录名不是 8–16 位数字学号，余额查询明确不可用；" +
                            "不会猜测或截取其他数字。请在账号设置中使用本人学号。",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("card_binding_status"),
                    )
                }
                CardBindingAvailability.READY -> {
                    val binding = status.binding
                    Text(
                        "当前绑定学号：${binding?.studentId?.let(AccountMask::mask).orEmpty()}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("card_binding_status"),
                    )
                    Text(
                        "学校账号已保存，尚未验证学校密码。余额接口只按学号查询、不接收密码；" +
                            "余额读取成功不代表 CAS 登录成功。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (status.availability == CardBindingAvailability.READY) {
                Button(
                    onClick = ::query,
                    enabled = !loading && !querying,
                    modifier = Modifier.fillMaxWidth().testTag("card_query"),
                ) {
                    Text(if (querying) "正在读取…" else "查询 / 刷新余额")
                }
            }

            OutlinedButton(
                onClick = onOpenAccountSettings,
                modifier = Modifier.fillMaxWidth().testTag("card_open_account"),
            ) { Text("前往学校账号设置") }

            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(
                    "本次操作失败：$it",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("card_error"),
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

            // 只要当前绑定可用就保留清除入口：清理失败后旧值已隐藏，用户仍能重试。
            if (status.availability == CardBindingAvailability.READY || snapshot != null) {
                TextButton(
                    onClick = ::clearCache,
                    enabled = !loading && !querying,
                    modifier = Modifier.testTag("card_clear_cache"),
                ) {
                    Text("仅清除余额缓存")
                }
                Text(
                    "清除余额缓存不会删除学校账号或密码；账号失效时仍由账号清理统一处理。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "数据来源：学校校园卡余额 getCardInfo，金额按“分”返回、按元显示。" +
                    "该接口使用 HTTP 明文传输学号与金额；余额缓存位于 App 私有、不参与备份的目录，" +
                    "带内部账号归属与读取时间，未单独加密。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
    Card(
        Modifier.fillMaxWidth().testTag("card_snapshot"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("学号：${AccountMask.mask(data.studentId)}")
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
}
