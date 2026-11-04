package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.data.nspace.NspaceOfficialLinks
import com.upcpocket.app.data.nspace.NspaceRoom
import com.upcpocket.app.library.NspaceService
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.components.StatusPill

/**
 * 唐岛湾东馆研讨间的原生只读浏览。
 *
 * 这不是预约提交页：房间、容量、开放日期来自 nspace 的已观察只读接口；真正的预约、
 * 参与人和取消仍在学校官方 nspace 页面完成，避免在没有实测写协议时伪造成功。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NspaceScreen(
    service: NspaceService,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenReservations: () -> Unit,
    onOpenWebLogin: () -> Unit,
) {
    val context = LocalContext.current
    val vm: NspaceViewModel = viewModel(factory = NspaceViewModel.factory(service))
    val state by vm.state.collectAsStateWithLifecycle()
    val accountState by service.accountState.collectAsStateWithLifecycle()
    var bookingRoom by remember { mutableStateOf<NspaceRoom?>(null) }
    val hasAccount = accountState.status == SchoolAccountStatus.SAVED_UNVERIFIED
    val accountMessage = when (accountState.status) {
        SchoolAccountStatus.LOADING -> "学校账号正在恢复，完成后再登录；不会跳过账号门闩。"
        SchoolAccountStatus.NEEDS_RECONFIGURATION -> "学校账号需要重新保存。"
        SchoolAccountStatus.SWITCHING,
        SchoolAccountStatus.UPDATING,
        SchoolAccountStatus.REMOVING,
        -> "学校账号切换/清理中，暂不可登录。"
        SchoolAccountStatus.SAVED_UNVERIFIED ->
            "使用已保存学校账号完成 nspace 正常 CAS；验证码或二次验证时请点“网页登录”。"
        SchoolAccountStatus.NOT_CONFIGURED ->
            "尚未保存学校账号。先保存账号，或直接使用学校官方页面登录和预约。"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("东馆研讨间")
                        Text(
                            "唐岛湾 · nspace 原生只读浏览",
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            text = "原生只读",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "原生表单 / 官方页面",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "这里读取馆区、空间类型、开放日期和房间容量，并提供本人预约查询/取消；" +
                            "新预约仍走官方页面，不提交新预约、不签到。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { openNspaceOfficial(context) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Spacer(Modifier.padding(start = 4.dp))
                        Text("在浏览器打开学校官方 nspace 页面")
                    }
                }
            }

            if (state.session == null) {
                GlassCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "使用统一学校账号登录",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            accountMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::login, enabled = hasAccount && !state.busy) {
                                Text("登录空间预约")
                            }
                            if (!hasAccount) {
                                OutlinedButton(onClick = onOpenAccount) {
                                    Text("设置学校账号")
                                }
                            }
                        }
                        if (hasAccount) {
                            OutlinedButton(onClick = onOpenWebLogin, enabled = !state.busy) {
                                Text("网页登录（验证码/二次验证）")
                            }
                        }
                        if (state.busy) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(Modifier.height(18.dp))
                                Text("正在认证…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        state.error?.let { error ->
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else {
                SessionCard(
                    name = state.session!!.user.name.ifBlank { state.session!!.user.username },
                    busy = state.busy,
                    onRefresh = vm::refresh,
                    onOpenReservations = onOpenReservations,
                )
                Filters(state, vm)
                RoomList(
                    state = state,
                    onOfficial = { room ->
                        state.selectedDate?.let { date ->
                            openNspaceRoom(context, room.id, date)
                        }
                    },
                    onBook = { room -> bookingRoom = room },
                )
                Pager(state, vm)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    val roomToBook = bookingRoom
    val bookDate = state.selectedDate
    if (roomToBook != null && bookDate != null) {
        NspaceBookingDialog(
            service = service,
            room = roomToBook,
            date = bookDate,
            onDismiss = { bookingRoom = null },
            onOpenOfficial = { openNspaceRoom(context, roomToBook.id, bookDate) },
        )
    }
}

@Composable
private fun SessionCard(
    name: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onOpenReservations: () -> Unit,
) {
    GlassCard {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("nspace 会话已建立", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "本人：$name · 仅内存，退出/换号即清除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text("刷新")
                }
            }
            TextButton(onClick = onOpenReservations, enabled = true) {
                Text("查看我的预约 / 取消")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Filters(state: NspaceUiState, vm: NspaceViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("馆区")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.venues.forEach { venue ->
                FilterChip(
                    selected = venue.id == state.selectedVenueId,
                    onClick = { vm.selectVenue(venue.id) },
                    label = { Text(venue.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        if (state.roomTypes.isNotEmpty()) {
            SectionHeader("空间类型")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.roomTypes.forEach { type ->
                    FilterChip(
                        selected = type.id == state.selectedRoomTypeId,
                        onClick = { vm.selectRoomType(type.id) },
                        enabled = type.enabled,
                        label = { Text(type.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        if (state.dates.isNotEmpty()) {
            SectionHeader("开放日期")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.dates.forEach { date ->
                    FilterChip(
                        selected = date == state.selectedDate,
                        onClick = { vm.selectDate(date) },
                        label = { Text(date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomList(
    state: NspaceUiState,
    onOfficial: (NspaceRoom) -> Unit,
    onBook: (NspaceRoom) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(
                if (state.totalCount > 0) "房间（${state.totalCount}）" else "房间",
                modifier = Modifier.weight(1f),
            )
            Text(
                "共 ${state.totalPage} 页",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!state.busy && state.rooms.isEmpty() && state.error == null) {
            Text(
                "该日期没有学校返回的房间；这不等于所有时段都可预约。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.rooms.forEach { room -> RoomCard(room, onOfficial, onBook) }
    }
}

@Composable
private fun RoomCard(
    room: NspaceRoom,
    onOfficial: (NspaceRoom) -> Unit,
    onBook: (NspaceRoom) -> Unit,
) {
    GlassCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.MeetingRoom,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(start = 8.dp))
                Text(
                    room.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val details = buildList {
                if (room.floor.isNotBlank()) add(room.floor)
                room.minUser?.let { min ->
                    room.maxUser?.let { max -> add("$min–$max 人") } ?: add("至少 $min 人")
                }
                room.area?.let { add(it) }
                room.openTime?.let { open ->
                    room.closeTime?.let { close -> add("$open–$close") } ?: add(open)
                }
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    text = room.status?.let { "状态 $it" } ?: "状态未提供",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (room.isSign == true) {
                    StatusPill(
                        text = "需签到",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            TextButton(onClick = { onBook(room) }) {
                Text("原生预约表单")
            }
            TextButton(onClick = { onOfficial(room) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.padding(start = 4.dp))
                Text("在官方页面预约此房间")
            }
        }
    }
}

private fun openNspaceRoom(context: android.content.Context, roomId: String, date: String) {
    val url = runCatching { NspaceOfficialLinks.roomDetailUrl(roomId, date) }.getOrNull()
        ?: return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openNspaceOfficial(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://libspace.upc.edu.cn/nspace/"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun Pager(state: NspaceUiState, vm: NspaceViewModel) {    if (state.totalPage <= 1) return
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = vm::previousPage, enabled = !state.busy && state.page > 1) {
            Text("上一页")
        }
        Text(
            "第 ${state.page} / ${state.totalPage} 页",
            style = MaterialTheme.typography.labelSmall,
        )
        TextButton(
            onClick = vm::nextPage,
            enabled = !state.busy && state.page < state.totalPage,
        ) {
            Text("下一页")
        }
    }
}
