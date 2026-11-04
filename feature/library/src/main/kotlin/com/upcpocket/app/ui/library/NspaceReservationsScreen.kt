package com.upcpocket.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.data.nspace.NspaceReservation
import com.upcpocket.app.library.NspaceService
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.components.StatusPill

/**
 * nspace 本人预约记录与取消。
 *
 * 取消是真实写操作；必须由用户在确认对话框里明确确认。测试与模拟器验收不点击确认。
 * 记录只展示房间、日期、时间、学校状态；不展示门禁密码等敏感字段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NspaceReservationsScreen(
    service: NspaceService,
    onBack: () -> Unit,
) {
    val vm: NspaceReservationsViewModel =
        viewModel(factory = NspaceReservationsViewModel.factory(service))
    val state by vm.state.collectAsStateWithLifecycle()
    val session by service.session.collectAsStateWithLifecycle()
    var pendingCancel by remember { mutableStateOf<NspaceReservation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("我的预约")
                        Text(
                            "唐岛湾东馆 · nspace",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session == null) {
                GlassCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("当前没有 nspace 会话", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "请先返回东馆研讨间完成登录；这里不会用其他会话代替。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onBack) { Text("返回登录") }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("预约记录", modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.load() }, enabled = !state.busy) {
                        Text("刷新")
                    }
                }
                if (state.totalCount > 0) {
                    Text(
                        "共 ${state.totalCount} 条 · 第 ${state.page}/${state.totalPage} 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                state.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                if (state.busy && state.reservations.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text("正在读取本人预约…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!state.busy && state.reservations.isEmpty() && state.error == null) {
                    GlassCard {
                        Text(
                            "学校返回的本人预约列表为空。空列表不等于取消成功或没有历史记录。",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.reservations.forEach { reservation ->
                    ReservationCard(
                        reservation = reservation,
                        cancelling = state.cancellingId == reservation.id,
                        onCancel = { pendingCancel = reservation },
                    )
                }
                if (state.totalPage > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = { vm.load(state.page - 1) },
                            enabled = !state.busy && state.page > 1,
                        ) { Text("上一页") }
                        Text(
                            "${state.page}/${state.totalPage}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        TextButton(
                            onClick = { vm.load(state.page + 1) },
                            enabled = !state.busy && state.page < state.totalPage,
                        ) { Text("下一页") }
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }

    pendingCancel?.let { reservation ->
        AlertDialog(
            onDismissRequest = { if (state.cancellingId == null) pendingCancel = null },
            title = { Text("确认取消真实预约？") },
            text = {
                Text(
                    "房间：${reservation.roomName.ifBlank { "未提供名称" }}\n" +
                        "日期：${reservation.onDate.take(10)}\n" +
                        "时间：${minutesToTime(reservation.begin)}–" +
                        "${minutesToTime(reservation.end)}\n\n" +
                        "确认后会向学校提交取消请求；本操作不可撤销。",
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingCancel = null },
                    enabled = state.cancellingId == null,
                ) { Text("保留预约") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCancel = null
                        vm.cancel(reservation)
                    },
                    enabled = state.cancellingId == null,
                ) { Text("确认取消") }
            },
        )
    }
}

@Composable
private fun ReservationCard(
    reservation: NspaceReservation,
    cancelling: Boolean,
    onCancel: () -> Unit,
) {
    GlassCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reservation.roomName.ifBlank { "房间名称未提供" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text = reservation.statusDescription
                        ?: "状态 ${reservation.status}",
                    containerColor = if (reservation.status == 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (reservation.status == 0) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                "${reservation.onDate.take(10)} · " +
                    "${minutesToTime(reservation.begin)}–${minutesToTime(reservation.end)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (reservation.status == 0) {
                TextButton(onClick = onCancel, enabled = !cancelling) {
                    Text(if (cancelling) "正在提交取消…" else "取消此预约")
                }
            } else {
                Text(
                    "当前状态不允许从此页取消；规则以学校页面为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun minutesToTime(minutes: Int): String {
    if (minutes < 0) return "未提供"
    val hour = minutes / 60
    val minute = minutes % 60
    return "%02d:%02d".format(hour, minute)
}
