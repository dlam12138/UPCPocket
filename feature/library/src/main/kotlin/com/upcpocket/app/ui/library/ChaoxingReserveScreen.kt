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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.data.chaoxing.ChaoxingReserveRoom
import com.upcpocket.app.data.chaoxing.ChaoxingTenant
import com.upcpocket.app.data.upc.UpcOfficialEntryLinks
import com.upcpocket.app.library.ChaoxingService
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.SectionHeader
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaoxingReserveScreen(
    service: ChaoxingService,
    initialTenant: ChaoxingTenant = ChaoxingTenant.GUZHENKOU,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val session by service.session.collectAsStateWithLifecycle()
    val vm: ChaoxingReserveViewModel = viewModel(
        factory = ChaoxingReserveViewModel.factory(service, initialTenant),
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
                        Text("超星研讨间")
                        Text(
                            "原生只读 · ${state.tenant.campusLabel}",
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
                    ChaoxingTenant.entries.forEach { tenant ->
                        FilterChip(
                            selected = state.tenant == tenant,
                            onClick = { vm.selectTenant(tenant) },
                            label = { Text(tenant.campusLabel) },
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
                                "应用不会读取密码；登录后只读房间/开放时段，预约仍在学校页面完成。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onOpenLogin) { Text("网页登录") }
                            OutlinedButton(onClick = {
                                openReserveOfficial(context, state.officialUrl)
                            }) { Text("打开官方研讨间页") }
                        }
                    }
                }
            } else {
                item { SectionHeader("研讨间 · ${state.tenant.campusLabel}") }
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
                    ReserveRoomCard(room = room, onClick = { vm.openRoom(room) })
                }
                item {
                    OutlinedButton(onClick = {
                        openReserveOfficial(context, state.officialUrl)
                    }) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("官方预约页（含团队/附件/选座）")
                    }
                }
            }
        }
    }

    state.detail?.let { room ->
        val weekday = LocalDate.now(ZoneId.of("Asia/Shanghai")).dayOfWeek.value
        val today = room.todayInterval(weekday)
        AlertDialog(
            onDismissRequest = { vm.closeRoom() },
            title = {
                Column {
                    Text(room.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            room.minUser?.let { "最少 $it 人" },
                            room.maxUser?.let { "最多 $it 人" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("今日开放时段", style = MaterialTheme.typography.labelMedium)
                    Text(today ?: "学校未返回今日开放时段", style = MaterialTheme.typography.bodyMedium)
                    if (room.intervals.isNotEmpty()) {
                        Text(
                            room.intervals.joinToString("；") {
                                "周${it.weekday} ${it.start}-${it.end}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (room.details.isNotBlank()) {
                        Text(room.details, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "团队/附件/选座等流程未在本页提交；请使用官方页面完成真实预约。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { openReserveOfficial(context, state.officialUrl) }) {
                    Text("官方预约")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.closeRoom() }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ReserveRoomCard(room: ChaoxingReserveRoom, onClick: () -> Unit) {
    val weekday = LocalDate.now(ZoneId.of("Asia/Shanghai")).dayOfWeek.value
    val today = room.todayInterval(weekday)
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
                listOfNotNull(
                    today?.let { "今日 $it" },
                    room.minUser?.let { "最少 $it 人" },
                    room.maxUser?.let { "最多 $it 人" },
                ).joinToString(" · ").ifBlank { "开放时段以学校页面为准" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun openReserveOfficial(context: android.content.Context, url: String) {
    if (!UpcOfficialEntryLinks.isAllowedChaoxingHost(url)) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
