package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryCaptchaRequiredException
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryFloor
import com.upcpocket.app.data.library.LibraryReservation
import com.upcpocket.app.data.library.LibraryReservationMatch
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatException
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySessionExpiredException
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.library.LibraryAccountAuthFailure
import com.upcpocket.app.library.LibraryAccountLoginResult
import com.upcpocket.app.library.LibraryService
import com.upcpocket.app.ui.components.GlassCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 原生座位预约：登录走官方网页，查询、选座、确认、取消都在 App 内完成。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySeatScreen(
    service: LibraryService,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    val repo = service.repository
    val accountState by service.accountState.collectAsStateWithLifecycle()
    if (accountState.blocksPersonalSessions) {
        LibraryAccountBlocked(accountState, onBack)
        return
    }
    val session by repo.session.collectAsStateWithLifecycle()
    val factory = remember(repo) {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibrarySeatViewModel(repo, service.sessionStore) as T
        }
    }
    val vm: LibrarySeatViewModel = viewModel(factory = factory)
    val state = vm.state
    var restoringSession by remember { mutableStateOf(false) }
    LaunchedEffect(accountState.status, accountState.generation, accountState.cleanupRequired) {
        if (repo.session.value == null && service.rememberLoginEnabled()) {
            restoringSession = true
            try {
                service.restoreSavedLibrarySession()
            } finally {
                restoringSession = false
            }
        }
    }
    LaunchedEffect(session) {
        if (session != null) vm.ensureLoaded()
    }
    LaunchedEffect(state.sessionExpired, state.expiredToken) {
        if (state.sessionExpired) {
            val expiredToken = state.expiredToken ?: return@LaunchedEffect
            if (service.signOutIfCurrent(expiredToken)) {
                vm.resetAfterLogout()
            }
        }
    }

    if (session == null) {
        if (restoringSession) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text("正在验证已保存的图书馆会话…")
            }
        } else {
            LibraryLoginGate(service = service, onBack = onBack, onLogin = onLogin)
        }
        return
    }

    var mode by rememberSaveable { mutableStateOf(SeatMode.AREA) }
    val areaListState = rememberLazyListState()
    val reservationListState = rememberLazyListState()

    if (state.room != null) {
        LibrarySeatDetailPage(
            state = state,
            vm = vm,
            onExit = vm::closeRoom,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("图书馆座位")
                        Text(
                            "唐岛湾 · libseat",
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
                actions = {
                    IconButton(onClick = { vm.logout() }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "退出登录")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeatModeChip("选座", mode == SeatMode.AREA) { mode = SeatMode.AREA }
                SeatModeChip("预约记录", mode == SeatMode.RESERVATIONS) {
                    mode = SeatMode.RESERVATIONS
                    vm.refreshReservations()
                }
            }
            when (mode) {
                SeatMode.AREA -> LibrarySeatAreaContent(
                    state = state,
                    listState = areaListState,
                    onBuilding = vm::selectBuilding,
                    onDate = vm::selectDate,
                    onFloor = vm::selectFloor,
                    onRetry = vm::queryRooms,
                    onRoom = vm::openRoom,
                    onRefresh = vm::queryRooms,
                )
                SeatMode.RESERVATIONS -> LibrarySeatReservationContent(
                    state = state,
                    listState = reservationListState,
                    onRefresh = vm::refreshReservations,
                    onRetry = vm::refreshReservations,
                    onCancel = vm::cancel,
                )
            }
        }
    }
}

private enum class SeatMode { AREA, RESERVATIONS }

