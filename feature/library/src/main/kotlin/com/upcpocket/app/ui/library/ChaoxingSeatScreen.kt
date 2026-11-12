package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingSeatPlace
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoom
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail
import com.upcpocket.app.data.upc.UpcOfficialEntryLinks
import com.upcpocket.app.data.prefs.LibraryHiddenSeatsStore
import com.upcpocket.app.library.ChaoxingAuthFailure
import com.upcpocket.app.library.ChaoxingLoginResult
import com.upcpocket.app.library.ChaoxingService
import com.upcpocket.app.ui.components.GlassCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaoxingSeatScreen(
    service: ChaoxingService,
    hiddenSeatsStore: LibraryHiddenSeatsStore,
    initialKind: ChaoxingSeatKind = ChaoxingSeatKind.SEAT,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val session by service.session.collectAsStateWithLifecycle()
    val vm: ChaoxingSeatViewModel = viewModel(
        factory = ChaoxingSeatViewModel.factory(service, initialKind, hiddenSeatsStore),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val accountState by service.accountState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var autoLoginBusy by remember { mutableStateOf(false) }
    var autoLoginError by remember { mutableStateOf<String?>(null) }
    val canAutoLogin = accountState.isCredentialSaved && service.hasSavedAccount()

    if (state.detail != null) {
        ChaoxingSeatDetailPage(
            state = state,
            vm = vm,
            onExit = vm::closeRoom,
            onOpenOfficial = {
                if (state.interactionRequired) onOpenLogin()
                else openChaoxingOfficial(context, state.officialUrl)
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("超星座位")
                        Text(
                            state.kind.campusLabel,
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
                    IconButton(onClick = { vm.refresh() }, enabled = !state.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新区域")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ChaoxingFilterPanel(
                    state = state,
                    onKind = vm::selectKind,
                    onDate = vm::selectDate,
                )
            }
            if (session == null) {
                item {
                    GlassCard {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("登录后查看座位", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (canAutoLogin) {
                                    "使用已保存的学校账号登录；需要验证码时打开学校网页。"
                                } else {
                                    "登录后选择座位和时段。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (canAutoLogin) {
                                Button(
                                    onClick = {
                                        if (autoLoginBusy) return@Button
                                        autoLoginBusy = true
                                        autoLoginError = null
                                        scope.launch {
                                            try {
                                                when (val result = service.loginWithSavedAccount()) {
                                                    ChaoxingLoginResult.Success -> autoLoginBusy = false
                                                    is ChaoxingLoginResult.Failure -> {
                                                        autoLoginBusy = false
                                                        autoLoginError = chaoxingLoginMessage(result.reason)
                                                        if (result.reason == ChaoxingAuthFailure.INTERACTION_REQUIRED) {
                                                            onOpenLogin()
                                                        }
                                                    }
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                autoLoginBusy = false
                                                autoLoginError = "登录失败，请用网页登录。"
                                            }
                                        }
                                    },
                                    enabled = !autoLoginBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (autoLoginBusy) "正在登录…" else "用已保存账号登录",
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onOpenLogin, enabled = !autoLoginBusy) {
                                    Text("网页登录")
                                }
                                OutlinedButton(onClick = { openChaoxingOfficial(context, state.officialUrl) }) {
                                    Text("学校页面")
                                }
                            }
                            autoLoginError?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            } else {
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { message ->
                    item {
                        CompactNotice("暂时无法读取", message)
                        TextButton(onClick = vm::refresh, enabled = !state.busy) { Text("重试") }
                    }
                }
                if (state.detailBusy) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "区域",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${state.rooms.size} 个 · ${shortChaoxingDate(state.selectedDate)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!state.busy && state.error == null && state.rooms.isEmpty()) {
                    item {
                        CompactNotice(
                            "没有区域",
                            "换个日期再试；这不代表有座。",
                        )
                    }
                }
                items(state.rooms, key = { it.id }) { room ->
                    ChaoxingAreaRow(
                        room = room,
                        selectedDate = state.selectedDate,
                        onClick = { vm.openRoom(room) },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { openChaoxingOfficial(context, state.officialUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("学校预约页")
                    }
                }
                item {
                    Text(
                        "可提交预约；签到在学校页面完成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChaoxingFilterPanel(
    state: ChaoxingSeatUiState,
    onKind: (ChaoxingSeatKind) -> Unit,
    onDate: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChaoxingSeatKind.entries.forEach { kind ->
                FilterChip(
                    selected = state.kind == kind,
                    onClick = { onKind(kind) },
                    enabled = !state.busy,
                    label = { Text(kind.campusLabel) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.dates.forEach { date ->
                FilterChip(
                    selected = state.selectedDate == date,
                    onClick = { onDate(date) },
                    enabled = !state.busy,
                    label = { Text(shortChaoxingDate(date)) },
                )
            }
        }
    }
}

@Composable
internal fun ChaoxingAreaRow(
    room: ChaoxingSeatRoom,
    selectedDate: String,
    onClick: () -> Unit,
) {
    val free = room.usedCount?.let { (room.capacity - it).coerceAtLeast(0) }
    val occupancy = free?.let { if (room.capacity > 0) it.toFloat() / room.capacity else null }
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
                    "${formatMinute(room.startMinute)}–${formatMinute(room.endMinute)} · 共 ${room.capacity} 座",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        free == null -> "已用座位待查询"
                        free == 0 -> "当前无余座"
                        else -> "余 $free 座"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (free == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                chaoxingAvailability(room, selectedDate)?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                occupancy?.let { value ->
                    LinearProgressIndicator(
                        progress = { value },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.widthIn(min = 76.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text(if ((free ?: 0) > 0) "查看座位" else "查看") }
        }
    }
}

@Composable
private fun ChaoxingSeatDetailPage(
    state: ChaoxingSeatUiState, vm: ChaoxingSeatViewModel,
    onExit: () -> Unit, onOpenOfficial: () -> Unit,
) {
    ChaoxingSeatDetailContent(state, onExit, onOpenOfficial,
        vm::setSeatMode, vm::selectStart, vm::selectEnd, vm::searchSeats, vm::selectSeat,
        vm::hideSeat, vm::openHiddenSheet, vm::closeHiddenSheet, vm::restoreHidden,
        vm::restoreAllHidden, vm::reserveSelected, vm::clearHiddenNotice, vm::reloadHidden)
}

/** Production detail UI; debug screenshots invoke this exact component with synthetic state. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChaoxingSeatDetailContent(
    state: ChaoxingSeatUiState,
    onExit: () -> Unit, onOpenOfficial: () -> Unit,
    onMode: (RoomSeatMode) -> Unit, onStart: (Int) -> Unit, onEnd: (Int) -> Unit,
    onSearch: () -> Unit, onSelect: (String) -> Unit, onHide: (String) -> Unit,
    onOpenHidden: () -> Unit, onCloseHidden: () -> Unit, onRestore: (String) -> Unit,
    onRestoreAll: () -> Unit, onReserve: () -> Unit, onClearHiddenNotice: () -> Unit,
    onReloadHidden: () -> Unit,
) {
    val detail = state.detail ?: return
    val view = LocalView.current
    val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
    DisposableEffect(view, lightBars) {
        val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        val controller = activity?.window?.insetsController
        val previous = controller?.systemBarsAppearance ?: 0
        val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller?.setSystemBarsAppearance(if (lightBars) mask else 0, mask)
        onDispose { controller?.setSystemBarsAppearance(previous, mask) }
    }
    BackHandler(onBack = onExit)
    var timePart by remember { mutableStateOf<String?>(null) }
    var confirmKey by remember { mutableStateOf<ChaoxingSeatUiState?>(null) }
    var menu by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(state.undoHidden) {
        val entry = state.undoHidden ?: return@LaunchedEffect
        if (snack.showSnackbar("已隐藏座位 ${entry.nameSnapshot}", "撤销") == SnackbarResult.ActionPerformed) {
            onRestore(entry.seatId)
        }
        onClearHiddenNotice()
    }
    val range = if (state.selectedStart != null && state.selectedEnd != null) {
        "${formatMinute(state.selectedStart)}–${formatMinute(state.selectedEnd)} · " +
            formatDuration(state.selectedEnd - state.selectedStart)
    } else "选择时段"
    // Confirmation is tied to immutable selection + query generation, never revived by late results.
    val confirm = confirmKey?.takeIf {
        state.canConfirm() && it.kind == state.kind && it.selectedDate == state.selectedDate &&
            it.detail === state.detail && it.seatMode == state.seatMode &&
            it.queryGeneration == state.queryGeneration && it.selectedSeat == state.selectedSeat &&
            it.selectedStart == state.selectedStart && it.selectedEnd == state.selectedEnd
    }
    LaunchedEffect(state.kind, state.detail, state.selectedDate, state.seatMode,
        state.queryGeneration, state.selectedSeat, state.hiddenBusy, state.canConfirm()) { confirmKey = null }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Surface {
              Row(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回区域") }
                Column(Modifier.weight(1f)) {
                    Text(detail.name, style = MaterialTheme.typography.titleMedium)
                    Text("${state.kind.campusLabel} · ${shortChaoxingDate(state.selectedDate)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("隐藏的座位（${state.hiddenEntries.size}）") },
                            onClick = { menu = false; onOpenHidden() })
                        DropdownMenuItem(text = { Text("学校页面") }, onClick = { menu = false; onOpenOfficial() })
                    }
                }
              }
            }
        },
        bottomBar = {
            val official = state.reservationUnknown || state.reservationSucceeded || state.interactionRequired
            SeatSelectionBottomBar(state.selectedSeat, range,
                state.canConfirm() || official, {
                    if (official) onOpenOfficial() else confirmKey = state
                }, when {
                    state.reserving -> "提交中…"
                    state.reservationUnknown || state.reservationSucceeded -> "查看学校记录"
                    state.interactionRequired -> "完成验证"
                    else -> "确认预约"
                })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.seatMode == RoomSeatMode.SEAT, { onMode(RoomSeatMode.SEAT) }, label = { Text("按座位选") })
                FilterChip(state.seatMode == RoomSeatMode.TIME, { onMode(RoomSeatMode.TIME) }, label = { Text("按时段找") })
            } }
            item {
                if (state.seatMode == RoomSeatMode.TIME) {
                    TimeRangeControls(state.selectedStart?.let(::formatMinute), state.selectedEnd?.let(::formatMinute),
                        { timePart = "start" }, { timePart = "end" }, onSearch,
                        state.validInterval() && !state.querying, state.querying)
                } else Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("使用时段", style = MaterialTheme.typography.labelMedium)
                            Text(range, style = MaterialTheme.typography.bodyMedium)
                            Text("开放 ${formatMinute(detail.startMinute)}–${formatMinute(detail.endMinute)}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { timePart = "start" }) { Text("更改") }
                    }
                }
            }
            if (state.seatMode == RoomSeatMode.SEAT && state.timeUnavailableReason != null) {
                item { CompactNotice("暂无可选时段", state.timeUnavailableReason) }
            }
            state.hiddenError?.let { item {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onReloadHidden, enabled = !state.hiddenBusy) { Text("重试") }
            } }
            state.reservationMessage?.let { item { CompactNotice(if (state.reservationUnknown) "提交结果未知" else "预约状态", it) } }
            if (state.seatMode == RoomSeatMode.TIME) {
                item { ChaoxingTimeResults(state, onSelect, onHide, onSearch) }
            } else {
                if (state.querying) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.detailError?.let { item {
                    CompactNotice("查询失败", it)
                    TextButton(onClick = onSearch) { Text("重试") }
                } }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(MaterialTheme.colorScheme.primary, "可选")
                    LegendDot(MaterialTheme.colorScheme.error, if (state.usedSeatsLoaded) "已用" else "已用未知")
                    LegendDot(MaterialTheme.colorScheme.tertiary, "已选")
                    if (state.hiddenIds.isNotEmpty()) LegendDot(MaterialTheme.colorScheme.outline, "已隐藏")
                } }
                item { ChaoxingSeatMap(detail.places, state.usedSeats, state.usedSeatsLoaded,
                    state.selectedSeat, onSelect, state.hiddenIds, onHide, detail.disabledSeatNums) }
                state.selectedSeat?.let { seat -> item {
                    TextButton(onClick = { onHide(seat) }, enabled = !state.hiddenBusy) { Text("隐藏座位 $seat") }
                } }
            }
        }
    }
    if (confirm != null) AlertDialog(
        onDismissRequest = { confirmKey = null }, title = { Text("确认预约") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${state.kind.campusLabel} · ${detail.name} · ${state.selectedSeat}")
            Text("${state.selectedDate} · $range")
            Text("提交后占用座位；结果未知时请先核对学校记录。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { confirmKey = null; onReserve() }, enabled = state.canConfirm()) { Text("确认预约") } },
        dismissButton = { TextButton(onClick = { confirmKey = null }) { Text("取消") } },
    )
    if (state.showHiddenSheet) HiddenSeatManagementSheet(state.hiddenEntries, onCloseHidden, onRestore, onRestoreAll)
    if (timePart != null) ModalBottomSheet(onDismissRequest = { timePart = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (timePart == "start") "开始时间" else "结束时间", style = MaterialTheme.typography.titleMedium)
            val options = if (timePart == "start") chaoxingStartOptions(detail, state.selectedDate)
                else state.selectedStart?.let { chaoxingEndOptions(detail, it, state.selectedDate) }.orEmpty()
            if (options.isEmpty()) Text(state.timeUnavailableReason ?: "没有可选时间")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { minute -> FilterChip(
                    selected = minute == if (timePart == "start") state.selectedStart else state.selectedEnd,
                    onClick = {
                        if (timePart == "start") { onStart(minute); timePart = "end" }
                        else { onEnd(minute); timePart = null }
                    }, label = { Text(formatMinute(minute)) }) }
            }
        }
    }
}

@Composable
internal fun ChaoxingTimeResults(state: ChaoxingSeatUiState, onSelect: (String) -> Unit,
    onHide: (String) -> Unit, onRetry: () -> Unit) {
    val candidates = state.candidates
    val visible = candidates.filter { it.seatNum !in state.hiddenIds }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.timeUnavailableReason != null -> CompactNotice("暂无可选时段", state.timeUnavailableReason)
            state.querying -> CompactNotice("正在查找", "只查询当前房间。")
            state.detailError != null -> {
                CompactNotice("查询失败", state.detailError)
                TextButton(onClick = onRetry) { Text("重试") }
            }
            !state.usedSeatsLoaded -> CompactNotice("按时段找", "选择时段，查找可约座位")
            candidates.isEmpty() -> CompactNotice("这个时段暂无可约座位", "可以修改时段再找。")
            visible.isEmpty() -> CompactNotice("可约座位已被隐藏", "可在右上角查看或恢复隐藏项。")
            else -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("共 ${candidates.size} 个", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    val hidden = candidates.size - visible.size
                    if (hidden > 0) Text("隐藏 $hidden", style = MaterialTheme.typography.labelSmall)
                }
                visible.forEach { place -> SeatCandidateRow(place.seatNum, state.hiddenLoaded && !state.hiddenBusy && !state.reserving,
                    state.selectedSeat == place.seatNum, { onSelect(place.seatNum) }, { onHide(place.seatNum) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChaoxingSeatMap(
    places: List<ChaoxingSeatPlace>,
    usedSeats: Set<String>,
    usedSeatsLoaded: Boolean,
    selectedSeat: String?,
    onSelect: (String) -> Unit,
    hiddenIds: Set<String> = emptySet(),
    onHide: (String) -> Unit = {},
    disabledIds: Set<String> = emptySet(),
) {
    if (places.isEmpty()) return
    val minX = places.minOf { it.x }
    val minY = places.minOf { it.y }
    val maxX = places.maxOf { it.x + it.width }
    val maxY = places.maxOf { it.y + it.height }
    val contentWidth = (maxX - minX).coerceAtLeast(1f)
    val contentHeight = (maxY - minY).coerceAtLeast(1f)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 画布坐标来自学校，不能假设是 px/dp。优先按宽度完整放下；但长排座位图
        // 缩到整屏会让座位只有几 dp、无法点选，因此保留一个可读下限并允许横滑。
        val available = (maxWidth - 24.dp).value.coerceAtLeast(240f)
        val fitScale = available / contentWidth
        val scale = fitScale.coerceIn(0.28f, 0.9f)
        val mapWidth = (contentWidth * scale + 16f).dp
        val mapHeight = (contentHeight * scale + 16f).dp
        Box(
            Modifier.fillMaxWidth().heightIn(max = 430.dp)
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.width(mapWidth).height(mapHeight).padding(8.dp)) {
                places.forEach { place ->
                    val hidden = place.seatNum in hiddenIds
                    val disabled = place.seatNum in disabledIds
                    val used = place.seatNum in usedSeats
                    val selected = place.seatNum == selectedSeat
                    val color = when {
                        hidden -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        disabled -> MaterialTheme.colorScheme.surfaceVariant
                        selected -> MaterialTheme.colorScheme.tertiaryContainer
                        !usedSeatsLoaded -> MaterialTheme.colorScheme.surfaceVariant
                        used -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                    val textColor = when {
                        selected -> MaterialTheme.colorScheme.onTertiaryContainer
                        !usedSeatsLoaded -> MaterialTheme.colorScheme.onSurfaceVariant
                        used -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    }
                    val seatWidth = (place.width * scale).dp.coerceAtLeast(22.dp)
                    val seatHeight = (place.height * scale).dp.coerceAtLeast(22.dp)
                    Surface(
                        modifier = Modifier
                            .offset(
                                x = ((place.x - minX) * scale).dp,
                                y = ((place.y - minY) * scale).dp,
                            )
                            .size(width = seatWidth, height = seatHeight)
                            .graphicsLayer(rotationZ = place.rotate)
                            .semantics {
                                contentDescription = "座位 ${place.seatNum}，" + when {
                                    hidden -> "已在本机隐藏"
                                    disabled -> "不可预约"
                                    !usedSeatsLoaded -> "当前时段已用状态未取得"
                                    used -> "当前时段已用"
                                    else -> "当前时段可用"
                                }
                            }
                            .combinedClickable(
                                onClick = { if (usedSeatsLoaded && !used && !hidden && !disabled) onSelect(place.seatNum) },
                                onLongClick = { if (!hidden) onHide(place.seatNum) },
                            ),
                        shape = RoundedCornerShape(5.dp),
                        color = color,
                        tonalElevation = if (selected) 3.dp else 0.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (hidden) "·" else place.seatNum,
                                color = textColor,
                                fontSize = if (seatWidth >= 26.dp) 9.sp else 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

internal fun formatDuration(minutes: Int): String = when {
    minutes <= 0 -> "时长未知"
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
}

private fun chaoxingAvailability(room: ChaoxingSeatRoom, selectedDate: String): String? {
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
    if (selectedDate != today) return null
    val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Shanghai"))
    val minute = now.hour * 60 + now.minute
    return when {
        minute < room.startMinute -> "未到开放时间"
        minute >= room.endMinute -> "今日已结束"
        else -> null
    }
}

private fun shortChaoxingDate(value: String): String = value.takeLast(5)

private fun openChaoxingOfficial(context: android.content.Context, url: String) {
    if (!UpcOfficialEntryLinks.isAllowedChaoxingHost(url)) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
