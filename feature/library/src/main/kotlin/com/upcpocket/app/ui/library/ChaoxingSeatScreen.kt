package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoom
import com.upcpocket.app.data.upc.UpcOfficialEntryLinks
import com.upcpocket.app.library.ChaoxingService
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaoxingSeatScreen(
    service: ChaoxingService,
    initialKind: ChaoxingSeatKind = ChaoxingSeatKind.SEAT,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val session by service.session.collectAsStateWithLifecycle()
    val vm: ChaoxingSeatViewModel = viewModel(
        factory = ChaoxingSeatViewModel.factory(service, initialKind),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(session) {
        if (session != null) vm.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("超星座位")
                        Text(
                            "原生只读 · ${state.kind.campusLabel}",
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChaoxingSeatKind.entries.forEach { kind ->
                        FilterChip(
                            selected = state.kind == kind,
                            onClick = { vm.selectKind(kind) },
                            label = { Text(kind.campusLabel) },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.dates.forEach { date ->
                        FilterChip(
                            selected = state.selectedDate == date,
                            onClick = { vm.selectDate(date) },
                            label = { Text(date.takeLast(5)) },
                        )
                    }
                }
            }
            if (session == null) {
                item {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("需要一次 Chaoxing 网页登录", fontWeight = FontWeight.SemiBold)
                            Text(
                                "应用不会读取密码；登录完成后只用本人学号核验并建立内存只读会话。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onOpenLogin) { Text("网页登录") }
                            OutlinedButton(onClick = {
                                openChaoxingOfficial(context, state.officialUrl)
                            }) { Text("打开官方座位页") }
                        }
                    }
                }
            } else {
                item { SectionHeader("区域 · ${state.selectedDate}") }
                if (state.busy) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
                state.error?.let { message ->
                    item {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { vm.refresh() }) { Text("重试") }
                    }
                }
                items(state.rooms, key = { it.id }) { room ->
                    SeatRoomCard(room = room, onClick = { vm.openRoom(room) })
                }
                item {
                    OutlinedButton(onClick = {
                        openChaoxingOfficial(context, state.officialUrl)
                    }) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("官方预约页（写操作仅在学校页面）")
                    }
                }
            }
        }
    }

    state.detail?.let { detail ->
        AlertDialog(
            onDismissRequest = { vm.closeRoom() },
            title = {
                Column {
                    Text(detail.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "容量 ${detail.capacity} · ${formatMinute(detail.startMinute)}–" +
                            "${formatMinute(detail.endMinute)} · ${detail.timeUnit} 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("选择开始时段（每次预览 60 分钟）", style = MaterialTheme.typography.labelMedium)
                    val starts = seatStartOptions(detail)
                    starts.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { start ->
                                FilterChip(
                                    selected = state.selectedStart == start,
                                    onClick = { vm.selectStart(start) },
                                    label = { Text(formatMinute(start)) },
                                )
                            }
                        }
                    }
                    if (state.selectedStart != null) {
                        Text(
                            "当前已用座位 ${state.usedSeats.size} 个；只读，不提交预约。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.detailError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    SectionHeader("座位图")
                    if (detail.places.isEmpty()) {
                        Text("学校未返回座位图。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        val sorted = detail.places.sortedBy { it.seatNum }
                        sorted.chunked(8).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { place ->
                                    val used = place.seatNum in state.usedSeats
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (used) {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                        modifier = Modifier.size(38.dp),
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            Text(
                                                place.seatNum,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (used) {
                                                    MaterialTheme.colorScheme.onErrorContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "红色为所选时段已用；座位位置来自学校坐标，按编号栅格展示。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { openChaoxingOfficial(context, state.officialUrl) }) {
                    Text("官方预约")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.closeRoom() }) { Text("关闭") }
            },
        )
    }
}

private fun seatStartOptions(
    detail: com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail,
): List<Int> {
    if (detail.timeUnit <= 0) return emptyList()
    val result = mutableListOf<Int>()
    var start = detail.startMinute
    while (start < detail.endMinute) {
        result += start
        start += detail.timeUnit
    }
    return result
}

@Composable
private fun SeatRoomCard(room: ChaoxingSeatRoom, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatMinute(room.startMinute)}–${formatMinute(room.endMinute)} · " +
                    "座位 ${room.capacity} 个" +
                    (room.usedCount?.let { " · 当前已用 $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun openChaoxingOfficial(context: android.content.Context, url: String) {
    if (!UpcOfficialEntryLinks.isAllowedChaoxingHost(url)) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
