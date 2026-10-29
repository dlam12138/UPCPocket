package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.ecard.CampusCardSessionExpiredException
import com.upcpocket.app.data.ecard.CampusCardTransactions
import java.math.BigDecimal
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 用户明确查询后才取流水，最多在当前页面保留一页，不写磁盘。 */
@Composable
internal fun CampusCardTransactionsSection(onExpired: () -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var page by remember { mutableStateOf<CampusCardTransactions.Page?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun query(number: Int) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                page = AppGraph.campusCardService.transactions(month.atDay(1), month.atEndOfMonth(), number)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: CampusCardSessionExpiredException) {
                page = null
                onExpired()
            } catch (failure: Exception) {
                error = failure.message ?: "流水查询失败，请重试"
            } finally {
                loading = false
            }
        }
    }

    HorizontalDivider()
    Text("校园卡流水", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = !loading, onClick = { month = month.minusMonths(1); page = null; error = null }) { Text("上月") }
        Text(month.toString(), Modifier.padding(top = 12.dp))
        TextButton(enabled = !loading && month < YearMonth.now(), onClick = { month = month.plusMonths(1); page = null; error = null }) { Text("下月") }
    }
    Button(onClick = { query(1) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
        Text(if (loading) "正在查询流水…" else "查询本月流水")
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text("本次查询失败：$it。下面若有结果，为上次读取的一页。", color = MaterialTheme.colorScheme.error) }
    page?.let { result ->
        Text("$month · 共 ${result.total} 条 · 第 ${result.current} / ${result.pages.coerceAtLeast(1)} 页")
        if (result.entries.isEmpty()) Text("学校返回该月暂无流水")
        result.entries.forEach { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${entry.amountText} 元 · ${entry.summary}", style = MaterialTheme.typography.titleMedium)
                    Text("交易时间：${entry.occurredAt}", style = MaterialTheme.typography.bodySmall)
                    entry.postedAt?.takeIf { it != entry.occurredAt && it.isNotBlank() }?.let {
                        Text("记账时间：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    entry.balanceCents?.let { Text("交易后余额：${BigDecimal.valueOf(it, 2).toPlainString()} 元", style = MaterialTheme.typography.bodySmall) }
                    entry.payment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { query(result.current - 1) }, enabled = !loading && result.current > 1) { Text("上一页") }
            OutlinedButton(onClick = { query(result.current + 1) }, enabled = !loading && result.current < result.pages) { Text("下一页") }
        }
    }
    Text("流水只在本页内存中保留。交易可能延迟记账，以学校记录为准。", style = MaterialTheme.typography.bodySmall)
}