@Composable
private fun SeatModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun LibrarySeatAreaContent(
    state: LibrarySeatViewModel.UiState,
    listState: LazyListState,
    onBuilding: (LibraryBuilding) -> Unit,
    onDate: (String) -> Unit,
    onFloor: (Int?) -> Unit,
    onRetry: () -> Unit,
    onRoom: (LibraryRoom) -> Unit,
    onRefresh: () -> Unit,
) {
    val filters = state.filters
    val visible = state.visibleSeatRooms()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (filters == null) {
            item {
                CompactNotice(
                    title = if (state.error == null) "正在读取馆区" else "馆区读取失败",
                    body = state.error ?: if (state.loading) "请稍候。" else "暂时没有拿到馆区/日期。",
                )
                if (!state.loading) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        } else {
            item {
                CompactFilterPanel(
                    state = state,
                    filters = filters,
                    onBuilding = onBuilding,
                    onDate = onDate,
                    onFloor = onFloor,
                    onRefresh = onRefresh,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "可预约区域",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (state.selectedFloor == null) {
                            "${visible.size} 个"
                        } else {
                            "${visible.size} 个 · ${state.floors.firstOrNull { it.floorNum == state.selectedFloor }?.floorName ?: "${state.selectedFloor} 楼"}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            state.error?.let { message ->
                item {
                    CompactNotice("暂时无法读取", message)
                    TextButton(onClick = onRetry, enabled = !state.loading) { Text("重试") }
                }
            }
            if (!state.loading && state.error == null && visible.isEmpty()) {
                item {
                    CompactNotice(
                        "没有可预约区域",
                        "当前馆区、日期和楼层没有返回区域；可以换日期或楼层再查。",
                    )
                }
            }
            items(visible, key = { it.roomId }) { room ->
                SeatRoomRow(room = room, onClick = { onRoom(room) })
            }
            item {
                Text(
                    "余座与总数来自学校当前查询。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun CompactFilterPanel(
    state: LibrarySeatViewModel.UiState,
    filters: LibraryFilters,
    onBuilding: (LibraryBuilding) -> Unit,
    onDate: (String) -> Unit,
    onFloor: (Int?) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("馆区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新区域")
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            filters.buildings.forEach { building ->
                FilterChip(
                    selected = state.building?.id == building.id,
                    onClick = { onBuilding(building) },
                    enabled = !state.loading,
                    label = { Text(building.name) },
                )
            }
        }
        Text("日期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            filters.dates.forEach { date ->
                FilterChip(
                    selected = state.date == date,
                    onClick = { onDate(date) },
                    enabled = !state.loading,
                    label = { Text(shortDate(date)) },
                )
            }
        }
        if (state.floors.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("楼层", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(
                    selected = state.selectedFloor == null,
                    onClick = { onFloor(null) },
                    label = { Text("全部") },
                )
                state.floors.forEach { floor ->
                    FilterChip(
                        selected = state.selectedFloor == floor.floorNum,
                        onClick = { onFloor(floor.floorNum) },
                        label = { Text(floor.floorName.ifBlank { "${floor.floorNum} 楼" }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySeatReservationContent(
    state: LibrarySeatViewModel.UiState,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onCancel: (LibraryReservation) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("我的预约", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.reservationsUpdatedAt?.let { "更新于 ${clockLabel(it)}" } ?: "尚未读取",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !state.loading && !state.reservationsBusy) {
                    Text(if (state.reservationsBusy) "刷新中…" else "刷新")
                }
            }
        }
        state.reservationsError?.let { message ->
            item {
                CompactNotice("预约状态读取失败", message)
                TextButton(onClick = onRetry, enabled = !state.loading) { Text("重新核对") }
            }
        }
        if (state.reservations.isEmpty()) {
            item {
                CompactNotice(
                    "当前没有预约记录",
                    if (state.reservationsUpdatedAt != null) "已成功读取，当前账号没有进行中的预约。"
                    else "还没有成功读取，不能判断是否有预约。",
                )
            }
        } else {
            items(state.reservations, key = { it.id }) { reservation ->
                ReservationCard(
                    reservation = reservation,
                    enabled = !state.loading && state.reservationsError == null,
                    pending = reservation.id in state.pendingCancelIds,
                    onCancel = { onCancel(reservation) },
                )
            }
        }
        item {
            Text(
                "取消前会二次确认；真实结果以学校返回和刷新后的记录为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SeatRoomRow(room: LibraryRoom, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${room.floorLabel} · ${room.remainingLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                room.maxDurationLabel?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                room.occupancy?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.widthIn(min = 64.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(if ((room.freeSeats ?: 0) > 0) "选座" else "查看")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibrarySeatDetailPage(
    state: LibrarySeatViewModel.UiState,
    vm: LibrarySeatViewModel,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    val context = LocalContext.current
    val room = state.room
    val seat = state.seat
    val time = if (state.start != null && state.end != null) {
        "${state.start.value}–${state.end.value}" +
            (durationText(state.start, state.end)?.let { " · $it" } ?: "")
    } else {
        "选择时间"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(room?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${state.date.orEmpty()} · ${room?.floorLabel.orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回区域")
                    }
                },
                actions = {
                    TextButton(onClick = { openSeatOfficial(context) }) { Text("官方页") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(seat?.name?.let { "已选 $it" } ?: "请选择座位", fontWeight = FontWeight.SemiBold)
                        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            when {
                                seat == null -> Unit
                                state.start == null || state.end == null -> vm.openTimeSheet()
                                else -> vm.requestReservation()
                            }
                        },
                        enabled = when {
                            seat == null -> false
                            state.start == null || state.end == null -> seat.selectable && !state.loading
                            else -> state.canConfirmReservation()
                        },
                    ) {
                        Text(
                            when {
                                seat == null -> "选择座位"
                                state.start == null || state.end == null -> "选择时间"
                                else -> "确认预约"
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.AccessTime, contentDescription = null, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("日期 ${state.date.orEmpty()}", style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (state.start != null && state.end != null) {
                                "已选时段 ${state.start.value}–${state.end.value}" +
                                    (durationText(state.start, state.end)?.let { " · $it" } ?: "") +
                                    "；点底部可改"
                            } else {
                                "先点选座位，再选择使用时间"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::openTimeSheet, enabled = seat != null) { Text("改时间") }
                }
            }
            state.error?.let { message ->
                item {
                    CompactNotice("座位操作未完成", message)
                }
            }
            item {
                SeatLegend()
            }
            item {
                if (state.loading && state.layout == null) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("正在读取座位布局…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    state.layout?.let { layout ->
                        SeatMap(
                            layout = layout,
                            selectedId = seat?.id,
                            onSelect = vm::chooseSeat,
                        )
                    } ?: CompactNotice("没有座位布局", "学校未返回该区域的座位布局，不能显示虚构平面图。")
                }
            }
            item {
                Text(
                    "点可用座位选择；布局可横向、纵向滑动。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                state.notice?.let { notice ->
                    Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (state.showTimeDialog && seat != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = vm::closeTimeDialog,
            sheetState = sheetState,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("选择使用时间", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${room?.name.orEmpty()} · ${seat.name} · ${state.date.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (room?.maxDurationLabel != null) {
                    Text(
                        room.maxDurationLabel.orEmpty() + "；结束时间由学校按座位和开始时间返回。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "结束时间由学校按座位和开始时间返回。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("开始时间", style = MaterialTheme.typography.labelLarge)
                if (state.startTimes.isEmpty()) {
                    Text("该座位当天没有可预约的开始时间。", color = MaterialTheme.colorScheme.error)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.startTimes.forEach { option ->
                            FilterChip(
                                selected = state.start?.id == option.id,
                                onClick = { vm.chooseStart(option) },
                                label = { Text(option.value) },
                            )
                        }
                    }
                }
                Text(
                    "结束时间" + if (state.endTimes.isNotEmpty()) "（学校返回 ${state.endTimes.size} 项）" else "",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (state.start == null) {
                    Text("先选择开始时间。", style = MaterialTheme.typography.bodySmall)
                } else if (state.endTimes.isEmpty()) {
                    Text("该开始时间没有可选的结束时间。", color = MaterialTheme.colorScheme.error)
                } else {
                    if (state.endTimes.size == 1) {
                        Text(
                            "学校当前只返回 1 个结束时间；不是应用把时长固定为一小时。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.endTimes.forEach { option ->
                            FilterChip(
                                selected = state.end?.id == option.id,
                                onClick = { vm.chooseEnd(option) },
                                label = { Text(option.value) },
                            )
                        }
                    }
                }
                if (state.start != null && state.end != null) {
                    Text(
                        "已选 ${state.start.value}–${state.end.value}" +
                            (durationText(state.start, state.end)?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = {
                        vm.closeTimeDialog()
                    },
                    enabled = state.start != null && state.end != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("完成") }
            }
        }
    }

    if (state.showConfirm && seat != null) {
        AlertDialog(
            onDismissRequest = vm::dismissConfirm,
            title = { Text("确认预约") },
            text = {
                Text(
                    "${room?.name.orEmpty()} · ${seat.name}\n" +
                        "${state.date.orEmpty()} ${state.start?.value.orEmpty()}–${state.end?.value.orEmpty()}" +
                        (durationText(state.start, state.end)?.let { " · $it" } ?: "") + "\n\n" +
                        "由学校系统处理；应用不会自动重试或抢座。",
                )
            },
            confirmButton = {
                TextButton(enabled = state.canConfirmReservation(), onClick = vm::confirmReservation) {
                    Text("确认提交")
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("返回") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeatLegend() {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendItem(MaterialTheme.colorScheme.primary, "可用")
        LegendItem(MaterialTheme.colorScheme.error, "已约/使用中")
        LegendItem(MaterialTheme.colorScheme.tertiary, "暂离")
        LegendItem(MaterialTheme.colorScheme.outline, "不可选")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun CompactNotice(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun LibrarySeatViewModel.UiState.visibleSeatRooms(): List<LibraryRoom> =
    rooms.filter { selectedFloor == null || it.floor == selectedFloor }

internal fun LibrarySeatViewModel.UiState.withStartOptions(
    start: LibraryTimeOption,
    ends: List<LibraryTimeOption>,
): LibrarySeatViewModel.UiState =
    copy(
        start = start,
        end = null,
        endTimes = ends,
        loading = false,
        error = if (ends.isEmpty()) "该开始时间没有可选的结束时间" else null,
    )

internal fun LibrarySeatViewModel.UiState.clearTimeSelection(): LibrarySeatViewModel.UiState =
    copy(
        startTimes = emptyList(),
        endTimes = emptyList(),
        start = null,
        end = null,
        showTimeDialog = false,
        showConfirm = false,
    )

internal fun LibrarySeatViewModel.UiState.afterCloseRoom(): LibrarySeatViewModel.UiState =
    copy(
        room = null,
        layout = null,
        seat = null,
        startTimes = emptyList(),
        endTimes = emptyList(),
        start = null,
        end = null,
        showTimeDialog = false,
        showConfirm = false,
        error = null,
    )

internal fun LibrarySeatViewModel.UiState.canConfirmReservation(): Boolean {
    val selectedSeat = seat ?: return false
    val selectedStart = start ?: return false
    val selectedEnd = end ?: return false
    val currentLayout = layout ?: return false
    if (loading || !selectedSeat.selectable) return false
    if (currentLayout.seats.none { it.id == selectedSeat.id && it.selectable }) return false
    if (endTimes.none { it.id == selectedEnd.id }) return false
    return timeDurationMinutes(selectedStart, selectedEnd) != null
}

internal fun timeDurationMinutes(start: LibraryTimeOption?, end: LibraryTimeOption?): Int? {
    if (start == null || end == null) return null
    fun parse(value: String): Int? {
        val parts = value.trim().split(':')
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
    val startMinute = parse(start.value) ?: return null
    val endMinute = parse(end.value) ?: return null
    return (endMinute - startMinute).takeIf { it > 0 }
}

internal fun durationText(start: LibraryTimeOption?, end: LibraryTimeOption?): String? =
    timeDurationMinutes(start, end)?.let { minutes ->
        when {
            minutes < 60 -> "$minutes 分钟"
            minutes % 60 == 0 -> "${minutes / 60} 小时"
            else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        }
    }

private fun shortDate(value: String): String = value.takeLast(5)

private fun openSeatOfficial(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://libspace.upc.edu.cn/libseat/"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryLoginGate(
    service: LibraryService,
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val accountState by service.accountState.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canAutoLogin = accountState.isCredentialSaved && service.hasSavedAccount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图书馆座位") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("需要学校统一认证", style = MaterialTheme.typography.titleLarge)
            Text(
                if (canAutoLogin) {
                    "可以使用本机加密保存的学校账号自动登录；学校要求验证码或二次验证时，" +
                        "改走学校官方网页登录。"
                } else {
                    "尚未保存可用的学校账号。请使用学校官方网页登录；登录后仍可查询馆区、选座和取消预约。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canAutoLogin) {
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                when (val result = service.loginWithSavedAccount()) {
                                    LibraryAccountLoginResult.Success -> Unit
                                    is LibraryAccountLoginResult.Failure -> {
                                        busy = false
                                        error = libraryAuthMessage(result.reason)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                busy = false
                                error = "自动登录失败，请改用学校网页登录"
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "正在使用学校账号登录…" else "使用已保存学校账号登录")
                }
            }
            OutlinedButton(
                onClick = onLogin,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (canAutoLogin) "打开学校网页登录（验证码/二次验证）" else "使用学校统一认证登录")
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "自动登录只使用本机受控保存的学校账号；密码不会进入本插件。学校返回的本人账号" +
                    "与已保存账号不一致时，不会保存会话。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun libraryAuthMessage(reason: LibraryAccountAuthFailure): String = when (reason) {
    LibraryAccountAuthFailure.INVALID_CREDENTIALS -> "学校账号或密码未被接受，请更新学校账号后重试"
    LibraryAccountAuthFailure.INTERACTION_REQUIRED -> "学校要求验证码或二次验证，请使用网页登录完成"
    LibraryAccountAuthFailure.NETWORK_UNAVAILABLE -> "无法连接学校认证服务，请检查网络"
    LibraryAccountAuthFailure.TLS_ERROR -> "学校认证服务的 TLS 连接失败，未降级"
    LibraryAccountAuthFailure.UPSTREAM_ERROR -> "学校认证服务暂时不可用，请稍后重试"
    LibraryAccountAuthFailure.PROTOCOL_ERROR -> "学校登录返回了无法识别的结果，请改用网页登录"
    LibraryAccountAuthFailure.ACCOUNT_UNAVAILABLE -> "当前没有可自动登录的已保存学校账号"
    LibraryAccountAuthFailure.ACCOUNT_CHANGED -> "账号状态已变化，请重新打开图书馆"
    LibraryAccountAuthFailure.ACCESS_DENIED -> "学校拒绝了本次认证，请检查账号或稍后重试"
    LibraryAccountAuthFailure.IDENTITY_MISMATCH ->
        "学校返回的本人账号与已保存账号不一致，未保存会话"
    LibraryAccountAuthFailure.PERSISTENCE_FAILED ->
        "登录成功，但本机加密保存失败，未保留会话"
    LibraryAccountAuthFailure.SESSION_EXPIRED ->
        "学校登录会话已失效，请重新通过学校统一认证登录"
}

@Composable
private fun ReservationCard(
    reservation: LibraryReservation,
    enabled: Boolean,
    pending: Boolean,
    onCancel: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(reservation.location.ifBlank { "座位" }, fontWeight = FontWeight.SemiBold)
                Text(
                    if (pending) "待确认" else reservation.statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (pending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "${reservation.date} · ${reservation.begin}–${reservation.end}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (pending) {
                Text(
                    "取消请求已发出，最终状态尚未核对；请刷新，不会自动重试。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (reservation.cancellable && !pending) {
                TextButton(enabled = enabled, onClick = onCancel) { Text("取消预约") }
            }
        }
    }
}

/** 上一次成功读取的时间（HH:mm）。 */
private fun clockLabel(epochMs: Long): String {
    val time = java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

@Composable
internal fun SeatMap(
    layout: LibrarySeatLayout,
    selectedId: String?,
    onSelect: (LibrarySeat) -> Unit,
) {
    val byPosition = remember(layout) { layout.seats.associateBy { it.row to it.col } }
    val cell = 38.dp
    val h = rememberScrollState()
    val v = rememberScrollState()
    Box(
        Modifier.fillMaxWidth().heightIn(max = 430.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .verticalScroll(v)
            .padding(8.dp),
    ) {
        Column(Modifier.horizontalScroll(h)) {
            for (row in 0 until layout.rows) {
                Row {
                    for (col in 0 until layout.cols) {
                        val seat = byPosition[row to col]
                        if (seat == null) {
                            Spacer(Modifier.size(cell))
                        } else {
                            SeatCell(
                                seat = seat,
                                selected = seat.id == selectedId,
                                cell = cell,
                                onClick = { onSelect(seat) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatCell(
    seat: LibrarySeat,
    selected: Boolean,
    cell: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val color = when {
        selected -> MaterialTheme.colorScheme.tertiary
        !seat.enabled || seat.localOnly -> MaterialTheme.colorScheme.surfaceContainerHighest
        seat.status == "FREE" || seat.status == "YESSELECT" -> MaterialTheme.colorScheme.primaryContainer
        seat.status == "AWAY" -> MaterialTheme.colorScheme.tertiaryContainer
        seat.status == "IN_USE" || seat.status == "BOOKED" || seat.status == "FULL" ->
            MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val textColor = when {
        selected -> MaterialTheme.colorScheme.onTertiary
        !seat.enabled || seat.localOnly -> MaterialTheme.colorScheme.onSurfaceVariant
        seat.status == "FREE" || seat.status == "YESSELECT" -> MaterialTheme.colorScheme.onPrimaryContainer
        seat.status == "AWAY" -> MaterialTheme.colorScheme.onTertiaryContainer
        seat.status == "IN_USE" || seat.status == "BOOKED" || seat.status == "FULL" ->
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val status = com.upcpocket.app.data.library.LibrarySeatStatus.label(seat.status)
    Box(
        Modifier.size(cell).padding(1.5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .semantics { contentDescription = "座位 ${seat.name}，$status" }
            .clickable(enabled = seat.selectable, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seat.name.takeLast(5),
            fontSize = 8.sp,
            lineHeight = 9.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = textColor,
        )
    }
}

internal class LibrarySeatViewModel(
    private val repo: LibrarySeatRepository,
    private val sessionStore: SchoolSessionStore,
) : ViewModel() {
    data class UiState(
        val filters: LibraryFilters? = null,
        val building: LibraryBuilding? = null,
        val date: String? = null,
        val rooms: List<LibraryRoom> = emptyList(),
        val floors: List<LibraryFloor> = emptyList(),
        val selectedFloor: Int? = null,
        val room: LibraryRoom? = null,
        val layout: LibrarySeatLayout? = null,
        val seat: LibrarySeat? = null,
        val startTimes: List<LibraryTimeOption> = emptyList(),
        val endTimes: List<LibraryTimeOption> = emptyList(),
        val start: LibraryTimeOption? = null,
        val end: LibraryTimeOption? = null,
        val reservations: List<LibraryReservation> = emptyList(),
        /** 最近一次成功读取“我的预约”的时间；失败时保留它，界面据此标注旧数据。 */
        val reservationsUpdatedAt: Long? = null,
        val reservationsError: String? = null,
        /** 已提交取消、但最终状态尚未核对的预约 id。 */
        val pendingCancelIds: Set<String> = emptySet(),
        val reservationsBusy: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        val sessionExpired: Boolean = false,
        /** 触发失效的会话 token；只用于确认没有并发新登录，不写日志。 */
        val expiredToken: String? = null,
        val showTimeDialog: Boolean = false,
        val showConfirm: Boolean = false,
    )

    var state by mutableStateOf(UiState())
        private set
    private var roomRequestGeneration = 0L
    private var timeRequestGeneration = 0L
    private var roomDetailRequestGeneration = 0L

    fun ensureLoaded() {
        if (state.filters != null || state.loading) return
        loadFilters()
    }

    fun loadFilters() {
        val requestToken = repo.session.value?.token
        run {
            state = state.copy(loading = true, error = null)
            viewModelScope.launch {
                try {
                    val filters = repo.filters()
                    val building = state.building ?: filters.buildings.first()
                    val date = state.date ?: filters.dates.first()
                    state = state.copy(
                        filters = filters,
                        building = building,
                        date = date,
                        loading = false,
                    )
                    refreshReservations()
                    queryRooms()
                } catch (e: Exception) {
                    fail(e, requestToken)
                }
            }
        }
    }

    fun selectBuilding(value: LibraryBuilding) {
        state = state.copy(
            building = value,
            rooms = emptyList(),
            floors = emptyList(),
            selectedFloor = null,
            room = null,
            layout = null,
            seat = null,
            error = null,
        ).clearTimeSelection()
        queryRooms()
    }

    fun selectDate(value: String) {
        state = state.copy(
            date = value,
            rooms = emptyList(),
            floors = emptyList(),
            selectedFloor = null,
            room = null,
            layout = null,
            seat = null,
            error = null,
        ).clearTimeSelection()
        queryRooms()
    }

    fun selectFloor(floor: Int?) {
        if (state.selectedFloor == floor) return
        state = state.copy(
            selectedFloor = floor,
            room = null,
            layout = null,
            seat = null,
        ).clearTimeSelection()
    }

    fun queryRooms() {
        val building = state.building ?: return
        val date = state.date ?: return
        val requestToken = repo.session.value?.token
        val generation = ++roomRequestGeneration
        state = state.copy(loading = true, error = null, rooms = emptyList(), room = null, layout = null, seat = null)
        viewModelScope.launch {
            try {
                val floors = runCatching { repo.floors(building.id) }.getOrDefault(emptyList())
                val names = floors.associate { it.floorNum to it.floorName }
                val rooms = repo.rooms(building.id, date)
                    .map { if (it.floorName == null) it.copy(floorName = names[it.floor]) else it }
                    .sortedWith(compareBy({ it.floor }, { it.name }))
                if (generation != roomRequestGeneration) return@launch
                state = state.copy(
                    rooms = rooms,
                    floors = floors,
                    selectedFloor = state.selectedFloor?.takeIf { floor ->
                        rooms.any { it.floor == floor }
                    },
                    loading = false,
                )
            } catch (e: Exception) {
                if (generation != roomRequestGeneration) return@launch
                fail(e, requestToken)
            }
        }
    }

    fun openRoom(room: LibraryRoom) {
        val date = state.date ?: return
        val requestToken = repo.session.value?.token
        val generation = ++roomDetailRequestGeneration
        timeRequestGeneration++
        state = state.copy(
            loading = true,
            error = null,
            room = room,
            layout = null,
            seat = null,
        ).clearTimeSelection()
        viewModelScope.launch {
            try {
                val layout = repo.layout(room.roomId, date)
                if (generation != roomDetailRequestGeneration || state.room?.roomId != room.roomId) {
                    return@launch
                }
                state = state.copy(layout = layout, loading = false)
            } catch (e: Exception) {
                if (generation == roomDetailRequestGeneration) fail(e, requestToken)
            }
        }
    }

    fun chooseSeat(seat: LibrarySeat) {
        if (!seat.selectable) return
        val date = state.date ?: return
        val requestToken = repo.session.value?.token
        val generation = ++timeRequestGeneration
        state = state.copy(
            loading = true,
            error = null,
            seat = seat,
            startTimes = emptyList(),
            endTimes = emptyList(),
            start = null,
            end = null,
            showTimeDialog = false,
        )
        viewModelScope.launch {
            try {
                val starts = repo.startTimes(seat.id, date)
                if (generation != timeRequestGeneration || state.seat?.id != seat.id) return@launch
                state = state.copy(
                    startTimes = starts,
                    loading = false,
                    error = if (starts.isEmpty()) "该座位当天没有可预约时间" else null,
                )
            } catch (e: Exception) {
                if (generation == timeRequestGeneration) fail(e, requestToken)
            }
        }
    }

    fun closeRoom() {
        timeRequestGeneration++
        roomDetailRequestGeneration++
        state = state.afterCloseRoom()
    }

    fun openTimeSheet() {
        if (state.seat != null) {
            state = state.copy(showTimeDialog = true, showConfirm = false)
        }
    }

    fun chooseStart(option: LibraryTimeOption) {
        val date = state.date ?: return
        val seat = state.seat ?: return
        val requestToken = repo.session.value?.token
        val generation = ++timeRequestGeneration
        state = state.copy(
            loading = true,
            start = option,
            end = null,
            endTimes = emptyList(),
            error = null,
        )
        viewModelScope.launch {
            try {
                val ends = repo.endTimes(seat.id, date, option.id)
                if (generation != timeRequestGeneration ||
                    state.seat?.id != seat.id ||
                    state.start?.id != option.id
                ) {
                    return@launch
                }
                state = state.withStartOptions(option, ends)
            } catch (e: Exception) {
                if (generation == timeRequestGeneration) fail(e, requestToken)
            }
        }
    }

    fun chooseEnd(option: LibraryTimeOption) {
        if (state.start == null) return
        if (state.endTimes.none { it.id == option.id }) return
        state = state.copy(end = option, error = null)
    }

    fun closeTimeDialog() {
        state = state.copy(showTimeDialog = false, showConfirm = false)
    }

    fun requestReservation() {
        if (!state.canConfirmReservation()) {
            state = state.copy(error = "所选座位或时段已不可用，请重新选择")
            return
        }
        state = state.copy(showConfirm = true)
    }

    fun dismissConfirm() {
        state = state.copy(showConfirm = false)
    }

    fun confirmReservation() {
        if (!state.canConfirmReservation()) {
            state = state.copy(showConfirm = false, error = "所选座位或时段已不可用，请重新选择")
            return
        }
        val date = state.date ?: return
        val seat = state.seat ?: return
        val start = state.start ?: return
        val end = state.end ?: return
        val beforeIds = state.reservations.map { it.id }.toSet()
        val requestToken = repo.session.value?.token
        state = state.copy(loading = true, error = null, notice = null, showConfirm = false)
        viewModelScope.launch {
            // 请求与最终状态分开：请求失败/超时也不等于“没预约上”，先查询核对再下结论。
            val requestError = try {
                repo.reserve(date, seat.id, start.id, end.id)
                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e
            }
            if (requestError is LibrarySessionExpiredException) {
                fail(requestError, requestToken)
                return@launch
            }
            val checked = runCatching { repo.myReservations() }
            checked.onSuccess { list ->
                val current = activeReservations(list)
                val matched = current.firstOrNull { it.id !in beforeIds }?.takeIf {
                    LibraryReservationMatch.matches(it, date, start.value, end.value, seat.name)
                }
                val legacyMatchWithoutNewId = current.firstOrNull {
                    it.id in beforeIds && LibraryReservationMatch.matches(
                        it, date, start.value, end.value, seat.name,
                    )
                }
                val notice = when {
                    matched != null ->
                        "预约成功，已在“我的预约”中核对到该座位与完整时段（记录 ${matched.id}）。"
                    legacyMatchWithoutNewId != null ->
                        "学校已接受请求，但“我的预约”里只有同步前就存在的记录，不能证明本次预约成功；请刷新核对，不要重复提交。"
                    requestError != null ->
                        "预约请求结果不确定（${requestError.friendly()}），核对后也没有看到新记录；请先刷新核对，不要重复提交。"
                    else ->
                        "学校已接受预约请求，但“我的预约”暂未出现新记录；结果待核对，请刷新，不要重复提交。"
                }
                state = state.copy(
                    loading = false,
                    reservations = current,
                    reservationsUpdatedAt = System.currentTimeMillis(),
                    reservationsError = null,
                    showTimeDialog = false,
                    notice = notice,
                )
            }.onFailure { error ->
                state = state.copy(
                    loading = false,
                    notice = if (requestError == null) {
                        "预约请求已提交，但核对“我的预约”失败（${error.friendly()}）；结果待确认，请稍后刷新，不要重复提交。"
                    } else {
                        "预约请求与核对都没有成功（${requestError.friendly()}）；结果不确定，请先刷新核对，不要重复提交。"
                    },
                )
            }
        }
    }

    fun refreshReservations() {
        if (state.reservationsBusy) return
        val requestToken = repo.session.value?.token
        state = state.copy(reservationsBusy = true)
        viewModelScope.launch {
            loadReservations(requestToken)
            state = state.copy(reservationsBusy = false)
        }
    }

    /** 读取“我的预约”。失败时保留上一次已确认的记录，只标记“刷新失败 + 旧数据时间”。 */
    private suspend fun loadReservations(requestToken: String?) {
        try {
            val list = repo.myReservations()
            state = state.copy(
                reservations = activeReservations(list),
                reservationsUpdatedAt = System.currentTimeMillis(),
                reservationsError = null,
                pendingCancelIds = state.pendingCancelIds.intersect(activeReservations(list).map { it.id }.toSet()),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is LibrarySessionExpiredException) {
                state = state.copy(
                    sessionExpired = true,
                    expiredToken = requestToken,
                )
                return
            }
            state = state.copy(
                reservationsError = "刷新“我的预约”失败：${e.friendly()}。" +
                    "下面仍是上一次成功读取的记录" +
                    (state.reservationsUpdatedAt?.let { "（${timeLabel(it)}）" } ?: "") +
                    "，不代表当前状态。",
            )
        }
    }

    fun cancel(reservation: LibraryReservation) {
        val requestToken = repo.session.value?.token
        state = state.copy(
            loading = true,
            error = null,
            notice = null,
            pendingCancelIds = state.pendingCancelIds + reservation.id,
        )
        viewModelScope.launch {
            val requestError = try {
                repo.cancel(reservation.id)
                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e
            }
            if (requestError is LibrarySessionExpiredException) {
                fail(requestError, requestToken)
                return@launch
            }
            // 取消请求返回只说明“学校收到”，最终状态必须再查一次。
            val checked = runCatching { repo.myReservations() }
            checked.onSuccess { list ->
                val current = activeReservations(list)
                val stillThere = current.any { it.id == reservation.id }
                state = state.copy(
                    loading = false,
                    reservations = current,
                    reservationsUpdatedAt = System.currentTimeMillis(),
                    reservationsError = null,
                    pendingCancelIds = if (stillThere) {
                        state.pendingCancelIds // 仍显示 = 结果未确认，保留“待确认”标记
                    } else {
                        state.pendingCancelIds - reservation.id
                    },
                    notice = when {
                        !stillThere && requestError == null ->
                            "取消已由学校确认，该记录不再出现在“我的预约”中。"
                        !stillThere ->
                            "取消请求返回不确定（${requestError?.friendly()}），但核对后记录已不在“我的预约”中。"
                        requestError == null ->
                            "取消请求已提交，但学校仍返回该预约；结果待确认，请稍后刷新，不会自动重试。"
                        else ->
                            "取消请求结果不确定（${requestError.friendly()}），学校仍返回该预约；请刷新核对，不会自动重试。"
                    },
                )
            }.onFailure { error ->
                // 关键：核对失败时不清空列表，也不能把“没读到”当成“已取消”。
                state = state.copy(
                    loading = false,
                    reservationsError = "取消请求已提交，但核对最终状态失败：${error.friendly()}。" +
                        "列表仍是取消前的记录；请稍后刷新，不会自动重复提交。",
                    notice = "该预约标记为“待确认”：请求已发出，最终状态尚未核对。",
                )
            }
        }
    }

    private fun activeReservations(list: List<LibraryReservation>) =
        list.filter { it.status == "RESERVE" || it.status == "CHECK_IN" || it.status == "AWAY" }

    private fun timeLabel(epochMs: Long): String {
        val time = java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "%02d:%02d".format(time.hour, time.minute)
    }

    private fun Throwable.friendly(): String = when (this) {
        is LibrarySeatException -> message ?: "学校服务异常"
        else -> message ?: javaClass.simpleName
    }

    /** 只退出图书馆：清本业务 token 与 libspace Cookie，不动其它业务。 */
    fun logout() {
        repo.clearSession()
        sessionStore.clearLibrary()
        LibrarySeatCookies.clearSeatCookies()
        resetAfterLogout()
    }

    /** Service 已按 token/锁确认并清理后，只重置本 ViewModel 的展示状态。 */
    fun resetAfterLogout() {
        state = UiState()
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun clearNotice() {
        state = state.copy(notice = null)
    }

    private fun fail(e: Exception, requestToken: String?) {
        if (e is CancellationException) throw e
        val message = when (e) {
            is LibrarySessionExpiredException -> {
                state = state.copy(
                    sessionExpired = true,
                    expiredToken = requestToken,
                )
                e.message
            }
            is LibraryCaptchaRequiredException -> e.message
            is LibrarySeatException -> e.message
            else -> e.message ?: "图书馆服务查询失败，请稍后重试"
        }
        state = state.copy(loading = false, error = message)
    }
}
