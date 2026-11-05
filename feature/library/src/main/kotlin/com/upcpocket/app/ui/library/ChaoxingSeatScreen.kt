package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
    val accountState by service.accountState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var autoLoginBusy by remember { mutableStateOf(false) }
    var autoLoginError by remember { mutableStateOf<String?>(null) }
    val canAutoLogin = accountState.isCredentialSaved && service.hasSavedAccount()

    LaunchedEffect(session) {
        if (session != null) vm.refresh()
    }

    if (state.detail != null) {
        ChaoxingSeatDetailPage(
            state = state,
            vm = vm,
            onExit = vm::closeRoom,
            onOpenOfficial = { openChaoxingOfficial(context, state.officialUrl) },
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
                            "座位查看 · ${state.kind.campusLabel}",
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
                            Text("登录后查看区域", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (canAutoLogin) {
                                    "可以使用本机加密保存的学校账号自动登录；学校要求验证码或二次验证时，" +
                                        "改走学校官方网页登录。"
                                } else {
                                    "区域、开放时间和已用座位来自学校；真实预约在学校官方页完成。" +
                                        "尚未保存可自动登录的学校账号。"
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
                                                autoLoginError = "自动登录失败，请改用学校网页登录"
                                            }
                                        }
                                    },
                                    enabled = !autoLoginBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (autoLoginBusy) "正在使用学校账号登录…" else "使用已保存学校账号登录",
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onOpenLogin, enabled = !autoLoginBusy) {
                                    Text(
                                        if (canAutoLogin) {
                                            "打开学校网页登录（验证码/二次验证）"
                                        } else {
                                            "网页登录"
                                        },
                                    )
                                }
                                OutlinedButton(onClick = { openChaoxingOfficial(context, state.officialUrl) }) {
                                    Text("官方页")
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
                            "没有区域数据",
                            "当前日期没有返回区域；请换日期重试，不能当作“有座”。",
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
                        Text("打开学校官方预约页")
                    }
                }
                item {
                    Text(
                        "这里只读展示区域、开放时间和所选时段已用座位；不提交预约、不签到。",
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChaoxingSeatDetailPage(
    state: ChaoxingSeatUiState,
    vm: ChaoxingSeatViewModel,
    onExit: () -> Unit,
    onOpenOfficial: () -> Unit,
) {
    BackHandler(onBack = onExit)
    val detail = state.detail ?: return
    val context = LocalContext.current
    var selectedSeat by rememberSaveable(detail.roomId) { mutableStateOf<String?>(null) }
    var showTimeSheet by rememberSaveable { mutableStateOf(false) }
    val selectedStart = state.selectedStart
    val selectedEnd = state.selectedEnd
    val timeLabel = if (selectedStart != null && selectedEnd != null) {
        "${formatMinute(selectedStart)}–${formatMinute(selectedEnd)} · " +
            formatDuration(selectedEnd - selectedStart)
    } else {
        "尚未选择时段"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(detail.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${shortChaoxingDate(state.selectedDate)} · ${state.kind.campusLabel}",
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
                actions = { TextButton(onClick = onOpenOfficial) { Text("官方页") } },
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
                        Text(
                            selectedSeat?.let { "已选 $it（仅查看）" } ?: "点选座位查看",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "$timeLabel · " + if (state.usedSeatsLoaded) {
                                "${state.usedSeats.size} 个已用"
                            } else {
                                "已用座位未取得"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.selectedStart == null) {
                        Button(onClick = { showTimeSheet = true }) { Text("选择时段") }
                    } else {
                        Button(onClick = onOpenOfficial) { Text("官方预约") }
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
                        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.AccessTime, contentDescription = null, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("使用时段", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "$timeLabel · 开放 ${formatMinute(detail.startMinute)}–${formatMinute(detail.endMinute)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "默认显示首个时段，可在“更改”里选择更长时段",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showTimeSheet = true }) { Text("更改") }
                }
            }
            state.detailError?.let { message ->
                item { CompactNotice("座位图未完成", message) }
            }
            item {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LegendDot(MaterialTheme.colorScheme.primary, "可选")
                    if (state.usedSeatsLoaded) {
                        LegendDot(MaterialTheme.colorScheme.error, "已用")
                    } else {
                        LegendDot(MaterialTheme.colorScheme.surfaceVariant, "已用未知")
                    }
                    LegendDot(MaterialTheme.colorScheme.tertiary, "已选")
                }
            }
            item {
                if (detail.places.isEmpty()) {
                    CompactNotice("没有座位图", "学校未返回该区域座位坐标；不能显示虚构平面图。")
                } else {
                    ChaoxingSeatMap(
                        places = detail.places,
                        usedSeats = state.usedSeats,
                        usedSeatsLoaded = state.usedSeatsLoaded,
                        selectedSeat = selectedSeat,
                        onSelect = { selectedSeat = it },
                    )
                }
            }
            item {
                Text(
                    "点座位查看编号；区域图可横向、纵向滑动。真实预约在官方页完成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showTimeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTimeSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("选择查看时段", style = MaterialTheme.typography.titleMedium)
                Text(
                    "结束时间由开放时间和时段步长决定；这里只查看所选区间的已用座位，不提交预约。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("开始时间", style = MaterialTheme.typography.labelLarge)
                val starts = chaoxingStartOptions(detail)
                if (starts.isEmpty()) {
                    Text("该区域当天没有可选开始时间。", color = MaterialTheme.colorScheme.error)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        starts.forEach { start ->
                            FilterChip(
                                selected = state.selectedStart == start,
                                onClick = { vm.selectStart(start) },
                                label = { Text(formatMinute(start)) },
                            )
                        }
                    }
                }
                Text("结束时间", style = MaterialTheme.typography.labelLarge)
                val ends = state.selectedStart?.let { chaoxingEndOptions(detail, it) }.orEmpty()
                if (state.selectedStart == null) {
                    Text("先选择开始时间。", style = MaterialTheme.typography.bodySmall)
                } else if (ends.isEmpty()) {
                    Text("该开始时间没有可用结束时间。", color = MaterialTheme.colorScheme.error)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ends.forEach { end ->
                            FilterChip(
                                selected = state.selectedEnd == end,
                                onClick = { vm.selectEnd(end) },
                                label = { Text(formatMinute(end)) },
                            )
                        }
                    }
                }
                if (state.selectedStart != null && state.selectedEnd != null) {
                    Text(
                        "已选 ${formatMinute(state.selectedStart)}–${formatMinute(state.selectedEnd)}" +
                            " · ${formatDuration(state.selectedEnd - state.selectedStart)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.detailError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showTimeSheet = false }, enabled = state.selectedStart != null) {
                        Text("完成")
                    }
                    OutlinedButton(onClick = onOpenOfficial) { Text("打开官方页") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChaoxingSeatMap(
    places: List<ChaoxingSeatPlace>,
    usedSeats: Set<String>,
    usedSeatsLoaded: Boolean,
    selectedSeat: String?,
    onSelect: (String) -> Unit,
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
                    val used = place.seatNum in usedSeats
                    val selected = place.seatNum == selectedSeat
                    val color = when {
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
                                    !usedSeatsLoaded -> "当前时段已用状态未取得"
                                    used -> "当前时段已用"
                                    else -> "当前时段可用"
                                }
                            }
                            .clickable { onSelect(place.seatNum) },
                        shape = RoundedCornerShape(5.dp),
                        color = color,
                        tonalElevation = if (selected) 3.dp else 0.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                place.seatNum,
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

internal fun chaoxingStartOptions(detail: ChaoxingSeatRoomDetail): List<Int> {
    if (detail.timeUnit <= 0) return emptyList()
    val result = mutableListOf<Int>()
    var start = detail.startMinute
    while (start < detail.endMinute) {
        result += start
        start += detail.timeUnit
    }
    return result
}

internal fun chaoxingEndOptions(detail: ChaoxingSeatRoomDetail, start: Int): List<Int> {
    if (detail.timeUnit <= 0 || start < detail.startMinute || start >= detail.endMinute) return emptyList()
    val result = mutableListOf<Int>()
    var end = start + detail.timeUnit
    while (end <= detail.endMinute) {
        result += end
        end += detail.timeUnit
    }
    return result
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
