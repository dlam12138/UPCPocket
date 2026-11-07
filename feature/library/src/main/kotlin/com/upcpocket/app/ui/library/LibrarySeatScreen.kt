package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.draw.drawBehind
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
import com.upcpocket.app.data.library.LibraryHiddenSeat
import com.upcpocket.app.data.library.LibraryReservation
import com.upcpocket.app.data.library.LibraryReservationMatch
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatException
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySessionExpiredException
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.data.library.LibraryTimeSearchResult
import com.upcpocket.app.data.prefs.LibraryHiddenSeatsStore
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
    hiddenSeatsStore: LibraryHiddenSeatsStore,
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
    val factory = remember(repo, hiddenSeatsStore) {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibrarySeatViewModel(repo, service.sessionStore, hiddenSeatsStore) as T
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
                Text("正在恢复登录…")
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
                            "唐岛湾",
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
                        "没有可预约区域，请换日期或楼层。",
                    )
                }
            }
            items(visible, key = { it.roomId }) { room ->
                SeatRoomRow(room = room, onClick = { onRoom(room) })
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
                TextButton(onClick = onRetry, enabled = !state.loading) { Text("重试") }
            }
        }
        if (state.reservations.isEmpty()) {
            item {
                CompactNotice(
                    "当前没有预约记录",
                    if (state.reservationsUpdatedAt != null) "当前没有进行中的预约。"
                    else "还没有读取成功，无法判断。",
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
                "取消后以学校记录为准。",
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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
    val snackbar = remember { SnackbarHostState() }
    var showHiddenMenu by remember { mutableStateOf(false) }
    var pickingBegin by remember { mutableStateOf(false) }
    var pickingFinish by remember { mutableStateOf(false) }

    // 隐藏后的 Snackbar + 撤销；座位名只作为本机展示快照。
    LaunchedEffect(state.undoHidden) {
        val entry = state.undoHidden ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "已隐藏座位 ${entry.nameSnapshot}",
            actionLabel = "撤销",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoHide() else vm.clearUndoHide()
    }
    state.hiddenActionError?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            vm.clearHiddenActionError()
        }
    }

    val timeMode = state.seatMode == RoomSeatMode.TIME
    val timeSummary = if (state.timeStart != null && state.timeStop != null) {
        "${state.timeStart.value}–${state.timeStop.value}" +
            (durationText(state.timeStart, state.timeStop)?.let { " · $it" } ?: "")
    } else {
        "选择时段"
    }
    val seatSummary = if (state.start != null && state.end != null) {
        "${state.start.value}–${state.end.value}" +
            (durationText(state.start, state.end)?.let { " · $it" } ?: "")
    } else {
        "选择时间"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                    TextButton(onClick = { openSeatOfficial(context) }) { Text("学校页面") }
                    Box {
                        IconButton(onClick = { showHiddenMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showHiddenMenu,
                            onDismissRequest = { showHiddenMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("隐藏的座位（${state.hiddenEntries.size}）") },
                                onClick = {
                                    showHiddenMenu = false
                                    vm.openHiddenSheet()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        if (timeMode) {
                            val chosen = state.timeSelectedSeat
                            Text(chosen?.name?.let { "已选 $it" } ?: "请选择座位", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.timeBegin != null && state.timeFinish != null) {
                                    "${state.timeBegin}–${state.timeFinish}" +
                                        (durationText(
                                            state.timeStart ?: LibraryTimeOption("", state.timeBegin),
                                            state.timeStop ?: LibraryTimeOption("", state.timeFinish),
                                        )?.let { " · $it" } ?: "")
                                } else "选择时段",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(seat?.name?.let { "已选 $it" } ?: "请选择座位", fontWeight = FontWeight.SemiBold)
                            Text(seatSummary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(
                        onClick = {
                            if (timeMode) {
                                vm.requestTimeReservation()
                            } else {
                                when {
                                    seat == null -> Unit
                                    state.start == null || state.end == null -> vm.openTimeSheet()
                                    else -> vm.requestReservation()
                                }
                            }
                        },
                        enabled = if (timeMode) {
                            state.canConfirmTimeReservation()
                        } else {
                            when {
                                seat == null -> false
                                state.start == null || state.end == null -> seat.selectable && !state.loading
                                else -> state.canConfirmReservation()
                            }
                        },
                    ) {
                        Text(
                            if (timeMode) {
                                "确认预约"
                            } else {
                                when {
                                    seat == null -> "选择座位"
                                    state.start == null || state.end == null -> "选择时间"
                                    else -> "确认预约"
                                }
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
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.seatMode == RoomSeatMode.SEAT,
                        onClick = { vm.setSeatMode(RoomSeatMode.SEAT) },
                        label = { Text("按座位选") },
                    )
                    FilterChip(
                        selected = state.seatMode == RoomSeatMode.TIME,
                        onClick = { vm.setSeatMode(RoomSeatMode.TIME) },
                        label = { Text("按时段找") },
                    )
                }
            }
            if (timeMode) {
                item {
                    TimeRangeControls(
                        begin = state.timeBegin,
                        finish = state.timeFinish,
                        onPickBegin = { pickingBegin = true },
                        onPickFinish = { pickingFinish = true },
                        onSearch = vm::searchByTime,
                        canSearch = validTimeRange(state.timeBegin, state.timeFinish) &&
                            state.timeResult?.querying != true,
                        querying = state.timeResult?.querying == true,
                    )
                }
                item {
                    TimeSearchResults(
                        state = state,
                        vm = vm,
                        onHide = vm::hideSeat,
                    )
                }
            } else {
                seatModeContent(state, vm, seat, room)
            }
            item {
                state.notice?.let { notice ->
                    Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    // 起止时间输入：系统风格 TimePicker，明确 HH:mm，不猜半小时规则。
    if (pickingBegin) {
        TimePickDialog(
            title = "选择开始",
            initial = state.timeBegin,
            onDismiss = { pickingBegin = false },
            onConfirm = { text ->
                vm.setTimeBegin(text)
                pickingBegin = false
            },
        )
    }
    if (pickingFinish) {
        TimePickDialog(
            title = "选择结束",
            initial = state.timeFinish,
            onDismiss = { pickingFinish = false },
            onConfirm = { text ->
                vm.setTimeFinish(text)
                pickingFinish = false
            },
        )
    }

    if (state.showTimeDialog && seat != null && !timeMode) {
        SeatTimeSheet(state, vm, room, seat)
    }

    if (state.showConfirm) {
        val confirmSeat = if (timeMode) state.timeSelectedSeat else seat
        val confirmStart = if (timeMode) state.timeStart else state.start
        val confirmEnd = if (timeMode) state.timeStop else state.end
        if (confirmSeat != null) {
            AlertDialog(
                onDismissRequest = vm::dismissConfirm,
                title = { Text("确认预约") },
                text = {
                    Text(
                        "${room?.name.orEmpty()} · ${confirmSeat.name}\n" +
                            "${state.date.orEmpty()} ${confirmStart?.value.orEmpty()}–${confirmEnd?.value.orEmpty()}" +
                            (durationText(confirmStart, confirmEnd)?.let { " · $it" } ?: "") + "\n\n" +
                            "提交后以学校结果为准。",
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = if (timeMode) state.canConfirmTimeReservation() else state.canConfirmReservation(),
                        onClick = if (timeMode) vm::confirmTimeReservation else vm::confirmReservation,
                    ) { Text("确认预约") }
                },
                dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("取消") } },
            )
        }
    }

    if (state.showHiddenSheet) {
        HiddenSeatsSheet(state = state, vm = vm)
    }
}

/** 按座位模式的原有内容（日期/时段提示、图例、座位图、底部说明）。 */
@OptIn(ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.seatModeContent(
    state: LibrarySeatViewModel.UiState,
    vm: LibrarySeatViewModel,
    seat: LibrarySeat?,
    room: LibraryRoom?,
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
        item { CompactNotice("座位操作未完成", message) }
    }
    item {
        SeatLegend(hiddenCount = state.hiddenSeatIds.size)
    }
    item {
        if (state.loading && state.layout == null) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在读取座位…", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            state.layout?.let { layout ->
                SeatMap(
                    layout = layout,
                    selectedId = seat?.id,
                    onSelect = vm::chooseSeat,
                    hiddenIds = state.hiddenSeatIds,
                    onLongClick = vm::hideSeat,
                )
            } ?: CompactNotice("没有座位布局", "学校未返回该区域的座位布局，不能显示虚构平面图。")
        }
    }
    item {
        Text(
            "点可用座位选择；长按可隐藏；布局可横向、纵向滑动。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(
    title: String,
    initial: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val minute = initial?.let(::parseHhmm) ?: 8 * 60
    val picker = rememberTimePickerState(
        initialHour = (minute / 60) % 24,
        initialMinute = minute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = picker) },
        confirmButton = {
            TextButton(onClick = { onConfirm(formatHhmm(picker.hour * 60 + picker.minute)) }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TimeRangeControls(
    begin: String?,
    finish: String?,
    onPickBegin: () -> Unit,
    onPickFinish: () -> Unit,
    onSearch: () -> Unit,
    canSearch: Boolean,
    querying: Boolean,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("开始时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onPickBegin, modifier = Modifier.fillMaxWidth()) {
                    Text(begin ?: "选择开始", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Column(Modifier.weight(1f)) {
                Text("结束时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onPickFinish, modifier = Modifier.fillMaxWidth()) {
                    Text(finish ?: "选择结束", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Button(
            onClick = onSearch,
            enabled = canSearch,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (querying) "查找中…" else "查找座位")
        }
        if (querying) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimeSearchResults(
    state: LibrarySeatViewModel.UiState,
    vm: LibrarySeatViewModel,
    onHide: (LibrarySeat) -> Unit,
) {
    val result = state.timeResult
    val visible = result?.visibleCandidates(state.hiddenSeatIds).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            result == null -> CompactNotice("按时段找", "选择时段，查找可约座位")
            result.querying && result.loadedPages == 0 ->
                CompactNotice("正在查找", "只查询当前房间。")
            result.failed ->
                CompactNotice("查询失败", result.failureMessage ?: "请重试")
            result.complete && result.seats.isEmpty() ->
                CompactNotice("这个时段暂无可约座位", "可以修改时段再找。")
            result.complete && visible.isEmpty() && result.seats.isNotEmpty() ->
                CompactNotice("可约座位已被隐藏", "可在右上角查看或恢复隐藏项。")
            else -> {
                // 数量：只有完整读完且无失败才写「共 N 个」，否则只写「已加载 N 个」。
                val countLabel = if (result.complete && !result.partialFailure) {
                    "共 ${result.seats.size} 个"
                } else {
                    "已加载 ${result.seats.size} 个"
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(countLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (state.hiddenSeatIds.isNotEmpty()) {
                        Text(
                            "已隐藏 ${state.hiddenSeatIds.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                visible.forEach { candidate ->
                    TimeCandidateRow(
                        candidate = candidate,
                        selected = state.timeSelectedSeat?.id == candidate.id,
                        onSelect = { vm.selectTimeCandidate(candidate) },
                        onHide = { onHide(candidate) },
                    )
                }
                when {
                    result.partialFailure ->
                        Column {
                            Text("部分结果未加载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = vm::loadMoreTime) { Text("重试") }
                        }
                    result.pageLimitReached ->
                        Text("已加载前 ${result.seats.size} 个，请缩小范围", style = MaterialTheme.typography.labelSmall)
                    result.hasMore ->
                        TextButton(onClick = vm::loadMoreTime, enabled = !result.querying) { Text("加载更多") }
                }
                if (state.timeSelecting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.error?.let { message ->
                    Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimeCandidateRow(
    candidate: LibrarySeat,
    selected: Boolean,
    onSelect: () -> Unit,
    onHide: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = candidate.timeSelectable, onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.RadioButton(
                selected = selected,
                onClick = null,
                enabled = candidate.timeSelectable,
            )
            Column(Modifier.weight(1f)) {
                Text(candidate.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val facility = buildList {
                    if (candidate.type.equals("seat", ignoreCase = true) && candidate.enabled) add("可选")
                }
                if (facility.isNotEmpty()) {
                    Text(facility.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("隐藏此座位") },
                        onClick = { menu = false; onHide() },
                        leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenSeatsSheet(
    state: LibrarySeatViewModel.UiState,
    vm: LibrarySeatViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = vm::closeHiddenSheet,
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("隐藏的座位（${state.hiddenEntries.size}）", style = MaterialTheme.typography.titleMedium)
            Text(
                "只在本机隐藏，不影响学校状态。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.hiddenEntries.isEmpty()) {
                Text("没有隐藏的座位。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.hiddenEntries.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.nameSnapshot, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.restoreHidden(entry.seatId) }) { Text("恢复") }
                    }
                }
                OutlinedButton(onClick = vm::restoreAllHidden, modifier = Modifier.fillMaxWidth()) {
                    Text("全部恢复")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SeatTimeSheet(
    state: LibrarySeatViewModel.UiState,
    vm: LibrarySeatViewModel,
    room: LibraryRoom?,
    seat: LibrarySeat,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = vm::closeTimeDialog, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("选择时间", style = MaterialTheme.typography.titleMedium)
            Text(
                "${room?.name.orEmpty()} · ${seat.name} · ${state.date.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                (room?.maxDurationLabel?.let { "$it；" } ?: "") + "结束时间由学校返回。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("开始时间", style = MaterialTheme.typography.labelLarge)
            if (state.startTimes.isEmpty()) {
                Text("当天没有可预约时间。", color = MaterialTheme.colorScheme.error)
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
            Text("结束时间", style = MaterialTheme.typography.labelLarge)
            if (state.start == null) {
                Text("先选择开始时间。", style = MaterialTheme.typography.bodySmall)
            } else if (state.endTimes.isEmpty()) {
                Text("没有可选结束时间。", color = MaterialTheme.colorScheme.error)
            } else {
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
                onClick = vm::closeTimeDialog,
                enabled = state.start != null && state.end != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("完成") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeatLegend(hiddenCount: Int = 0) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendItem(MaterialTheme.colorScheme.primary, "可用")
        LegendItem(MaterialTheme.colorScheme.error, "已约/使用中")
        LegendItem(MaterialTheme.colorScheme.tertiary, "暂离")
        LegendItem(MaterialTheme.colorScheme.outline, "不可选")
        if (hiddenCount > 0) {
            LegendItem(MaterialTheme.colorScheme.surfaceContainerHighest, "已隐藏")
        }
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
        reservationSubmitted = false,
        timeResult = null,
        timeSelectedSeat = null,
        timeStartTimes = emptyList(),
        timeEndTimes = emptyList(),
        timeStart = null,
        timeStop = null,
        timeSelecting = false,
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
        reservationSubmitted = false,
        seatMode = RoomSeatMode.SEAT,
        timeBegin = null,
        timeFinish = null,
        timeResult = null,
        timeSelectedSeat = null,
        timeStartTimes = emptyList(),
        timeEndTimes = emptyList(),
        timeStart = null,
        timeStop = null,
        timeSelecting = false,
        hiddenSeatIds = emptySet(),
        hiddenEntries = emptyList(),
        showHiddenSheet = false,
        undoHidden = null,
    )

internal fun LibrarySeatViewModel.UiState.canConfirmReservation(): Boolean {
    if (reservationSubmitted) return false
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
            Text("登录学校账号", style = MaterialTheme.typography.titleLarge)
            Text(
                if (canAutoLogin) {
                    "使用已保存的学校账号登录；需要验证码时打开学校网页。"
                } else {
                    "使用学校网页登录后即可查询、预约和取消。"
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
                                error = "登录失败，请用网页登录。"
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "正在登录…" else "用已保存账号登录")
                }
            }
            OutlinedButton(
                onClick = onLogin,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("网页登录")
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun libraryAuthMessage(reason: LibraryAccountAuthFailure): String = when (reason) {
    LibraryAccountAuthFailure.INVALID_CREDENTIALS -> "账号或密码不正确，请更新后重试。"
    LibraryAccountAuthFailure.INTERACTION_REQUIRED -> "需要验证码或二次验证，请用网页登录。"
    LibraryAccountAuthFailure.NETWORK_UNAVAILABLE -> "网络不可用，请稍后重试。"
    LibraryAccountAuthFailure.TLS_ERROR -> "无法安全连接学校服务，请稍后重试。"
    LibraryAccountAuthFailure.UPSTREAM_ERROR -> "学校服务暂时不可用，请稍后重试。"
    LibraryAccountAuthFailure.PROTOCOL_ERROR -> "登录返回异常，请用网页登录。"
    LibraryAccountAuthFailure.ACCOUNT_UNAVAILABLE -> "没有可用的已保存账号。"
    LibraryAccountAuthFailure.ACCOUNT_CHANGED -> "账号已更换，请重新打开。"
    LibraryAccountAuthFailure.ACCESS_DENIED -> "学校未允许本次登录，请稍后重试。"
    LibraryAccountAuthFailure.IDENTITY_MISMATCH ->
        "登录账号与已保存账号不一致，未保存。"
    LibraryAccountAuthFailure.PERSISTENCE_FAILED ->
        "登录成功，但本机保存失败，请重试。"
    LibraryAccountAuthFailure.SESSION_EXPIRED ->
        "登录已失效，请重新登录。"
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SeatMap(
    layout: LibrarySeatLayout,
    selectedId: String?,
    onSelect: (LibrarySeat) -> Unit,
    hiddenIds: Set<String> = emptySet(),
    onLongClick: (LibrarySeat) -> Unit = {},
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
                                hidden = seat.id in hiddenIds,
                                cell = cell,
                                onClick = { onSelect(seat) },
                                onLongClick = { onLongClick(seat) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeatCell(
    seat: LibrarySeat,
    selected: Boolean,
    hidden: Boolean,
    cell: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 个人隐藏是弱化 + 独立标记，不删除占位、不重排，也不冒充官方占用状态。
    val color = when {
        hidden -> MaterialTheme.colorScheme.surfaceContainerHighest
        selected -> MaterialTheme.colorScheme.tertiary
        !seat.enabled || seat.localOnly -> MaterialTheme.colorScheme.surfaceContainerHighest
        seat.status == "FREE" || seat.status == "YESSELECT" -> MaterialTheme.colorScheme.primaryContainer
        seat.status == "AWAY" -> MaterialTheme.colorScheme.tertiaryContainer
        seat.status == "IN_USE" || seat.status == "BOOKED" || seat.status == "FULL" ->
            MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val textColor = when {
        hidden -> MaterialTheme.colorScheme.outline
        selected -> MaterialTheme.colorScheme.onTertiary
        !seat.enabled || seat.localOnly -> MaterialTheme.colorScheme.onSurfaceVariant
        seat.status == "FREE" || seat.status == "YESSELECT" -> MaterialTheme.colorScheme.onPrimaryContainer
        seat.status == "AWAY" -> MaterialTheme.colorScheme.onTertiaryContainer
        seat.status == "IN_USE" || seat.status == "BOOKED" || seat.status == "FULL" ->
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val status = if (hidden) "已隐藏" else com.upcpocket.app.data.library.LibrarySeatStatus.label(seat.status)
    Box(
        Modifier.size(cell).padding(1.5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .then(if (hidden) Modifier.drawBehind {
                drawLine(
                    color = androidx.compose.ui.graphics.Color.Gray,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 2f,
                )
            } else Modifier)
            .semantics { contentDescription = "座位 ${seat.name}，$status" }
            .combinedClickable(
                enabled = seat.selectable || hidden,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
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
    private val hiddenSeatsStore: LibraryHiddenSeatsStore,
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
        /** 真正发出过一次提交后置位；结果未知/超时也不得再次启用同一选择的确认。 */
        val reservationSubmitted: Boolean = false,
        // ── 按时段找（与按座位模式的状态严格分开）──────────────────────
        val seatMode: RoomSeatMode = RoomSeatMode.SEAT,
        val timeBegin: String? = null,
        val timeFinish: String? = null,
        val timeResult: LibraryTimeSearchResult? = null,
        val timeSelectedSeat: LibrarySeat? = null,
        val timeStartTimes: List<LibraryTimeOption> = emptyList(),
        val timeEndTimes: List<LibraryTimeOption> = emptyList(),
        val timeStart: LibraryTimeOption? = null,
        val timeStop: LibraryTimeOption? = null,
        val timeSelecting: Boolean = false,
        // ── 本机个人隐藏 ────────────────────────────────────────────────
        val hiddenSeatIds: Set<String> = emptySet(),
        val hiddenEntries: List<LibraryHiddenSeat> = emptyList(),
        val showHiddenSheet: Boolean = false,
        val hiddenActionError: String? = null,
        val undoHidden: LibraryHiddenSeat? = null,
    )

    var state by mutableStateOf(UiState())
        private set
    private var roomRequestGeneration = 0L
    private var timeRequestGeneration = 0L
    private var roomDetailRequestGeneration = 0L
    private var timeSearchGeneration = 0L
    private var hiddenGeneration = 0L

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
        timeSearchGeneration++
        state = state.copy(
            loading = true,
            error = null,
            room = room,
            layout = null,
            seat = null,
            seatMode = RoomSeatMode.SEAT,
            timeBegin = null,
            timeFinish = null,
            hiddenSeatIds = emptySet(),
            hiddenEntries = emptyList(),
            undoHidden = null,
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
        if (state.reservationSubmitted) return
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
        state = state.copy(
            loading = true,
            error = null,
            notice = null,
            showConfirm = false,
            reservationSubmitted = true,
        )
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

    // ───────────────── 按时段找 ─────────────────

    /** 切换房间页两种模式；切模式清空另一模式的确认状态与时段结果。 */
    fun setSeatMode(mode: RoomSeatMode) {
        if (state.seatMode == mode) return
        timeSearchGeneration++      // 在途查询作废
        timeRequestGeneration++     // 在途选时作废
        state = state.copy(
            seatMode = mode,
            showTimeDialog = false,
            showConfirm = false,
            error = null,
            timeResult = null,
            timeSelectedSeat = null,
            timeStartTimes = emptyList(),
            timeEndTimes = emptyList(),
            timeStart = null,
            timeStop = null,
            timeSelecting = false,
        )
        if (mode == RoomSeatMode.TIME) loadHiddenSeats()
    }

    fun setTimeBegin(text: String) {
        onTimeInputChanged(begin = text, finish = state.timeFinish)
    }

    fun setTimeFinish(text: String) {
        onTimeInputChanged(begin = state.timeBegin, finish = text)
    }

    /** 时段变化：清空候选/选择/分页/确认状态，旧查询作废，不自动联网。 */
    private fun onTimeInputChanged(begin: String?, finish: String?) {
        timeSearchGeneration++
        timeRequestGeneration++
        state = state.copy(
            timeBegin = begin,
            timeFinish = finish,
            timeResult = null,
            timeSelectedSeat = null,
            timeStartTimes = emptyList(),
            timeEndTimes = emptyList(),
            timeStart = null,
            timeStop = null,
            timeSelecting = false,
            showConfirm = false,
            error = null,
        )
    }

    /** 点「查找座位」：只查当前房间一页；同一在途查询不重复发起。 */
    fun searchByTime() {
        val key = state.currentTimeSearchKey() ?: return
        val inFlight = state.timeResult
        if (inFlight?.querying == true && inFlight.key == key) return
        val generation = ++timeSearchGeneration
        val token = repo.session.value?.token
        val context = TimeQueryContext(generation, token, key)
        state = state.copy(
            timeResult = LibraryTimeSearchResult(key = key, querying = true),
            timeSelectedSeat = null,
            timeStartTimes = emptyList(),
            timeEndTimes = emptyList(),
            timeStart = null,
            timeStop = null,
            showConfirm = false,
            error = null,
        )
        loadTimePage(context, firstPage = true)
    }

    /** 点「加载更多」：按服务端分页前进；同一在途页不重复发起。 */
    fun loadMoreTime() {
        val current = state.timeResult ?: return
        val key = state.currentTimeSearchKey() ?: return
        if (current.key != key || current.querying || !current.hasMore || current.pageLimitReached) return
        val generation = timeSearchGeneration
        val token = repo.session.value?.token
        val context = TimeQueryContext(generation, token, key)
        state = state.copy(
            timeResult = current.copy(querying = true, partialFailure = false, failureMessage = null),
        )
        loadTimePage(context, firstPage = false)
    }

    private fun loadTimePage(context: TimeQueryContext, firstPage: Boolean) {
        val page = if (firstPage) 1 else state.timeResult?.nextPage ?: return
        viewModelScope.launch {
            try {
                val result = repo.timeSearchSeats(
                    key = context.key,
                    page = page,
                    batch = TIME_SEARCH_BATCH,
                ) { isTimeContextCurrent(context) }
                if (!isTimeContextCurrent(context)) return@launch
                val merged = if (firstPage) {
                    foldTimePage(
                        LibraryTimeSearchResult(key = context.key),
                        result,
                        page,
                    ).copy(querying = false)
                } else {
                    foldTimePage(
                        state.timeResult ?: LibraryTimeSearchResult(key = context.key),
                        result,
                        page,
                    ).copy(querying = false)
                }
                state = state.copy(timeResult = merged)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!isTimeContextCurrent(context)) return@launch
                if (e is LibrarySessionExpiredException) {
                    fail(e, context.token)
                    return@launch
                }
                val message = (e as? LibrarySeatException)?.message ?: "查询失败"
                state = state.copy(
                    timeResult = if (firstPage) {
                        LibraryTimeSearchResult(key = context.key).asFirstPageFailure(message)
                    } else {
                        (state.timeResult ?: LibraryTimeSearchResult(key = context.key))
                            .asNextPageFailure()
                    },
                )
            }
        }
    }

    private fun isTimeContextCurrent(context: TimeQueryContext): Boolean =
        state.seatMode == RoomSeatMode.TIME &&
            context.isCurrent(
                timeSearchGeneration,
                repo.session.value?.token,
                state.currentTimeSearchKey(),
            )

    /** 选中一个区间候选：调官方 startTimes/endTimes 匹配 HH:mm，只用原始 id。 */
    fun selectTimeCandidate(seat: LibrarySeat) {
        if (!seat.timeSelectable) return
        val key = state.currentTimeSearchKey() ?: return
        val result = state.timeResult ?: return
        if (result.key != key || result.seats.none { it.id == seat.id }) return
        if (seat.id in state.hiddenSeatIds) return
        val generation = ++timeRequestGeneration
        val token = repo.session.value?.token
        state = state.copy(
            timeSelecting = true,
            timeSelectedSeat = seat,
            timeStartTimes = emptyList(),
            timeEndTimes = emptyList(),
            timeStart = null,
            timeStop = null,
            showConfirm = false,
            error = null,
        )
        viewModelScope.launch {
            try {
                val starts = repo.startTimes(seat.id, key.date)
                val start = matchOfficialTime(starts, key.beginText)
                    ?: throw LibrarySeatException("该座位时段已变化，请重新查找")
                val ends = repo.endTimes(seat.id, key.date, start.id)
                val stop = matchOfficialTime(ends, key.endText)
                    ?: throw LibrarySeatException("该座位时段已变化，请重新查找")
                if (generation != timeRequestGeneration ||
                    repo.session.value?.token != token ||
                    state.currentTimeSearchKey() != key ||
                    state.seatMode != RoomSeatMode.TIME ||
                    state.timeSelectedSeat?.id != seat.id
                ) {
                    return@launch
                }
                state = state.copy(
                    timeSelecting = false,
                    timeStartTimes = starts,
                    timeEndTimes = ends,
                    timeStart = start,
                    timeStop = stop,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation != timeRequestGeneration) return@launch
                if (e is LibrarySessionExpiredException) {
                    fail(e, token)
                    return@launch
                }
                state = state.copy(
                    timeSelecting = false,
                    timeSelectedSeat = null,
                    timeStartTimes = emptyList(),
                    timeEndTimes = emptyList(),
                    timeStart = null,
                    timeStop = null,
                    error = (e as? LibrarySeatException)?.message ?: "该座位时段已变化，请重新查找",
                )
            }
        }
    }

    fun requestTimeReservation() {
        if (state.reservationSubmitted) return
        if (!state.canConfirmTimeReservation()) {
            state = state.copy(error = "所选座位或时段已变化，请重新查找")
            return
        }
        state = state.copy(showConfirm = true)
    }

    /** 时段模式最终提交：与按座位模式共用一次性/核对逻辑，但守卫完全分开。 */
    fun confirmTimeReservation() {
        if (state.reservationSubmitted) return
        if (!state.canConfirmTimeReservation()) {
            state = state.copy(showConfirm = false, error = "所选座位或时段已变化，请重新查找")
            return
        }
        val key = state.currentTimeSearchKey() ?: return
        val seat = state.timeSelectedSeat ?: return
        val start = state.timeStart ?: return
        val stop = state.timeStop ?: return
        val beforeIds = state.reservations.map { it.id }.toSet()
        val requestToken = repo.session.value?.token
        // 提交前置位：网络读取迟到、未知结果都不能再次启用同一提交的确认。
        state = state.copy(
            loading = true,
            error = null,
            notice = null,
            showConfirm = false,
            reservationSubmitted = true,
        )
        viewModelScope.launch {
            val requestError = try {
                repo.reserve(key.date, seat.id, start.id, stop.id)
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
                    LibraryReservationMatch.matches(it, key.date, start.value, stop.value, seat.name)
                }
                val legacyMatchWithoutNewId = current.firstOrNull {
                    it.id in beforeIds && LibraryReservationMatch.matches(
                        it, key.date, start.value, stop.value, seat.name,
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

    // ───────────────── 本机个人隐藏 ─────────────────

    private fun loadHiddenSeats() {
        val buildingId = state.building?.id ?: return
        val roomId = state.room?.roomId ?: return
        val generation = ++hiddenGeneration
        viewModelScope.launch {
            try {
                val ids = hiddenSeatsStore.hiddenIds(buildingId, roomId)
                val entries = hiddenSeatsStore.entries(buildingId, roomId)
                if (generation != hiddenGeneration || state.building?.id != buildingId ||
                    state.room?.roomId != roomId
                ) {
                    return@launch
                }
                state = state.copy(hiddenSeatIds = ids, hiddenEntries = entries)
            } catch (_: Exception) {
                if (generation == hiddenGeneration) {
                    state = state.copy(hiddenActionError = "隐藏名单读取失败")
                }
            }
        }
    }

    fun hideSeat(seat: LibrarySeat) {
        val buildingId = state.building?.id ?: return
        val roomId = state.room?.roomId ?: return
        val entry = LibraryHiddenSeat(
            system = LibraryHiddenSeat.SYSTEM_LIBSEAT,
            buildingId = buildingId,
            roomId = roomId,
            seatId = seat.id,
            nameSnapshot = seat.name,
        )
        viewModelScope.launch {
            val saved = hiddenSeatsStore.hide(entry)
            if (!saved) {
                state = state.copy(hiddenActionError = "保存失败，请重试")
                return@launch
            }
            // 选中即清空；两模式的确认状态都清掉。
            state = state.copy(
                hiddenSeatIds = state.hiddenSeatIds + seat.id,
                hiddenEntries = state.hiddenEntries + entry,
                undoHidden = entry,
                hiddenActionError = null,
                timeSelectedSeat = state.timeSelectedSeat?.takeIf { it.id != seat.id },
                seat = state.seat?.takeIf { it.id != seat.id },
                showConfirm = false,
                timeStartTimes = emptyList(),
                timeEndTimes = emptyList(),
                timeStart = null,
                timeStop = null,
            )
            loadHiddenSeats()
        }
    }

    fun undoHide() {
        val entry = state.undoHidden ?: return
        viewModelScope.launch {
            val saved = hiddenSeatsStore.restore(entry.buildingId, entry.roomId, entry.seatId)
            if (!saved) {
                state = state.copy(hiddenActionError = "保存失败，请重试")
                return@launch
            }
            state = state.copy(undoHidden = null)
            loadHiddenSeats()
        }
    }

    fun clearUndoHide() {
        state = state.copy(undoHidden = null)
    }

    fun openHiddenSheet() {
        state = state.copy(showHiddenSheet = true)
        loadHiddenSeats()
    }

    fun closeHiddenSheet() {
        state = state.copy(showHiddenSheet = false)
    }

    fun restoreHidden(seatId: String) {
        val buildingId = state.building?.id ?: return
        val roomId = state.room?.roomId ?: return
        viewModelScope.launch {
            val saved = hiddenSeatsStore.restore(buildingId, roomId, seatId)
            if (!saved) {
                state = state.copy(hiddenActionError = "保存失败，请重试")
                return@launch
            }
            loadHiddenSeats()
        }
    }

    fun restoreAllHidden() {
        val buildingId = state.building?.id ?: return
        val roomId = state.room?.roomId ?: return
        viewModelScope.launch {
            val saved = hiddenSeatsStore.restoreAll(buildingId, roomId)
            if (!saved) {
                state = state.copy(hiddenActionError = "保存失败，请重试")
                return@launch
            }
            loadHiddenSeats()
        }
    }

    fun clearHiddenActionError() {
        state = state.copy(hiddenActionError = null)
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
