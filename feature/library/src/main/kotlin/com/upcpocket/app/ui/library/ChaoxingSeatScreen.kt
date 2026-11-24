package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import com.upcpocket.app.ui.components.GlassDropdownMenu as DropdownMenu
import com.upcpocket.app.ui.components.GlassDropdownMenuItem as DropdownMenuItem
import com.upcpocket.app.ui.components.GlassSnackbarHost as SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import com.upcpocket.app.ui.components.GlassIconButton as IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.upcpocket.app.ui.components.GlassModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import com.upcpocket.app.ui.components.GlassSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
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
import com.upcpocket.app.ui.components.glassPane
import com.upcpocket.app.ui.components.GlassCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import com.upcpocket.app.ui.components.GlassRadioButton as RadioButton

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
    val observedState by vm.state.collectAsStateWithLifecycle()
    val state = observedState
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
        containerColor = com.upcpocket.app.ui.theme.appScaffoldContainerColor(),
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
                title = {
                    Column {
                        Text("超星座位")
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(contentType = "filters") {
                ChaoxingFilterPanel(
                    state = state,
                    onKind = vm::selectKind,
                    onDate = vm::selectDate,
                )
            }
            if (session == null) {
                item(contentType = "login_gate") {
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
                                com.upcpocket.app.ui.components.CampusButton(
                                    onClick = {
                                        if (autoLoginBusy) return@CampusButton
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
                                com.upcpocket.app.ui.components.CampusOutlinedButton(onClick = onOpenLogin, enabled = !autoLoginBusy) {
                                    Text("网页登录")
                                }
                                com.upcpocket.app.ui.components.CampusOutlinedButton(onClick = { openChaoxingOfficial(context, state.officialUrl) }) {
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
                if (state.busy) item(contentType = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { message ->
                    item(contentType = "error") {
                        CompactNotice("暂时无法读取", message)
                        com.upcpocket.app.ui.components.CampusTextButton(onClick = vm::refresh, enabled = !state.busy) { Text("重试") }
                    }
                }
                if (state.detailBusy) {
                    item(contentType = "detail_loading") {
                        Row(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                }
                item(contentType = "area_summary") {
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
                        IconButton(onClick = vm::refresh, enabled = !state.busy) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新区域")
                        }
                    }
                }
                if (!state.busy && state.error == null && state.rooms.isEmpty()) {
                    item(contentType = "empty") {
                        CompactNotice(
                            "没有区域",
                            "换个日期再试；这不代表有座。",
                        )
                    }
                }
                items(state.rooms, key = { it.id }, contentType = { "area" }) { room ->
                    ChaoxingAreaRow(
                        room = room,
                        selectedDate = state.selectedDate,
                        onClick = { vm.openRoom(room) },
                    )
                }
                item(contentType = "official_page") {
                    com.upcpocket.app.ui.components.CampusOutlinedButton(
                        onClick = { openChaoxingOfficial(context, state.officialUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("学校预约页")
                    }
                }
                item(contentType = "reservation_note") {
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
            .glassPane(RoundedCornerShape(18.dp), MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "校区",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChaoxingSeatKind.entries.forEach { kind ->
                com.upcpocket.app.ui.components.CampusFilterChip(
                    selected = state.kind == kind,
                    onClick = { onKind(kind) },
                    enabled = !state.busy,
                    label = { Text(kind.campusLabel) },
                )
            }
        }
        Text(
            "日期",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.dates.forEach { date ->
                com.upcpocket.app.ui.components.CampusFilterChip(
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
            com.upcpocket.app.ui.components.CampusOutlinedButton(
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
    val presentationAreaKey = state.kind.hiddenBuilding
    val presentationRoomKey = detail.roomId.toString()
    val mapViewportState = rememberSeatMapViewportState(
        state.kind.hiddenSystem,
        presentationAreaKey,
        presentationRoomKey,
    )
    var showSeatMap by rememberSeatPresentationMapState(
        state.kind.hiddenSystem,
        presentationAreaKey,
        presentationRoomKey,
    )
    val sortedPlaces = remember(detail.roomId, detail.places) {
        sortSeatPresentations(
            detail.places,
            nameOf = ChaoxingSeatPlace::seatNum,
            stableIdOf = ChaoxingSeatPlace::seatNum,
        )
    }
    val density = LocalDensity.current
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
        containerColor = com.upcpocket.app.ui.theme.appScaffoldContainerColor(),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
                title = {
                    Column {
                        Text(detail.name, style = MaterialTheme.typography.titleMedium)
                        Text("${state.kind.campusLabel} · ${shortChaoxingDate(state.selectedDate)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回区域") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("隐藏的座位（${state.hiddenEntries.size}）") },
                                onClick = { menu = false; onOpenHidden() })
                            DropdownMenuItem(text = { Text("学校页面") }, onClick = { menu = false; onOpenOfficial() })
                        }
                    }
                },
            )
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val listColumns = seatListColumnCount(
                availableWidthDp = (maxWidth - 32.dp).coerceAtLeast(1.dp).value,
                fontScale = density.fontScale,
            )
            val seatRows = remember(sortedPlaces, listColumns) { sortedPlaces.chunked(listColumns) }
            LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(contentType = "seat_mode_controls") { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.upcpocket.app.ui.components.CampusFilterChip(state.seatMode == RoomSeatMode.SEAT, { onMode(RoomSeatMode.SEAT) }, label = { Text("按座位选") })
                com.upcpocket.app.ui.components.CampusFilterChip(state.seatMode == RoomSeatMode.TIME, { onMode(RoomSeatMode.TIME) }, label = { Text("按时段找") })
            } }
            item(contentType = "time_controls") {
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
                        com.upcpocket.app.ui.components.CampusTextButton(onClick = { timePart = "start" }) { Text("更改") }
                    }
                }
            }
            if (state.seatMode == RoomSeatMode.SEAT && state.timeUnavailableReason != null) {
                item(contentType = "time_unavailable") { CompactNotice("暂无可选时段", state.timeUnavailableReason) }
            }
            state.hiddenError?.let { item(contentType = "hidden_error") {
                Text(it, color = MaterialTheme.colorScheme.error)
                com.upcpocket.app.ui.components.CampusTextButton(onClick = onReloadHidden, enabled = !state.hiddenBusy) { Text("重试") }
            } }
            if (state.hiddenBusy && !state.hiddenLoaded) item(contentType = "hidden_loading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在恢复本机隐藏偏好…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            state.reservationMessage?.let { item(contentType = "reservation_status") { CompactNotice(if (state.reservationUnknown) "提交结果未知" else "预约状态", it) } }
            if (state.seatMode == RoomSeatMode.TIME) {
                item(contentType = "time_results") { ChaoxingTimeResults(state, onSelect, onHide, onSearch) }
            } else {
                if (state.querying) item(contentType = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.detailError?.let { item(contentType = "detail_error") {
                    CompactNotice("查询失败", it)
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = onSearch) { Text("重试") }
                } }
                item(contentType = "seat_legend") { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(MaterialTheme.colorScheme.primary, "可选")
                    LegendDot(MaterialTheme.colorScheme.error, if (state.usedSeatsLoaded) "已用" else "已用未知")
                    LegendDot(MaterialTheme.colorScheme.tertiary, "已选")
                    if (state.hiddenIds.isNotEmpty()) LegendDot(MaterialTheme.colorScheme.outline, "已隐藏")
                } }
                item(contentType = "seat_presentation_control") {
                    SeatPresentationModeControl(showMap = showSeatMap, onShowMapChange = { showSeatMap = it })
                }
                if (showSeatMap) {
                    item(contentType = "seat_map") {
                        ChaoxingSeatMap(
                            places = detail.places,
                            usedSeats = state.usedSeats,
                            usedSeatsLoaded = state.usedSeatsLoaded,
                            selectedSeat = state.selectedSeat,
                            onSelect = onSelect,
                            hiddenIds = state.hiddenIds,
                            onHide = onHide,
                            disabledIds = detail.disabledSeatNums,
                            selectionEnabled = state.hiddenLoaded && !state.hiddenBusy &&
                                !state.querying && !state.reserving,
                            mapScopeKey = "${state.kind.hiddenSystem}|$presentationAreaKey|$presentationRoomKey",
                            viewportState = mapViewportState,
                        )
                    }
                    item(contentType = "seat_map_help") {
                        Text("点可用位置选择；长按可隐藏；双指缩放或拖动查看，复位返回概览。",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(
                        items = seatRows,
                        key = { row -> "chaoxing-row-${state.kind.name}-${presentationRoomKey}-${row.first().seatNum}" },
                        contentType = { "chaoxing_seat_list_row" },
                    ) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { place ->
                                ChaoxingSeatListTile(
                                    modifier = Modifier.weight(1f),
                                    seatNum = place.seatNum,
                                    selected = state.selectedSeat == place.seatNum,
                                    hidden = place.seatNum in state.hiddenIds,
                                    disabled = place.seatNum in detail.disabledSeatNums,
                                    used = place.seatNum in state.usedSeats,
                                    usedSeatsLoaded = state.usedSeatsLoaded,
                                    hiddenLoaded = state.hiddenLoaded,
                                    hiddenBusy = state.hiddenBusy,
                                    querying = state.querying,
                                    reserving = state.reserving,
                                    onSelect = { onSelect(place.seatNum) },
                                    onHide = { onHide(place.seatNum) },
                                )
                            }
                            repeat(listColumns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                state.selectedSeat?.let { seat -> item(contentType = "selected_seat") {
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = { onHide(seat) }, enabled = !state.hiddenBusy) { Text("隐藏座位 $seat") }
                } }
            }
        }
    }
    }
    if (confirm != null) com.upcpocket.app.ui.components.CampusAlertDialog(
        onDismissRequest = { confirmKey = null }, title = { Text("确认预约") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${state.kind.campusLabel} · ${detail.name} · ${state.selectedSeat}")
            Text("${state.selectedDate} · $range")
            Text("提交后占用座位；结果未知时请先核对学校记录。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { com.upcpocket.app.ui.components.CampusTextButton(onClick = { confirmKey = null; onReserve() }, enabled = state.canConfirm()) { Text("确认预约") } },
        dismissButton = { com.upcpocket.app.ui.components.CampusTextButton(onClick = { confirmKey = null }) { Text("取消") } },
    )
    if (state.showHiddenSheet) HiddenSeatManagementSheet(state.hiddenEntries, onCloseHidden, onRestore, onRestoreAll)
    if (timePart != null) ModalBottomSheet(onDismissRequest = { timePart = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (timePart == "start") "开始时间" else "结束时间", style = MaterialTheme.typography.titleMedium)
            val options = if (timePart == "start") chaoxingStartOptions(detail, state.selectedDate)
                else state.selectedStart?.let { chaoxingEndOptions(detail, it, state.selectedDate) }.orEmpty()
            if (options.isEmpty()) Text(state.timeUnavailableReason ?: "没有可选时间")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { minute -> com.upcpocket.app.ui.components.CampusFilterChip(
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
    val visible = remember(candidates, state.hiddenIds) {
        candidates.filter { it.seatNum !in state.hiddenIds }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.timeUnavailableReason != null -> CompactNotice("暂无可选时段", state.timeUnavailableReason)
            state.querying -> CompactNotice("正在查找", "只查询当前房间。")
            state.detailError != null -> {
                CompactNotice("查询失败", state.detailError)
                com.upcpocket.app.ui.components.CampusTextButton(onClick = onRetry) { Text("重试") }
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
                visible.forEach { place ->
                    val hiddenPreferencesReady = state.hiddenLoaded && !state.hiddenBusy
                    SeatCandidateRow(
                        name = place.seatNum,
                        selectable = hiddenPreferencesReady && !state.reserving,
                        selected = state.selectedSeat == place.seatNum,
                        onSelect = { onSelect(place.seatNum) },
                        onHide = { onHide(place.seatNum) },
                        canHide = hiddenPreferencesReady,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChaoxingSeatListTile(
    modifier: Modifier,
    seatNum: String,
    selected: Boolean,
    hidden: Boolean,
    disabled: Boolean,
    used: Boolean,
    usedSeatsLoaded: Boolean,
    hiddenLoaded: Boolean,
    hiddenBusy: Boolean,
    querying: Boolean,
    reserving: Boolean,
    onSelect: () -> Unit,
    onHide: () -> Unit,
) {
    val canSelect = hiddenLoaded && !hiddenBusy && !querying && !reserving &&
        usedSeatsLoaded && !hidden && !disabled && !used
    val canHide = hiddenLoaded && !hiddenBusy && !hidden
    val status = when {
        hidden -> "本机已隐藏"
        disabled -> "不可预约"
        hiddenBusy || !hiddenLoaded -> "隐藏偏好未就绪"
        !usedSeatsLoaded -> "当前座位状态未读取"
        used -> "当前时段已用"
        selected -> "已选"
        else -> "可选"
    }
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.heightIn(min = 72.dp)
            .semantics {
                contentDescription = "座位 $seatNum，$status"
                this.selected = selected
            }
            .testTag("chaoxing_seat_tile_$seatNum")
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
            canSelect -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                seatNum,
                modifier = Modifier.fillMaxWidth().testTag("chaoxing_seat_name_$seatNum"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RadioButton(
                    selected = selected,
                    onClick = null,
                    enabled = canSelect,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Box {
                    IconButton(onClick = { menu = true }, enabled = canHide) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "隐藏座位 $seatNum")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("隐藏此座位") },
                            enabled = canHide,
                            onClick = { menu = false; onHide() },
                        )
                    }
                }
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
    selectionEnabled: Boolean = true,
    mapScopeKey: String = "chaoxing|default",
    viewportState: SeatMapViewportState? = null,
) {
    if (places.isEmpty()) return
    val rectangles = remember(places) {
        places.mapNotNull { place ->
            SeatMapRect(place.x, place.y, place.x + place.width, place.y + place.height)
                .rotatedBounds(place.rotate)
        }
    }
    val bounds = remember(rectangles) { seatMapBoundsFromRects(rectangles) } ?: return
    val smallestSeatDimension = remember(places) {
        places.mapNotNull { place ->
            val rect = SeatMapRect(place.x, place.y, place.x + place.width, place.y + place.height)
            minOf(rect.width, rect.height).takeIf { rect.isUsable() && it.isFinite() && it > 0f }
        }.minOrNull() ?: 1f
    }
    val density = LocalDensity.current
    var fallbackZoom by rememberSaveable("chaoxing", mapScopeKey, "zoom") { mutableStateOf(1f) }
    var fallbackCenterX by rememberSaveable("chaoxing", mapScopeKey, "center-x") { mutableStateOf(0.5f) }
    var fallbackCenterY by rememberSaveable("chaoxing", mapScopeKey, "center-y") { mutableStateOf(0.5f) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .height(368.dp)
                .clip(CHAOXING_MAP_SHAPE)
                .glassPane(CHAOXING_MAP_SHAPE, MaterialTheme.colorScheme.surfaceContainerLow),
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
                val maxZoom = max(4f, targetSeatPx / (smallestSeatDimension * overview.scale))
                    .coerceAtMost(64f)
                val transformState = remember(bounds, viewportSize, paddingPx, maxZoom, overview, viewportState) {
                    derivedStateOf {
                        val zoom = viewportState?.zoomMultiplier?.value ?: fallbackZoom
                        val centerX = viewportState?.centerXFraction?.value ?: fallbackCenterX
                        val centerY = viewportState?.centerYFraction?.value ?: fallbackCenterY
                        SeatMapViewportPose(zoom, centerX, centerY)
                            .toTransform(bounds, viewportSize, paddingPx, maxZoom) ?: overview
                    }
                }
                val currentScale by remember(transformState) {
                    derivedStateOf { transformState.value.scale }
                }
                val showLabels by remember(density, smallestSeatDimension, transformState) {
                    derivedStateOf {
                        with(density) { (smallestSeatDimension * transformState.value.scale).toDp() >= 24.dp }
                    }
                }
                val currentPlaces = rememberUpdatedState(places)
                val currentHiddenIds = rememberUpdatedState(hiddenIds)
                val currentUsedSeats = rememberUpdatedState(usedSeats)
                val currentDisabledIds = rememberUpdatedState(disabledIds)
                val currentInteractionsEnabled = rememberUpdatedState(selectionEnabled)
                val currentUsedSeatsLoaded = rememberUpdatedState(usedSeatsLoaded)
                val currentOnSelect = rememberUpdatedState(onSelect)
                val currentOnHide = rememberUpdatedState(onHide)

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
                    .pointerInput(mapScopeKey, bounds, viewportSize, maxZoom, transformState) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val next = transformState.value
                                .zoomAt(SeatMapPoint(centroid.x, centroid.y), zoomChange)
                                .panBy(SeatMapPoint(pan.x, pan.y))
                            saveTransform(next)
                        }
                    }
                    .pointerInput(mapScopeKey, bounds, viewportSize, transformState) {
                        detectTapGestures(
                            onTap = { offset ->
                                val contentPoint = transformState.value.displayToContent(
                                    SeatMapPoint(offset.x, offset.y),
                                )
                                val target = currentPlaces.value.asReversed().firstOrNull { place ->
                                    SeatMapRect(place.x, place.y, place.x + place.width, place.y + place.height)
                                        .containsRotated(contentPoint, place.rotate)
                                } ?: return@detectTapGestures
                                if (currentInteractionsEnabled.value && currentUsedSeatsLoaded.value &&
                                    target.seatNum !in currentUsedSeats.value &&
                                    target.seatNum !in currentHiddenIds.value &&
                                    target.seatNum !in currentDisabledIds.value
                                ) {
                                    currentOnSelect.value(target.seatNum)
                                }
                            },
                            onLongPress = { offset ->
                                val contentPoint = transformState.value.displayToContent(
                                    SeatMapPoint(offset.x, offset.y),
                                )
                                val target = currentPlaces.value.asReversed().firstOrNull { place ->
                                    SeatMapRect(place.x, place.y, place.x + place.width, place.y + place.height)
                                        .containsRotated(contentPoint, place.rotate)
                                } ?: return@detectTapGestures
                                if (currentInteractionsEnabled.value && target.seatNum !in currentHiddenIds.value) {
                                    currentOnHide.value(target.seatNum)
                                }
                            },
                        )
                    }

                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeatMapZoomControls(
                        onZoomOut = {
                            val center = SeatMapPoint(viewportSize.width / 2f, viewportSize.height / 2f)
                            saveTransform(transformState.value.zoomAt(center, 1f / 1.6f))
                        },
                        onZoomIn = {
                            val center = SeatMapPoint(viewportSize.width / 2f, viewportSize.height / 2f)
                            saveTransform(transformState.value.zoomAt(center, 1.6f))
                        },
                        onReset = { transformState.value.reset()?.let(::saveTransform) },
                    )
                    Box(Modifier.fillMaxWidth().weight(1f).then(gestureModifier)) {
                        val seatFontSize = if (showLabels) {
                            with(density) { (12.dp.toPx() / currentScale).sp }
                        } else {
                            12.sp
                        }
                        Box(
                            Modifier
                                .offset {
                                    val current = transformState.value
                                    IntOffset(
                                        x = (current.translationX + current.contentBounds.left * current.scale).roundToInt(),
                                        y = (current.translationY + current.contentBounds.top * current.scale).roundToInt(),
                                    )
                                }
                                .graphicsLayer {
                                    val current = transformState.value
                                    val densityScale = current.scale / density.density
                                    scaleX = densityScale
                                    scaleY = densityScale
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                                .size(bounds.width.dp, bounds.height.dp),
                        ) {
                            places.forEach { place ->
                                val rect = SeatMapRect(place.x, place.y, place.x + place.width, place.y + place.height)
                                if (!rect.isUsable()) return@forEach
                                val hidden = place.seatNum in hiddenIds
                                val disabled = place.seatNum in disabledIds
                                val used = place.seatNum in usedSeats
                                val selected = place.seatNum == selectedSeat
                                val status = when {
                                    hidden -> "本机已隐藏"
                                    disabled -> "不可预约"
                                    !usedSeatsLoaded -> "当前时段已用状态未取得"
                                    used -> "当前时段已用"
                                    else -> "当前时段可用"
                                }
                                val color = when {
                                    hidden -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    disabled -> MaterialTheme.colorScheme.surfaceVariant
                                    selected -> MaterialTheme.colorScheme.tertiaryContainer
                                    !usedSeatsLoaded -> MaterialTheme.colorScheme.surfaceVariant
                                    used -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                }
                                val textColor = when {
                                    hidden -> MaterialTheme.colorScheme.outline
                                    disabled || !usedSeatsLoaded -> MaterialTheme.colorScheme.onSurfaceVariant
                                    selected -> MaterialTheme.colorScheme.onTertiaryContainer
                                    used -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                                }
                                Box(
                                    Modifier
                                        .offset(
                                            x = (place.x - bounds.left).dp,
                                            y = (place.y - bounds.top).dp,
                                        )
                                        .size(place.width.dp, place.height.dp)
                                        .graphicsLayer(rotationZ = place.rotate)
                                        .padding(1.dp)
                                        .background(color, SEAT_CELL_SHAPE)
                                        .then(if (hidden) Modifier.drawBehind {
                                            drawLine(
                                                color = Color.Gray,
                                                start = Offset.Zero,
                                                end = Offset(size.width, size.height),
                                                strokeWidth = size.minDimension * 0.08f,
                                            )
                                        } else Modifier)
                                        .semantics { contentDescription = "座位 ${place.seatNum}，$status" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (showLabels && !hidden) {
                                        Text(
                                            place.seatNum,
                                            color = textColor,
                                            fontSize = seatFontSize,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Clip,
                                        )
                                    } else if (showLabels) {
                                        Text(
                                            "·",
                                            color = textColor,
                                            fontSize = seatFontSize,
                                            maxLines = 1,
                                        )
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

private val CHAOXING_MAP_SHAPE = RoundedCornerShape(18.dp)
private val SEAT_CELL_SHAPE = RoundedCornerShape(5.dp)
private val CHAOXING_CAMPUS_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Shanghai")

private fun chaoxingAvailability(room: ChaoxingSeatRoom, selectedDate: String): String? {
    val today = java.time.LocalDate.now(CHAOXING_CAMPUS_ZONE).toString()
    if (selectedDate != today) return null
    val now = java.time.LocalTime.now(CHAOXING_CAMPUS_ZONE)
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
