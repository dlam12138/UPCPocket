package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.upcpocket.app.data.library.LibraryReservationAttempt
import com.upcpocket.app.data.library.LibraryReservationContextStaleException
import com.upcpocket.app.data.library.LibraryReservationDuplicateException
import com.upcpocket.app.data.library.LibraryReservationMatch
import com.upcpocket.app.data.library.LibraryReservationPreflightFailedException
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatException
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibrarySeatOps
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySeatRepositoryOps
import com.upcpocket.app.data.library.LibrarySessionExpiredException
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.data.library.LibraryTimeSearchResult
import com.upcpocket.app.data.prefs.LibraryHiddenSeatsStore
import com.upcpocket.app.library.LibraryAccountAuthFailure
import com.upcpocket.app.library.LibraryAccountLoginResult
import com.upcpocket.app.library.LibraryService
import com.upcpocket.app.ui.components.GlassCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.math.max
import kotlin.math.roundToInt

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
                LibrarySeatViewModel(
                    repo = repo,
                    hiddenSeatsStore = hiddenSeatsStore,
                    ops = LibrarySeatRepositoryOps(repo),
                    logoutCleanup = {
                        service.sessionStore.clearLibrary()
                        LibrarySeatCookies.clearSeatCookies()
                    },
                ) as T
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
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
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
    com.upcpocket.app.ui.components.CampusFilterChip(selected = selected, onClick = onClick, label = { Text(label) })
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
            item(contentType = "area_notice") {
                CompactNotice(
                    title = if (state.error == null) "正在读取馆区" else "馆区读取失败",
                    body = state.error ?: if (state.loading) "请稍候。" else "暂时没有拿到馆区/日期。",
                )
                if (!state.loading) {
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = onRetry) { Text("重试") }
                }
            }
        } else {
            item(contentType = "area_filters") {
                CompactFilterPanel(
                    state = state,
                    filters = filters,
                    onBuilding = onBuilding,
                    onDate = onDate,
                    onFloor = onFloor,
                )
            }
            item(contentType = "area_summary") {
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
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新区域")
                    }
                }
            }
            if (state.loading) {
                item(contentType = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            state.error?.let { message ->
                item(contentType = "error") {
                    CompactNotice("暂时无法读取", message)
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = onRetry, enabled = !state.loading) { Text("重试") }
                }
            }
            if (!state.loading && state.error == null && visible.isEmpty()) {
                item(contentType = "empty") {
                    CompactNotice(
                        "没有可预约区域",
                        "没有可预约区域，请换日期或楼层。",
                    )
                }
            }
            items(visible, key = { it.roomId }, contentType = { "seat_room" }) { room ->
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
) {
    com.upcpocket.app.ui.components.GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        Text("馆区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            filters.buildings.forEach { building ->
                com.upcpocket.app.ui.components.CampusFilterChip(
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
                com.upcpocket.app.ui.components.CampusFilterChip(
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
                com.upcpocket.app.ui.components.CampusFilterChip(
                    selected = state.selectedFloor == null,
                    onClick = { onFloor(null) },
                    label = { Text("全部") },
                )
                state.floors.forEach { floor ->
                    com.upcpocket.app.ui.components.CampusFilterChip(
                        selected = state.selectedFloor == floor.floorNum,
                        onClick = { onFloor(floor.floorNum) },
                        label = { Text(floor.floorName.ifBlank { "${floor.floorNum} 楼" }) },
                    )
                }
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
        item(contentType = "reservation_summary") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("我的预约", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.reservationsUpdatedAt?.let { "更新于 ${clockLabel(it)}" } ?: "尚未读取",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                com.upcpocket.app.ui.components.CampusTextButton(onClick = onRefresh, enabled = !state.loading && !state.reservationsBusy) {
                    Text(if (state.reservationsBusy) "刷新中…" else "刷新")
                }
            }
        }
        state.reservationsError?.let { message ->
            item(contentType = "reservation_error") {
                CompactNotice("预约状态读取失败", message)
                com.upcpocket.app.ui.components.CampusTextButton(onClick = onRetry, enabled = !state.loading) { Text("重试") }
            }
        }
        if (state.reservations.isEmpty()) {
            item(contentType = "reservation_empty") {
                CompactNotice(
                    "当前没有预约记录",
                    if (state.reservationsUpdatedAt != null) "当前没有进行中的预约。"
                    else "还没有读取成功，无法判断。",
                )
            }
        } else {
            items(
                state.reservations,
                key = { it.id },
                contentType = { "reservation" },
            ) { reservation ->
                ReservationCard(
                    reservation = reservation,
                    enabled = !state.loading && state.reservationsError == null,
                    pending = reservation.id in state.pendingCancelIds,
                    onCancel = { onCancel(reservation) },
                )
            }
        }
        item(contentType = "reservation_note") {
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
    com.upcpocket.app.ui.components.GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
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
            com.upcpocket.app.ui.components.CampusOutlinedButton(
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
internal fun LibrarySeatDetailPage(
    state: LibrarySeatViewModel.UiState,
    vm: LibrarySeatViewModel,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    val context = LocalContext.current
    val room = state.room
    val seat = state.seat
    val buildingKey = state.building?.id.orEmpty()
    val roomKey = room?.roomId.orEmpty()
    var showSeatMap by rememberSeatPresentationMapState("libseat", buildingKey, roomKey)
    val mapViewportState = rememberSeatMapViewportState("libseat", buildingKey, roomKey)
    val listSeats = remember(state.layout) {
        sortSeatPresentations(
            state.layout?.seats.orEmpty().filter { it.isSeat },
            nameOf = LibrarySeat::name,
            stableIdOf = LibrarySeat::id,
        )
    }
    val density = LocalDensity.current
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
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
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
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = { openSeatOfficial(context) }) { Text("学校页面") }
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
            if (timeMode) {
                TimeSearchBottomBar(state = state, onConfirm = vm::requestTimeReservation)
            } else {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(seat?.name?.let { "已选 $it" } ?: "请选择座位", fontWeight = FontWeight.SemiBold)
                            Text(seatSummary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        com.upcpocket.app.ui.components.CampusButton(
                            onClick = {
                                when {
                                    seat == null -> Unit
                                    state.start == null || state.end == null -> vm.openTimeSheet()
                                    else -> vm.requestReservation()
                                }
                            },
                            enabled = when {
                                seat == null -> false
                                state.start == null || state.end == null ->
                                    seat.selectable && state.hiddenLoaded && !state.hiddenBusy &&
                                        state.hiddenError == null && seat.id !in state.hiddenSeatIds && !state.loading
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
            }
        },
    ) { padding ->
        if (timeMode) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                LibraryTimeSearchContent(
                    state = state,
                    onMode = vm::setSeatMode,
                    onPickBegin = { pickingBegin = true },
                    onPickFinish = { pickingFinish = true },
                    onSearch = vm::searchByTime,
                    onSelect = vm::selectTimeCandidate,
                    onHide = vm::hideSeat,
                    onRetry = vm::retryTime,
                    onLoadMore = vm::loadMoreTime,
                    onRetryHidden = vm::retryHiddenSeats,
                )
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val columns = seatListColumnCount(
                    availableWidthDp = (maxWidth - 32.dp).coerceAtLeast(1.dp).value,
                    fontScale = density.fontScale,
                )
                val seatRows = remember(listSeats, columns) { listSeats.chunked(columns) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(contentType = "seat_mode_controls") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            com.upcpocket.app.ui.components.CampusFilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text("按座位选") },
                            )
                            com.upcpocket.app.ui.components.CampusFilterChip(
                                selected = false,
                                onClick = { vm.setSeatMode(RoomSeatMode.TIME) },
                                label = { Text("按时段找") },
                            )
                        }
                    }
                    seatModeContent(
                        state = state,
                        vm = vm,
                        seat = seat,
                        room = room,
                        sortedSeats = listSeats,
                        seatRows = seatRows,
                        listColumns = columns,
                        showMap = showSeatMap,
                        onShowMapChange = { showSeatMap = it },
                        mapScopeKey = "$buildingKey|$roomKey",
                        mapViewportState = mapViewportState,
                    )
                    item(contentType = "seat_mode_notice") {
                        state.notice?.let { notice ->
                            Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
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
            com.upcpocket.app.ui.components.CampusAlertDialog(
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
                    com.upcpocket.app.ui.components.CampusTextButton(
                        enabled = if (timeMode) {
                            state.hiddenLoaded && !state.hiddenBusy && state.hiddenError == null &&
                                state.canConfirmTimeReservation()
                        } else state.canConfirmReservation(),
                        onClick = if (timeMode) vm::confirmTimeReservation else vm::confirmReservation,
                    ) { Text("确认预约") }
                },
                dismissButton = { com.upcpocket.app.ui.components.CampusTextButton(onClick = vm::dismissConfirm) { Text("取消") } },
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
    sortedSeats: List<LibrarySeat>,
    seatRows: List<List<LibrarySeat>>,
    listColumns: Int,
    showMap: Boolean,
    onShowMapChange: (Boolean) -> Unit,
    mapScopeKey: String,
    mapViewportState: SeatMapViewportState,
) {
    item(contentType = "seat_time_summary") {
        com.upcpocket.app.ui.components.GlassCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
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
                com.upcpocket.app.ui.components.CampusTextButton(onClick = vm::openTimeSheet, enabled = seat != null) { Text("改时间") }
            }
        }
    }
    state.error?.let { message ->
        item(contentType = "seat_operation_error") { CompactNotice("座位操作未完成", message) }
    }
    if (state.hiddenBusy) {
        item(contentType = "hidden_loading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    if (state.hiddenLoaded) "正在更新本机隐藏偏好…" else "正在恢复本机隐藏偏好…",
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    state.hiddenError?.let { message ->
        item(contentType = "hidden_error") {
            CompactNotice("隐藏偏好未恢复", message)
            com.upcpocket.app.ui.components.CampusTextButton(
                onClick = vm::retryHiddenSeats,
                enabled = !state.hiddenBusy,
            ) { Text("重试") }
        }
    }
    item(contentType = "seat_legend") {
        SeatLegend(hiddenCount = state.hiddenSeatIds.size)
    }
    item(contentType = "seat_presentation_control") {
        SeatPresentationModeControl(showMap = showMap, onShowMapChange = onShowMapChange)
    }
    if (state.loading && state.layout == null) {
        item(contentType = "seat_map_loading") {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在读取座位…", style = MaterialTheme.typography.bodySmall)
            }
        }
    } else if (state.layout == null) {
        item(contentType = "seat_map_missing") {
            CompactNotice("没有座位布局", "学校未返回该区域的座位布局，不能显示虚构平面图。")
        }
    } else if (showMap) {
        item(contentType = "seat_map") {
            SeatMap(
                layout = requireNotNull(state.layout),
                selectedId = seat?.id,
                onSelect = vm::chooseSeat,
                hiddenIds = state.hiddenSeatIds,
                onLongClick = vm::hideSeat,
                selectionEnabled = state.hiddenLoaded && !state.hiddenBusy &&
                    state.hiddenError == null && !state.loading,
                mapScopeKey = mapScopeKey,
                viewportState = mapViewportState,
            )
        }
    } else {
        if (sortedSeats.isEmpty()) {
            item(contentType = "seat_list_empty") { CompactNotice("没有座位", "学校未返回可显示的真实座位。") }
        } else {
            items(
                items = seatRows,
                key = { row -> "libseat-row-${row.first().id}" },
                contentType = { "seat_list_row" },
            ) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        LibrarySeatListTile(
                            modifier = Modifier.weight(1f),
                            seat = item,
                            selected = item.id == seat?.id,
                            hidden = item.id in state.hiddenSeatIds,
                            hiddenLoaded = state.hiddenLoaded,
                            hiddenBusy = state.hiddenBusy,
                            onSelect = { vm.chooseSeat(item) },
                            onHide = { vm.hideSeat(item) },
                        )
                    }
                    repeat(listColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    item(contentType = "seat_help") {
        Text(
            if (showMap) "点可用位置选择；长按座位可隐藏；双指缩放或拖动查看，复位返回概览。"
            else "按座号排序；点可用座位选择，更多操作可隐藏。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SeatPresentationModeControl(
    showMap: Boolean,
    onShowMapChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.upcpocket.app.ui.components.CampusFilterChip(
            selected = !showMap,
            onClick = { onShowMapChange(false) },
            modifier = Modifier.testTag("seat_presentation_list"),
            label = { Text("座位列表") },
        )
        com.upcpocket.app.ui.components.CampusFilterChip(
            selected = showMap,
            onClick = { onShowMapChange(true) },
            modifier = Modifier.testTag("seat_presentation_map"),
            label = { Text("布局图") },
        )
    }
}

@Composable
internal fun rememberSeatPresentationMapState(
    systemKey: String,
    areaKey: String,
    roomKey: String,
): androidx.compose.runtime.MutableState<Boolean> = rememberSaveable(systemKey, areaKey, roomKey) {
    mutableStateOf(false)
}

internal data class SeatMapViewportState(
    val zoomMultiplier: androidx.compose.runtime.MutableState<Float>,
    val centerXFraction: androidx.compose.runtime.MutableState<Float>,
    val centerYFraction: androidx.compose.runtime.MutableState<Float>,
)

@Composable
internal fun rememberSeatMapViewportState(
    systemKey: String,
    areaKey: String,
    roomKey: String,
): SeatMapViewportState {
    val zoomMultiplier = rememberSaveable(systemKey, areaKey, roomKey, "zoom") { mutableStateOf(1f) }
    val centerXFraction = rememberSaveable(systemKey, areaKey, roomKey, "center-x") { mutableStateOf(0.5f) }
    val centerYFraction = rememberSaveable(systemKey, areaKey, roomKey, "center-y") { mutableStateOf(0.5f) }
    return remember(zoomMultiplier, centerXFraction, centerYFraction) {
        SeatMapViewportState(zoomMultiplier, centerXFraction, centerYFraction)
    }
}

@Composable
private fun LibrarySeatListTile(
    modifier: Modifier,
    seat: LibrarySeat,
    selected: Boolean,
    hidden: Boolean,
    hiddenLoaded: Boolean,
    hiddenBusy: Boolean,
    onSelect: () -> Unit,
    onHide: () -> Unit,
) {
    val canSelect = seat.selectable && hiddenLoaded && !hiddenBusy && !hidden
    val canHide = seat.isSeat && hiddenLoaded && !hiddenBusy && !hidden
    var menu by remember { mutableStateOf(false) }
    val description = when {
        hidden -> "本机已隐藏"
        seat.localOnly -> "仅本地布局元素，不可预约"
        !seat.enabled -> "不可预约"
        else -> com.upcpocket.app.data.library.LibrarySeatStatus.label(seat.status)
    }
    Surface(
        modifier = modifier.heightIn(min = 56.dp)
            .semantics {
                contentDescription = "座位 ${seat.name}，$description"
                this.selected = selected
            }
            .testTag("library_seat_list_${seat.id}")
            .combinedClickable(
                enabled = canSelect || canHide,
                role = Role.RadioButton,
                onClick = { if (canSelect) onSelect() },
                onLongClick = { if (canHide) onHide() },
            ),
        shape = RoundedCornerShape(12.dp),
        color = when {
            hidden -> MaterialTheme.colorScheme.surfaceContainerHighest
            selected -> MaterialTheme.colorScheme.tertiaryContainer
            canSelect && (seat.status == "FREE" || seat.status == "YESSELECT") ->
                MaterialTheme.colorScheme.primaryContainer
            canSelect -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                seat.name,
                modifier = Modifier.fillMaxWidth().testTag("library_seat_name_${seat.id}"),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    description,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
                Box {
                    IconButton(onClick = { menu = true }, enabled = canHide) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "座位 ${seat.name} 的更多操作")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("隐藏此座位") },
                            enabled = canHide,
                            onClick = { menu = false; onHide() },
                            leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                        )
                    }
                }
            }
        }
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
    com.upcpocket.app.ui.components.CampusAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = picker) },
        confirmButton = {
            com.upcpocket.app.ui.components.CampusTextButton(onClick = { onConfirm(formatHhmm(picker.hour * 60 + picker.minute)) }) {
                Text("确定")
            }
        },
        dismissButton = { com.upcpocket.app.ui.components.CampusTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 按时段找页面的主体（生产组件）：模式切换 + 时间输入 + 结果列表。
 *
 * debug 合成预览用同一个 [state] 类型与同一套派生规则、无网络回调调用它，
 * 不复制第二份布局，也不硬编码计数/hasMore/时长/确认可用性。
 */
@Composable
internal fun LibraryTimeSearchContent(
    state: LibrarySeatViewModel.UiState,
    onMode: (RoomSeatMode) -> Unit,
    onPickBegin: () -> Unit,
    onPickFinish: () -> Unit,
    onSearch: () -> Unit,
    onSelect: (LibrarySeat) -> Unit,
    onHide: (LibrarySeat) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryHidden: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(contentType = "time_mode_controls") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.upcpocket.app.ui.components.CampusFilterChip(
                    selected = state.seatMode == RoomSeatMode.SEAT,
                    onClick = { onMode(RoomSeatMode.SEAT) },
                    label = { Text("按座位选") },
                )
                com.upcpocket.app.ui.components.CampusFilterChip(
                    selected = state.seatMode == RoomSeatMode.TIME,
                    onClick = { onMode(RoomSeatMode.TIME) },
                    label = { Text("按时段找") },
                )
            }
        }
        item(contentType = "time_range_controls") {
            TimeRangeControls(
                begin = state.timeBegin,
                finish = state.timeFinish,
                onPickBegin = onPickBegin,
                onPickFinish = onPickFinish,
                onSearch = onSearch,
                canSearch = validTimeRange(state.timeBegin, state.timeFinish) &&
                    state.timeResult?.querying != true,
                querying = state.timeResult?.querying == true,
            )
        }
        if (state.hiddenBusy) {
            item(contentType = "hidden_loading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.hiddenLoaded) "正在更新本机隐藏偏好…" else "正在恢复本机隐藏偏好…",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        state.hiddenError?.let { message ->
            item(contentType = "hidden_error") {
                CompactNotice("隐藏偏好未恢复", message)
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = onRetryHidden,
                    enabled = !state.hiddenBusy,
                ) { Text("重试") }
            }
        }
        item(contentType = "time_results") {
            TimeSearchResults(
                state = state,
                onSelect = onSelect,
                onHide = onHide,
                onRetry = onRetry,
                onLoadMore = onLoadMore,
            )
        }
        item(contentType = "time_notice") {
            state.notice?.let { notice ->
                Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun TimeRangeControls(
    begin: String?,
    finish: String?,
    onPickBegin: () -> Unit,
    onPickFinish: () -> Unit,
    onSearch: () -> Unit,
    canSearch: Boolean,
    querying: Boolean,
) {
    com.upcpocket.app.ui.components.GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("开始时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.upcpocket.app.ui.components.CampusOutlinedButton(onClick = onPickBegin, modifier = Modifier.fillMaxWidth()) {
                        Text(begin ?: "选择开始", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("结束时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.upcpocket.app.ui.components.CampusOutlinedButton(onClick = onPickFinish, modifier = Modifier.fillMaxWidth()) {
                        Text(finish ?: "选择结束", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            com.upcpocket.app.ui.components.CampusButton(
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
}

/**
 * 按时段找的底部摘要与确认（生产组件，debug 合成预览也复用同一套派生规则）。
 * 时长、可选状态、按钮文案都由 [state] 推导，不硬编码「2 小时」或 chosen!=null。
 */
@Composable
internal fun TimeSearchBottomBar(
    state: LibrarySeatViewModel.UiState,
    onConfirm: () -> Unit,
) {
    val chosen = state.timeSelectedSeat
    val start = state.timeStart
    val stop = state.timeStop
    val summary = if (start != null && stop != null) {
        "${start.value}–${stop.value}" +
            (durationText(start, stop)?.let { " · $it" } ?: "")
    } else if (state.timeBegin != null && state.timeFinish != null) {
        "${state.timeBegin}–${state.timeFinish}"
    } else {
        "选择时段"
    }
    val hiddenPreferencesReady = state.hiddenLoaded && !state.hiddenBusy && state.hiddenError == null
    SeatSelectionBottomBar(
        chosen?.name,
        summary,
        hiddenPreferencesReady && state.canConfirmTimeReservation(),
        onConfirm,
    )
}

@Composable
internal fun SeatSelectionBottomBar(
    selectedName: String?, summary: String, canConfirm: Boolean, onConfirm: () -> Unit,
    actionLabel: String = "确认预约",
) {
    Surface(tonalElevation = 2.dp) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val stacked = maxWidth < 320.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.4f
            @Composable fun Summary() {
                Column {
                    Text(selectedName?.let { "已选 $it" } ?: "请选择座位", fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Summary()
                    com.upcpocket.app.ui.components.CampusButton(onClick = onConfirm, enabled = canConfirm, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Summary() }
                com.upcpocket.app.ui.components.CampusButton(onClick = onConfirm, enabled = canConfirm) { Text(actionLabel) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimeSearchResults(
    state: LibrarySeatViewModel.UiState,
    onSelect: (LibrarySeat) -> Unit,
    onHide: (LibrarySeat) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val result = state.timeResult
    val visible = result?.visibleCandidates(state.hiddenSeatIds).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            result == null -> CompactNotice("按时段找", "选择时段，查找可约座位")
            result.querying && result.loadedPages == 0 ->
                CompactNotice("正在查找", "只查询当前房间。")
            result.failed ->
                Column {
                    CompactNotice("查询失败", result.failureMessage ?: "请重试")
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = onRetry, enabled = !result.querying) { Text("重试") }
                }
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
                // 已隐藏数只取「已加载候选 ∩ 个人隐藏」，不是整个房间的隐藏总数。
                val hiddenAmongLoaded = result.hiddenAmongLoaded(state.hiddenSeatIds)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(countLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (hiddenAmongLoaded > 0) {
                        Text(
                            "已加载中隐藏 $hiddenAmongLoaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                visible.forEach { candidate ->
                    TimeCandidateRow(
                        candidate = candidate,
                        selected = state.timeSelectedSeat?.id == candidate.id,
                        interactionsEnabled = state.hiddenLoaded && !state.hiddenBusy &&
                            state.hiddenError == null,
                        onSelect = { onSelect(candidate) },
                        onHide = { onHide(candidate) },
                    )
                }
                when {
                    result.partialFailure ->
                        Column {
                            Text("部分结果未加载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            com.upcpocket.app.ui.components.CampusTextButton(onClick = onRetry, enabled = !result.querying) { Text("重试") }
                        }
                    result.pageLimitReached ->
                        Text("已加载前 ${result.seats.size} 个，请缩小范围", style = MaterialTheme.typography.labelSmall)
                    result.hasMore ->
                        com.upcpocket.app.ui.components.CampusTextButton(onClick = onLoadMore, enabled = !result.querying) { Text("加载更多") }
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
    interactionsEnabled: Boolean,
    onSelect: () -> Unit,
    onHide: () -> Unit,
) = SeatCandidateRow(
    name = candidate.name,
    selectable = candidate.timeSelectable && interactionsEnabled,
    selected = selected,
    onSelect = onSelect,
    onHide = onHide,
    canHide = interactionsEnabled,
)

@Composable
internal fun SeatCandidateRow(
    name: String,
    selectable: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onHide: () -> Unit,
    canHide: Boolean = true,
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
                .clickable(enabled = selectable, onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.RadioButton(
                selected = selected,
                onClick = null,
                enabled = selectable,
            )
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val facility = buildList {
                    if (selectable) add("可选")
                }
                if (facility.isNotEmpty()) {
                    Text(facility.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = canHide) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("隐藏此座位") },
                        enabled = canHide,
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
    HiddenSeatManagementSheet(
        entries = state.hiddenEntries,
        onClose = vm::closeHiddenSheet,
        onRestore = vm::restoreHidden,
        onRestoreAll = vm::restoreAllHidden,
        busy = state.hiddenBusy,
        loaded = state.hiddenLoaded,
        error = state.hiddenError,
        onRetry = vm::retryHiddenSeats,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HiddenSeatManagementSheet(
    entries: List<LibraryHiddenSeat>, onClose: () -> Unit,
    onRestore: (String) -> Unit, onRestoreAll: () -> Unit,
    busy: Boolean = false,
    loaded: Boolean = true,
    error: String? = null,
    onRetry: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("隐藏的座位（${entries.size}）", style = MaterialTheme.typography.titleMedium)
            Text(
                "只在本机隐藏，不影响学校状态。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        if (loaded) "正在更新隐藏名单…" else "正在读取隐藏名单…",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            error?.let { message ->
                CompactNotice("隐藏偏好未恢复", message)
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = onRetry,
                    enabled = !busy,
                ) { Text("重试") }
            }
            if (!loaded && !busy && error == null) {
                CompactNotice("隐藏偏好未读取", "读取完成前不会将名单显示为空。")
            } else if (loaded && !busy && entries.isEmpty()) {
                Text("没有隐藏的座位。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loaded && entries.isNotEmpty()) {
                entries.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.nameSnapshot, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        com.upcpocket.app.ui.components.CampusTextButton(
                            onClick = { onRestore(entry.seatId) },
                            enabled = !busy,
                        ) { Text("恢复") }
                    }
                }
                if (!busy) {
                    com.upcpocket.app.ui.components.CampusOutlinedButton(
                        onClick = onRestoreAll,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("全部恢复")
                    }
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
    ModalBottomSheet(
        onDismissRequest = vm::closeTimeDialog,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
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
                        com.upcpocket.app.ui.components.CampusFilterChip(
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
                        com.upcpocket.app.ui.components.CampusFilterChip(
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
            com.upcpocket.app.ui.components.CampusButton(
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
    com.upcpocket.app.ui.components.GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
        hiddenLoaded = false,
        hiddenBusy = false,
        hiddenError = null,
        showHiddenSheet = false,
        hiddenActionError = null,
        undoHidden = null,
    )

internal fun LibrarySeatViewModel.UiState.canConfirmReservation(): Boolean {
    if (reservationSubmitted) return false
    if (!hiddenLoaded || hiddenBusy || hiddenError != null) return false
    val selectedSeat = seat ?: return false
    if (selectedSeat.id in hiddenSeatIds) return false
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
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
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
                com.upcpocket.app.ui.components.CampusButton(
                    onClick = {
                        if (busy) return@CampusButton
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
            com.upcpocket.app.ui.components.CampusOutlinedButton(
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
                com.upcpocket.app.ui.components.CampusTextButton(enabled = enabled, onClick = onCancel) { Text("取消预约") }
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
    selectionEnabled: Boolean = true,
    mapScopeKey: String = "libseat|default",
    viewportState: SeatMapViewportState? = null,
) {
    val bounds = remember(layout) {
        seatMapBoundsForGrid(
            rowCount = layout.rows,
            columnCount = layout.cols,
            allLayoutElements = layout.seats,
            rowOf = LibrarySeat::row,
            columnOf = LibrarySeat::col,
        )
    }
    if (bounds == null) {
        CompactNotice("没有有效布局", "学校未返回可显示的座位坐标。")
        return
    }

    val byPosition = remember(layout) { layout.seats.associateBy { it.row to it.col } }
    val density = LocalDensity.current
    var fallbackZoom by rememberSaveable("libseat", mapScopeKey, "zoom") { mutableStateOf(1f) }
    var fallbackCenterX by rememberSaveable("libseat", mapScopeKey, "center-x") { mutableStateOf(0.5f) }
    var fallbackCenterY by rememberSaveable("libseat", mapScopeKey, "center-y") { mutableStateOf(0.5f) }
    val zoom = viewportState?.zoomMultiplier?.value ?: fallbackZoom
    val centerX = viewportState?.centerXFraction?.value ?: fallbackCenterX
    val centerY = viewportState?.centerYFraction?.value ?: fallbackCenterY

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .height(368.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .testTag("seat_map_viewport"),
        ) {
            val controlsHeightPx = with(density) { 48.dp.toPx() }
            val viewportSize = SeatMapSize(
                width = constraints.maxWidth.toFloat(),
                height = (constraints.maxHeight - controlsHeightPx).coerceAtLeast(1f),
            )
            val paddingPx = with(density) { 12.dp.toPx() }
            val targetSeatPx = with(density) { 48.dp.toPx() }
            val overview = SeatMapViewportTransform.overview(bounds, viewportSize, paddingPx)
            if (overview == null) {
                CompactNotice("无法显示布局", "当前窗口空间不足，请调整窗口后重试。")
            } else {
                val maxZoom = max(4f, targetSeatPx / overview.scale).coerceAtMost(64f)
                val pose = SeatMapViewportPose(zoom, centerX, centerY)
                val transform = remember(bounds, viewportSize, pose, maxZoom) {
                    pose.toTransform(bounds, viewportSize, paddingPx, maxZoom) ?: overview
                }
                val currentTransform = rememberUpdatedState(transform)
                val currentByPosition = rememberUpdatedState(byPosition)
                val currentHiddenIds = rememberUpdatedState(hiddenIds)
                val currentSelectionEnabled = rememberUpdatedState(selectionEnabled)
                val currentOnSelect = rememberUpdatedState(onSelect)
                val currentOnLongClick = rememberUpdatedState(onLongClick)

                fun saveTransform(next: SeatMapViewportTransform) {
                    val nextPose = SeatMapViewportPose.from(next)
                    if (viewportState == null) {
                        fallbackZoom = nextPose.zoomMultiplier
                        fallbackCenterX = nextPose.centerXFraction
                        fallbackCenterY = nextPose.centerYFraction
                    } else {
                        viewportState.zoomMultiplier.value = nextPose.zoomMultiplier
                        viewportState.centerXFraction.value = nextPose.centerXFraction
                        viewportState.centerYFraction.value = nextPose.centerYFraction
                    }
                }

                val gestureModifier = Modifier
                    .pointerInput(mapScopeKey, bounds, viewportSize, maxZoom) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val current = currentTransform.value
                            val next = current
                                .zoomAt(SeatMapPoint(centroid.x, centroid.y), zoomChange)
                                .panBy(SeatMapPoint(pan.x, pan.y))
                            saveTransform(next)
                        }
                    }
                    .pointerInput(mapScopeKey, bounds, viewportSize) {
                        detectTapGestures(
                            onTap = { offset ->
                                val position = currentTransform.value.displayToGridPosition(
                                    SeatMapPoint(offset.x, offset.y),
                                ) ?: return@detectTapGestures
                                val target = currentByPosition.value[position.row to position.col]
                                    ?: return@detectTapGestures
                                if (currentSelectionEnabled.value && target.selectable &&
                                    target.id !in currentHiddenIds.value
                                ) {
                                    currentOnSelect.value(target)
                                }
                            },
                            onLongPress = { offset ->
                                val position = currentTransform.value.displayToGridPosition(
                                    SeatMapPoint(offset.x, offset.y),
                                ) ?: return@detectTapGestures
                                val target = currentByPosition.value[position.row to position.col]
                                    ?: return@detectTapGestures
                                if (target.isSeat && target.id !in currentHiddenIds.value &&
                                    currentSelectionEnabled.value
                                ) {
                                    currentOnLongClick.value(target)
                                }
                            },
                        )
                    }

                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeatMapZoomControls(
                        onZoomOut = {
                            val center = SeatMapPoint(viewportSize.width / 2f, viewportSize.height / 2f)
                            saveTransform(transform.zoomAt(center, 1f / 1.6f))
                        },
                        onZoomIn = {
                            val center = SeatMapPoint(viewportSize.width / 2f, viewportSize.height / 2f)
                            saveTransform(transform.zoomAt(center, 1.6f))
                        },
                        onReset = { transform.reset()?.let(::saveTransform) },
                    )
                    Box(Modifier.fillMaxWidth().weight(1f).then(gestureModifier)) {
                    val drawnCellSize = with(density) { transform.scale.toDp() }
                    val showLabels = drawnCellSize >= 24.dp
                    Box(
                        Modifier
                            .offset {
                                val current = currentTransform.value
                                IntOffset(
                                    x = (current.translationX + current.contentBounds.left * current.scale).roundToInt(),
                                    y = (current.translationY + current.contentBounds.top * current.scale).roundToInt(),
                                )
                            }
                            .graphicsLayer {
                                val current = currentTransform.value
                                val densityScale = current.scale / density.density
                                scaleX = densityScale
                                scaleY = densityScale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .size(bounds.width.dp, bounds.height.dp),
                    ) {
                        layout.seats.forEach { item ->
                            if (item.row !in 0 until layout.rows || item.col !in 0 until layout.cols) {
                                return@forEach
                            }
                            val hidden = item.id in hiddenIds
                            val selected = item.id == selectedId
                            val positionStatus = if (!item.isSeat) "非座位布局元素" else when {
                                hidden -> "本机已隐藏"
                                item.localOnly || !item.enabled -> "不可预约"
                                item.status == "FREE" || item.status == "YESSELECT" -> "可用"
                                else -> com.upcpocket.app.data.library.LibrarySeatStatus.label(item.status)
                            }
                            val color = when {
                                !item.isSeat -> MaterialTheme.colorScheme.surfaceVariant
                                hidden -> MaterialTheme.colorScheme.surfaceContainerHighest
                                selected -> MaterialTheme.colorScheme.tertiary
                                !item.enabled || item.localOnly -> MaterialTheme.colorScheme.surfaceContainerHighest
                                item.status == "FREE" || item.status == "YESSELECT" -> MaterialTheme.colorScheme.primaryContainer
                                item.status == "AWAY" -> MaterialTheme.colorScheme.tertiaryContainer
                                item.status == "IN_USE" || item.status == "BOOKED" || item.status == "FULL" ->
                                    MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                            val textColor = when {
                                hidden -> MaterialTheme.colorScheme.outline
                                selected -> MaterialTheme.colorScheme.onTertiary
                                !item.enabled || item.localOnly -> MaterialTheme.colorScheme.onSurfaceVariant
                                item.status == "FREE" || item.status == "YESSELECT" -> MaterialTheme.colorScheme.onPrimaryContainer
                                item.status == "AWAY" -> MaterialTheme.colorScheme.onTertiaryContainer
                                item.status == "IN_USE" || item.status == "BOOKED" || item.status == "FULL" ->
                                    MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Box(
                                Modifier
                                    .offset(
                                        x = (item.col.toFloat() - bounds.left).dp,
                                        y = (item.row.toFloat() - bounds.top).dp,
                                    )
                                    .size(1.dp)
                                    .padding(0.04.dp)
                                    .clip(RoundedCornerShape(0.18.dp))
                                    .background(color)
                                    .then(if (hidden) Modifier.drawBehind {
                                        drawLine(
                                            color = Color.Gray,
                                            start = Offset.Zero,
                                            end = Offset(size.width, size.height),
                                            strokeWidth = size.minDimension * 0.09f,
                                        )
                                    } else Modifier)
                                    .semantics {
                                        contentDescription = if (item.isSeat) {
                                            "座位 ${item.name}，$positionStatus"
                                        } else {
                                            positionStatus
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (item.isSeat && showLabels && !hidden) {
                                    Text(
                                        item.name,
                                        color = textColor,
                                        fontSize = 0.38.sp,
                                        lineHeight = 0.4.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip,
                                        textAlign = TextAlign.Center,
                                    )
                                } else if (hidden && showLabels) {
                                    Text("·", color = textColor, fontSize = 0.5.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
internal fun SeatMapZoomControls(
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.upcpocket.app.ui.components.CampusTextButton(onClick = onZoomOut) { Text("缩小") }
        com.upcpocket.app.ui.components.CampusTextButton(onClick = onZoomIn) { Text("放大") }
        com.upcpocket.app.ui.components.CampusTextButton(onClick = onReset) { Text("复位") }
    }
}

internal class LibrarySeatViewModel(
    private val repo: LibrarySeatRepository,
    private val hiddenSeatsStore: LibraryHiddenSeatsStore,
    private val ops: LibrarySeatOps,
    private val startTimesLoader: suspend (String, String) -> List<LibraryTimeOption> = repo::startTimes,
    /** 默认读取原房间布局；测试可注入本地布局，避免触发学校请求。 */
    private val layoutLoader: suspend (String, String) -> LibrarySeatLayout = repo::layout,
    private val logoutCleanup: () -> Unit = {},
    /** 仅供状态恢复/测试注入初始状态；生产 factory 不传。 */
    initialState: UiState = UiState(),
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
        val hiddenLoaded: Boolean = false,
        val hiddenBusy: Boolean = false,
        val hiddenError: String? = null,
        val showHiddenSheet: Boolean = false,
        val hiddenActionError: String? = null,
        val undoHidden: LibraryHiddenSeat? = null,
    )

    var state by mutableStateOf(initialState)
        private set
    private var roomRequestGeneration = 0L
    private var timeRequestGeneration = 0L
    private var roomDetailRequestGeneration = 0L
    private var timeSearchGeneration = 0L
    private var hiddenGeneration = 0L
    private val hiddenStoreMutex = Mutex()

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
            hiddenLoaded = false,
            hiddenBusy = false,
            hiddenError = null,
            showHiddenSheet = false,
            hiddenActionError = null,
            undoHidden = null,
        ).clearTimeSelection()
        loadHiddenSeats()
        viewModelScope.launch {
            try {
                val layout = layoutLoader(room.roomId, date)
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
        when {
            state.hiddenBusy -> {
                state = state.copy(
                    error = if (state.hiddenLoaded) {
                        "本机隐藏偏好正在更新，请稍后再选座"
                    } else {
                        "正在恢复本机隐藏偏好，请稍后再选座"
                    },
                )
                return
            }
            state.hiddenError != null -> {
                state = state.copy(error = "隐藏偏好未恢复，请重试后再选座")
                return
            }
            !state.hiddenLoaded -> {
                state = state.copy(error = "隐藏偏好未恢复，请重试后再选座")
                return
            }
            seat.id in state.hiddenSeatIds -> {
                state = state.copy(error = "该座位已在本机隐藏，可在隐藏列表中恢复")
                return
            }
            !seat.selectable -> return
        }
        val currentSeat = state.layout?.seats?.firstOrNull { it.id == seat.id && it.selectable } ?: return
        val date = state.date ?: return
        val requestToken = repo.session.value?.token
        val generation = ++timeRequestGeneration
        state = state.copy(
            loading = true,
            error = null,
            seat = currentSeat,
            startTimes = emptyList(),
            endTimes = emptyList(),
            start = null,
            end = null,
            showTimeDialog = false,
        )
        viewModelScope.launch {
            try {
                val starts = startTimesLoader(currentSeat.id, date)
                if (generation != timeRequestGeneration || state.seat?.id != currentSeat.id) return@launch
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
        hiddenGeneration++
        state = state.afterCloseRoom()
    }

    fun openTimeSheet() {
        val selectedSeat = state.seat ?: return
        if (state.hiddenLoaded && !state.hiddenBusy &&
            state.hiddenError == null && selectedSeat.id !in state.hiddenSeatIds
        ) {
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
        val token = ops.sessionToken()
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
        if (current.key != key || current.querying || current.pageLimitReached) return
        if (!current.hasMore && !current.partialFailure) return
        val generation = timeSearchGeneration
        val token = ops.sessionToken()
        val context = TimeQueryContext(generation, token, key)
        state = state.copy(
            timeResult = current.copy(querying = true, partialFailure = false, failureMessage = null),
        )
        loadTimePage(context, firstPage = false)
    }

    /**
     * 「重试」：首屏失败重新查找（page 1），下一页失败/不前进重新发出当前合法页。
     * 不是空操作 —— 两种情况都会真正走 [loadTimePage]。
     */
    fun retryTime() {
        val current = state.timeResult ?: return
        if (current.key != state.currentTimeSearchKey()) return
        if (current.failed) {
            searchByTime()
        } else {
            loadMoreTime()
        }
    }

    private fun loadTimePage(context: TimeQueryContext, firstPage: Boolean) {
        val page = if (firstPage) 1 else state.timeResult?.nextPage ?: return
        viewModelScope.launch {
            try {
                val result = ops.timeSearchSeats(
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
                ops.sessionToken(),
                state.currentTimeSearchKey(),
            )

    /** 选中一个区间候选：调官方 startTimes/endTimes 匹配 HH:mm，只用原始 id。 */
    fun selectTimeCandidate(seat: LibrarySeat) {
        if (!state.hiddenLoaded || state.hiddenBusy || state.hiddenError != null) {
            state = state.copy(error = "隐藏偏好未恢复，请重试后再选座")
            return
        }
        if (!seat.timeSelectable) return
        val key = state.currentTimeSearchKey() ?: return
        val result = state.timeResult ?: return
        if (result.key != key || result.seats.none { it.id == seat.id }) return
        if (seat.id in state.hiddenSeatIds) return
        val generation = ++timeRequestGeneration
        val token = ops.sessionToken()
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
                val starts = ops.startTimes(seat.id, key.date)
                val start = matchOfficialTime(starts, key.beginText)
                    ?: throw LibrarySeatException("该座位时段已变化，请重新查找")
                val ends = ops.endTimes(seat.id, key.date, start.id)
                val stop = matchOfficialTime(ends, key.endText)
                    ?: throw LibrarySeatException("该座位时段已变化，请重新查找")
                // 迟到响应：代次/token/完整键/模式/所选座位任一变化都不得恢复确认。
                if (generation != timeRequestGeneration ||
                    ops.sessionToken() != token ||
                    state.currentTimeSearchKey() != key ||
                    state.seatMode != RoomSeatMode.TIME ||
                    state.timeSelectedSeat?.id != seat.id ||
                    seat.id in state.hiddenSeatIds
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
        if (!state.hiddenLoaded || state.hiddenBusy || state.hiddenError != null ||
            !state.canConfirmTimeReservation()
        ) {
            state = state.copy(error = "所选座位或时段已变化，请重新查找")
            return
        }
        state = state.copy(showConfirm = true)
    }

    /**
     * 按时段模式最终提交。防重由两层保证：
     *  - 会话范围 repository 闩（[LibraryReservationRequest]）阻止真正再次 POST；
     *  - [reservationSubmitted] UI 状态阻止再次显示可确认。
     * 关闭重进 / 切模式 / 重建 ViewModel 只要 repository 仍是同一会话，就不能绕过。
     * POST 前的验证码/CAPTCHA 不算已发送，不置 submitted，正常网页回退仍可再试。
     */
    fun confirmTimeReservation() {
        if (state.reservationSubmitted) return
        if (!state.hiddenLoaded || state.hiddenBusy || state.hiddenError != null ||
            !state.canConfirmTimeReservation()
        ) {
            state = state.copy(showConfirm = false, error = "所选座位或时段已变化，请重新查找")
            return
        }
        val key = state.currentTimeSearchKey() ?: return
        val seat = state.timeSelectedSeat ?: return
        val start = state.timeStart ?: return
        val stop = state.timeStop ?: return
        submitReservation(
            context = ReservationContext(
                token = ops.sessionToken(),
                date = key.date,
                seatId = seat.id,
                seatName = seat.name,
                startId = start.id,
                endId = stop.id,
                startValue = start.value,
                endValue = stop.value,
                buildingId = key.buildingId,
                roomId = key.roomId,
            ),
            mode = RoomSeatMode.TIME,
        )
    }

    /** 按座位模式最终提交，复用同一防重闩与响应校验。 */
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
        submitReservation(
            context = ReservationContext(
                token = ops.sessionToken(),
                date = date,
                seatId = seat.id,
                seatName = seat.name,
                startId = start.id,
                endId = end.id,
                startValue = start.value,
                endValue = end.value,
                buildingId = state.building?.id,
                roomId = state.room?.roomId,
            ),
            mode = RoomSeatMode.SEAT,
        )
    }

    /** 一次提交的完整上下文；迟到响应据此核对账号/会话/房间，不写新房间/新账号。 */
    private data class ReservationContext(
        val token: String?,
        val date: String,
        val seatId: String,
        val seatName: String,
        val startId: String,
        val endId: String,
        val startValue: String,
        val endValue: String,
        val buildingId: String?,
        val roomId: String?,
    )

    private fun submitReservation(context: ReservationContext, mode: RoomSeatMode) {
        val beforeIds = state.reservations.map { it.id }.toSet()
        viewModelScope.launch {
            if (!isReservationContextCurrent(context, mode)) return@launch
            // 请求与最终状态分开：请求失败/超时也不等于「没预约上」，先查询核对再下结论。
            val attempt = try {
                ops.reserve(context.date, context.seatId, context.startId, context.endId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!isReservationContextCurrent(context, mode)) return@launch
                if (e is LibrarySessionExpiredException) {
                    // 会话失效：不能确定是否发出，但 repository 闩已按当前会话登记；
                    // 只做失效处理，不重提。
                    fail(e, context.token)
                    return@launch
                }
                if (e is LibraryReservationDuplicateException) {
                    // 防重闩拒绝：确认这不是新发出请求，保持 UI 不可再确认。
                    state = state.copy(
                        loading = false,
                        showConfirm = false,
                        reservationSubmitted = true,
                        error = e.message ?: "这次预约已提交，请勿重复提交。",
                    )
                    return@launch
                }
                if (e is LibraryReservationPreflightFailedException) {
                    // POST 前验证失败：没有发出提交，不置 submitted，网页回退仍可再试。
                    state = state.copy(
                        loading = false,
                        showConfirm = false,
                        error = e.message ?: "提交前验证失败，请在学校页面预约。",
                    )
                    return@launch
                }
                if (e is LibraryReservationContextStaleException) {
                    // 提交前/提交时会话已变化：本次整体中止，没有发出 POST；
                    // 不占新会话防重键、不清新账号会话、不冒充已发送。
                    state = state.copy(
                        loading = false,
                        showConfirm = false,
                        error = e.message ?: "登录状态已变化，请重新确认后再试。",
                    )
                    return@launch
                }
                // 其它异常（含超时/未知）：结果未知，已尝试发出或可能发出，登记为已提交。
                state = state.copy(loading = true, error = null, notice = null,
                    showConfirm = false, reservationSubmitted = true)
                checkReservationsAfterAttempt(context, mode, beforeIds, e)
                return@launch
            }
            if (!isReservationContextCurrent(context, mode)) return@launch
            when (attempt) {
                LibraryReservationAttempt.NOT_SENT -> {
                    // POST 前验证/CAPTCHA 拒绝：不算已发送，不影响正常网页回退，
                    // 也不置 reservationSubmitted。
                    state = state.copy(
                        loading = false,
                        showConfirm = false,
                        error = "需要验证码或学校未允许本次提交，请在学校页面预约。",
                    )
                }
                LibraryReservationAttempt.SENT -> {
                    state = state.copy(loading = true, error = null, notice = null,
                        showConfirm = false, reservationSubmitted = true)
                    checkReservationsAfterAttempt(context, mode, beforeIds, null)
                }
            }
        }
    }

    private fun isReservationContextCurrent(context: ReservationContext, mode: RoomSeatMode): Boolean =
        ops.sessionToken() == context.token && state.building?.id == context.buildingId &&
            state.room?.roomId == context.roomId && state.date == context.date &&
            state.seatMode == mode && when (mode) {
                RoomSeatMode.TIME -> state.timeSelectedSeat?.id == context.seatId &&
                    state.timeBegin == context.startValue && state.timeFinish == context.endValue
                RoomSeatMode.SEAT -> state.seat?.id == context.seatId &&
                    state.start?.id == context.startId && state.end?.id == context.endId
            }

    /** 提交后核对「我的预约」；迟到/新账号/新房间响应不得写回状态。 */
    private suspend fun checkReservationsAfterAttempt(
        context: ReservationContext,
        mode: RoomSeatMode,
        beforeIds: Set<String>,
        requestError: Throwable?,
    ) {
        val checked = try {
            Result.success(ops.myReservations())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
        // 核对响应迟到：会话/房间/模式已变就丢弃，不写新上下文。
        if (!isReservationContextCurrent(context, mode)) return
        checked.onSuccess { list ->
            val current = activeReservations(list)
            val matched = current.firstOrNull { it.id !in beforeIds }?.takeIf {
                LibraryReservationMatch.matches(
                    it, context.date, context.startValue, context.endValue, context.seatName,
                )
            }
            val legacyMatchWithoutNewId = current.firstOrNull {
                it.id in beforeIds && LibraryReservationMatch.matches(
                    it, context.date, context.startValue, context.endValue, context.seatName,
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

    // ───────────────── 本机个人隐藏 ─────────────────

    /**
     * 读取当前房间隐藏名单：只做一次 [LibraryHiddenSeatsStore.entries] 读取，
     * ids 由同一次结果派生，避免两份快照不一致。响应校验房间+代次，旧房间响应
     * 不污染新房间。
     */
    private fun loadHiddenSeats() {
        if (state.hiddenBusy) return
        val buildingId = state.building?.id
        val roomId = state.room?.roomId
        if (buildingId == null || roomId == null) {
            state = state.copy(
                hiddenLoaded = false,
                hiddenBusy = false,
                hiddenError = "当前房间信息不完整，无法恢复隐藏偏好",
            )
            return
        }
        val generation = ++hiddenGeneration
        state = state.copy(hiddenBusy = true, hiddenLoaded = false, hiddenError = null)
        viewModelScope.launch {
            hiddenStoreMutex.lock()
            try {
                if (!isHiddenContextCurrent(generation, buildingId, roomId)) return@launch
                val entries = hiddenSeatsStore.entries(buildingId, roomId)
                if (!isHiddenContextCurrent(generation, buildingId, roomId)) return@launch
                publishHiddenEntries(entries)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isHiddenContextCurrent(generation, buildingId, roomId)) {
                    state = state.copy(
                        hiddenLoaded = false,
                        hiddenBusy = false,
                        hiddenError = "隐藏名单读取失败",
                    )
                }
            } finally {
                hiddenStoreMutex.unlock()
            }
        }
    }

    private fun publishHiddenEntries(
        entries: List<LibraryHiddenSeat>,
        updateUndo: (LibraryHiddenSeat?) -> LibraryHiddenSeat? = { it },
    ) {
        val hiddenIds = entries.mapTo(linkedSetOf()) { it.seatId }
        val selectedSeatHidden = state.seat?.id?.let { it in hiddenIds } == true
        val selectedTimeSeatHidden = state.timeSelectedSeat?.id?.let { it in hiddenIds } == true
        if (selectedSeatHidden || selectedTimeSeatHidden) timeRequestGeneration++
        state = state.copy(
            hiddenSeatIds = hiddenIds,
            hiddenEntries = entries,
            hiddenLoaded = true,
            hiddenBusy = false,
            hiddenError = null,
            seat = state.seat.takeUnless { selectedSeatHidden },
            startTimes = if (selectedSeatHidden) emptyList() else state.startTimes,
            endTimes = if (selectedSeatHidden) emptyList() else state.endTimes,
            start = if (selectedSeatHidden) null else state.start,
            end = if (selectedSeatHidden) null else state.end,
            showTimeDialog = if (selectedSeatHidden) false else state.showTimeDialog,
            timeSelectedSeat = state.timeSelectedSeat.takeUnless { selectedTimeSeatHidden },
            timeStartTimes = if (selectedTimeSeatHidden) emptyList() else state.timeStartTimes,
            timeEndTimes = if (selectedTimeSeatHidden) emptyList() else state.timeEndTimes,
            timeStart = if (selectedTimeSeatHidden) null else state.timeStart,
            timeStop = if (selectedTimeSeatHidden) null else state.timeStop,
            timeSelecting = if (selectedTimeSeatHidden) false else state.timeSelecting,
            showConfirm = if (selectedSeatHidden || selectedTimeSeatHidden) false else state.showConfirm,
            undoHidden = updateUndo(state.undoHidden),
            error = null,
        )
    }

    private fun performHiddenWrite(
        buildingId: String,
        roomId: String,
        mutation: suspend () -> Boolean,
        beforeWrite: (UiState) -> UiState = { it },
        updateUndo: (LibraryHiddenSeat?) -> LibraryHiddenSeat? = { it },
    ) {
        if (state.hiddenBusy) return
        val generation = ++hiddenGeneration
        state = beforeWrite(state).copy(
            hiddenBusy = true,
            hiddenActionError = null,
            hiddenError = null,
            error = null,
        )
        viewModelScope.launch {
            hiddenStoreMutex.lock()
            try {
                if (!mutation()) error("hidden preference write failed")
                val entries = hiddenSeatsStore.entries(buildingId, roomId)
                if (!isHiddenContextCurrent(generation, buildingId, roomId)) return@launch
                publishHiddenEntries(entries, updateUndo)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isHiddenContextCurrent(generation, buildingId, roomId)) {
                    state = state.copy(
                        hiddenBusy = false,
                        hiddenLoaded = false,
                        hiddenError = "隐藏名单保存失败，请重试恢复",
                        hiddenActionError = "保存失败，请重试",
                    )
                }
            } finally {
                hiddenStoreMutex.unlock()
            }
        }
    }

    fun retryHiddenSeats() {
        loadHiddenSeats()
    }

    private fun isHiddenContextCurrent(generation: Long, buildingId: String, roomId: String): Boolean =
        generation == hiddenGeneration &&
            state.building?.id == buildingId &&
            state.room?.roomId == roomId

    fun hideSeat(seat: LibrarySeat) {
        if (state.hiddenBusy) {
            state = state.copy(error = "本机隐藏偏好正在更新，请稍后再隐藏")
            return
        }
        if (!state.hiddenLoaded || state.hiddenError != null) {
            state = state.copy(error = "隐藏偏好未恢复，请重试后再隐藏")
            return
        }
        val buildingId = state.building?.id ?: return
        val roomId = state.room?.roomId ?: return
        val entry = LibraryHiddenSeat(
            system = LibraryHiddenSeat.SYSTEM_LIBSEAT,
            buildingId = buildingId,
            roomId = roomId,
            seatId = seat.id,
            nameSnapshot = seat.name,
        )
        // 在写入前立即清掉选中状态；performHiddenWrite 同步置忙，
        // 直到持久化并重读房间名单完成后才允许再次选座或确认。
        timeRequestGeneration++
        performHiddenWrite(
            buildingId = buildingId,
            roomId = roomId,
            mutation = { hiddenSeatsStore.hide(entry) },
            beforeWrite = { current ->
                val selectedSeat = current.seat?.id == seat.id
                current.copy(
                    timeSelectedSeat = current.timeSelectedSeat?.takeUnless { it.id == seat.id },
                    seat = current.seat?.takeUnless { it.id == seat.id },
                    startTimes = if (selectedSeat) emptyList() else current.startTimes,
                    endTimes = if (selectedSeat) emptyList() else current.endTimes,
                    start = if (selectedSeat) null else current.start,
                    end = if (selectedSeat) null else current.end,
                    showTimeDialog = if (selectedSeat) false else current.showTimeDialog,
                    showConfirm = false,
                    timeStartTimes = emptyList(),
                    timeEndTimes = emptyList(),
                    timeStart = null,
                    timeStop = null,
                    timeSelecting = false,
                )
            },
            updateUndo = { entry },
        )
    }

    fun undoHide() {
        if (state.hiddenBusy) {
            state = state.copy(hiddenActionError = "隐藏名单正在更新，请稍后再撤销")
            return
        }
        if (!state.hiddenLoaded || state.hiddenError != null) return
        val entry = state.undoHidden ?: return
        if (state.building?.id != entry.buildingId || state.room?.roomId != entry.roomId) return
        performHiddenWrite(
            buildingId = entry.buildingId,
            roomId = entry.roomId,
            mutation = { hiddenSeatsStore.restore(entry.buildingId, entry.roomId, entry.seatId) },
            updateUndo = { null },
        )
    }

    fun clearUndoHide() {
        state = state.copy(undoHidden = null)
    }

    fun openHiddenSheet() {
        state = state.copy(showHiddenSheet = true)
        if (!state.hiddenBusy) loadHiddenSeats()
    }

    fun closeHiddenSheet() {
        state = state.copy(showHiddenSheet = false)
    }

    fun restoreHidden(seatId: String) {
        if (!state.hiddenLoaded || state.hiddenBusy || state.hiddenError != null) return
        val buildingId = state.building?.id ?: return
        val roomId = state.room?.roomId ?: return
        performHiddenWrite(
            buildingId = buildingId,
            roomId = roomId,
            mutation = { hiddenSeatsStore.restore(buildingId, roomId, seatId) },
            updateUndo = { undo -> undo?.takeUnless { it.seatId == seatId } },
        )
    }

    fun restoreAllHidden() {
        if (!state.hiddenLoaded || state.hiddenBusy || state.hiddenError != null) return
        val buildingId = state.building?.id ?: return
        val roomId = state.room?.roomId ?: return
        performHiddenWrite(
            buildingId = buildingId,
            roomId = roomId,
            mutation = { hiddenSeatsStore.restoreAll(buildingId, roomId) },
            updateUndo = { null },
        )
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
        logoutCleanup()
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
