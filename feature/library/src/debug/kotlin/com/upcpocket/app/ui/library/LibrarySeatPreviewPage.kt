package com.upcpocket.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingSeatPlace
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoom
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail
import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryFloor
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibraryTimeOption

@Composable
fun LibrarySeatPreviewPage(page: String) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (page) {
            "lib-seat", "lib-time" -> LibSeatPreview(showTime = page == "lib-time")
            "lib-timesearch" -> LibTimeSearchPreview(scenario = 0)
            "lib-timesearch-empty" -> LibTimeSearchPreview(scenario = 1)
            "lib-timesearch-hidden" -> LibTimeSearchPreview(scenario = 2)
            "lib-timesearch-partial" -> LibTimeSearchPreview(scenario = 3)
            "lib-timesearch-confirm" -> LibTimeSearchPreview(scenario = 4)
            "lib-timesearch-longname" -> LibTimeSearchPreview(scenario = 5)
            "lib-timesearch-hiddensheet" -> LibTimeSearchPreview(scenario = 6)
            "chaoxing-area" -> ChaoxingAreaPreview()
            "chaoxing-seat", "chaoxing-time" -> ChaoxingSeatPreview(showTime = page == "chaoxing-time")
            else -> LibAreaPreview()
        }
    }
}

private val previewFilters = LibraryFilters(
    dates = listOf("2026-11-04", "2026-11-05", "2026-11-06"),
    buildings = listOf(LibraryBuilding("1", "东馆"), LibraryBuilding("2", "总馆")),
)

private val previewRooms = listOf(
    LibraryRoom(
        roomId = "r1",
        name = "视听鉴赏区",
        floor = 1,
        floorName = "1 楼",
        totalSeats = 68,
        freeSeats = 25,
    ),
    LibraryRoom(
        roomId = "r2",
        name = "临窗学习区 A",
        floor = 1,
        floorName = "1 楼",
        totalSeats = 40,
        freeSeats = 7,
    ),
    LibraryRoom(
        roomId = "r3",
        name = "电子阅览区 B",
        floor = 2,
        floorName = "2 楼",
        totalSeats = 24,
        freeSeats = 0,
    ),
)

private fun previewLayout(): LibrarySeatLayout {
    val seats = mutableListOf<LibrarySeat>()
    for (row in 0 until 6) {
        for (col in 0 until 8) {
            val number = row * 8 + col + 1
            val status = when {
                number in listOf(3, 4, 11) -> "IN_USE"
                number in listOf(14, 15) -> "AWAY"
                number == 22 -> "FULL"
                else -> "FREE"
            }
            seats += LibrarySeat(
                id = "s$number",
                name = "A%02d".format(number),
                row = row,
                col = col,
                type = "seat",
                enabled = number != 22,
                status = status,
                localOnly = false,
            )
        }
    }
    return LibrarySeatLayout(rows = 6, cols = 8, seats = seats)
}

private fun previewPlaces(): List<ChaoxingSeatPlace> {
    val result = mutableListOf<ChaoxingSeatPlace>()
    // 与真实 getdrawseat 相同的浮点坐标/尺寸/旋转形状：48 座长排，x 约 166–2811、
    // 每排 y 间距 79、座位 75×75，rotate 会出现 630。座位号是合成值。
    for (row in 0 until 3) {
        for (col in 0 until 16) {
            val number = row * 16 + col + 1
            result += ChaoxingSeatPlace(
                seatNum = "%03d".format(number),
                x = 166f + col * 160f + (row % 2) * 3f,
                y = 131f + row * 79f,
                width = 75f,
                height = 75f,
                rotate = when {
                    row == 1 -> 630f
                    col % 2 == 0 -> 270f
                    else -> 90f
                },
            )
        }
    }
    return result
}

private val previewChaoxingDetail = ChaoxingSeatRoomDetail(
    roomId = 12814,
    name = "合成预览 · 长排座位图",
    capacity = 48,
    timeUnit = 30,
    startMinute = 8 * 60,
    endMinute = 22 * 60,
    places = previewPlaces(),
)

private val previewChaoxingRooms = listOf(
    ChaoxingSeatRoom(12814, "求是图书馆-二楼-阶梯学习区", "二楼", 30, 8, 8 * 60, 22 * 60),
    ChaoxingSeatRoom(12815, "求是图书馆-二楼-特色阅览区", "二楼", 96, 23, 8 * 60, 22 * 60),
    ChaoxingSeatRoom(12816, "求是图书馆-二楼-电子阅览区 A", "二楼", 36, 6, 8 * 60, 22 * 60),
)

@Composable
private fun PreviewBanner() {
    Text(
        "合成预览 · 仅视觉，不连接学校、无账号/真实数据",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun LibAreaPreview() {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        PreviewBanner()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("图书馆座位", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "唐岛湾 · libseat · 合成区域",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        val state = LibrarySeatViewModel.UiState(
            filters = previewFilters,
            building = previewFilters.buildings.first(),
            date = previewFilters.dates.first(),
            rooms = previewRooms,
            floors = listOf(LibraryFloor(1, "1 楼"), LibraryFloor(2, "2 楼")),
        )
        CompactFilterPanel(
            state = state,
            filters = previewFilters,
            onBuilding = {},
            onDate = {},
            onFloor = {},
            onRefresh = {},
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("可预约区域", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("3 个", style = MaterialTheme.typography.labelSmall)
        }
        previewRooms.forEach { room ->
            SeatRoomRow(room = room, onClick = {})
        }
        Text(
            "余座/总数来自学校当前查询；本页是合成预览。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }
}

private fun previewSeatState(): LibrarySeatViewModel.UiState {
    val layout = previewLayout()
    val seat = layout.seats.first { it.name == "A01" }
    return LibrarySeatViewModel.UiState(
        filters = previewFilters,
        building = previewFilters.buildings.first(),
        date = "2026-11-04",
        rooms = previewRooms,
        floors = listOf(LibraryFloor(1, "1 楼"), LibraryFloor(2, "2 楼")),
        room = previewRooms.first(),
        layout = layout,
        seat = seat,
        startTimes = listOf(LibraryTimeOption("480", "08:00")),
        endTimes = listOf(LibraryTimeOption("600", "10:00")),
        start = LibraryTimeOption("480", "08:00"),
        end = LibraryTimeOption("600", "10:00"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibSeatPreview(showTime: Boolean) {
    val state = previewSeatState()
    val seat = state.seat
    val start = state.start
    val end = state.end
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("视听鉴赏区", maxLines = 1)
                        Text("2026-11-04 · 1 楼", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回区域")
                    }
                },
                actions = { TextButton(onClick = {}) { Text("官方页") } },
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
                        Text("已选 ${seat?.name.orEmpty()}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${start?.value.orEmpty()}–${end?.value.orEmpty()} · " +
                                (durationText(start, end) ?: "时长未知"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Button(onClick = {}, enabled = state.canConfirmReservation()) { Text("确认预约") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { PreviewBanner() }
            item {
                Row(
                    Modifier.fillMaxWidth().background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(16.dp),
                    ).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("日期 2026-11-04", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "已选时段 ${start?.value.orEmpty()}–${end?.value.orEmpty()} · " +
                                (durationText(start, end) ?: "时长未知") + " · 点底部可改",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {}) { Text("改时间") }
                }
            }
            item { SeatLegend() }
            item {
                SeatMap(
                    layout = state.layout ?: previewLayout(),
                    selectedId = seat?.id,
                    onSelect = {},
                )
            }
            if (showTime) {
                item { PreviewTimeChips(title = "选择使用时间", state = state) }
            }
            item {
                Text(
                    "点可用座位选择；布局可横向、纵向滑动。本页为合成预览。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChaoxingAreaPreview() {
    val state = ChaoxingSeatUiState(
        kind = ChaoxingSeatKind.SEATENGINE,
        selectedDate = "2026-11-04",
        rooms = previewChaoxingRooms,
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        PreviewBanner()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("超星座位", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "求是 · seatengine · 合成区域",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChaoxingFilterPanel(state = state, onKind = {}, onDate = {})
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("区域", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("3 个", style = MaterialTheme.typography.labelSmall)
            }
            previewChaoxingRooms.forEach { room ->
                ChaoxingAreaRow(room = room, selectedDate = "2026-11-04", onClick = {})
            }
            Text(
                "只读展示区域、开放时间和所选时段已用座位；本页为合成预览。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChaoxingSeatPreview(showTime: Boolean) {
    val detail = previewChaoxingDetail
    var picked by remember { mutableStateOf("008") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(detail.name, maxLines = 1)
                        Text("11-04 · 求是", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回区域")
                    }
                },
                actions = { TextButton(onClick = {}) { Text("官方页") } },
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
                        Text("已选 $picked（仅查看）", fontWeight = FontWeight.SemiBold)
                        Text("08:00–10:00 · 2 小时 · 6 个已用", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = {}) { Text("官方预约") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { PreviewBanner() }
            item {
                Row(
                    Modifier.fillMaxWidth().background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(16.dp),
                    ).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("使用时段", style = MaterialTheme.typography.labelMedium)
                        Text("08:00–10:00 · 2 小时 · 开放 08:00–22:00", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {}) { Text("更改") }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreviewLegendDot(MaterialTheme.colorScheme.primary, "可选")
                    PreviewLegendDot(MaterialTheme.colorScheme.error, "已用")
                    PreviewLegendDot(MaterialTheme.colorScheme.tertiary, "已选")
                }
            }
            item {
                ChaoxingSeatMap(
                    places = detail.places,
                    usedSeats = setOf("003", "004", "011", "022"),
                    usedSeatsLoaded = true,
                    selectedSeat = picked,
                    onSelect = { picked = it },
                )
            }
            if (showTime) {
                item { PreviewChaoxingTimeChips(title = "选择查看时段") }
            }
            item {
                Text(
                    "座位位置来自学校 x/y 坐标；真实预约、选座和签到仍在官方页完成。本页为合成预览。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewChaoxingTimeChips(title: String) {
    val start = "08:00"
    val end = "10:00"
    Column(
        Modifier.fillMaxWidth().background(
            MaterialTheme.colorScheme.surfaceContainerLow,
            RoundedCornerShape(16.dp),
        ).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("开始时间", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("08:00", "09:00", "10:00").forEach { value ->
                FilterChip(selected = value == start, onClick = {}, label = { Text(value) })
            }
        }
        Text("结束时间", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("09:00", "10:00", "11:00").forEach { value ->
                FilterChip(selected = value == end, onClick = {}, label = { Text(value) })
            }
        }
        Text(
            "已选 $start–$end · 2 小时",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "这里只读查看所选区间；真实预约仍在官方页完成。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewTimeChips(title: String, state: LibrarySeatViewModel.UiState) {
    val start = state.start?.value.orEmpty()
    val end = state.end?.value.orEmpty()
    Column(
        Modifier.fillMaxWidth().background(
            MaterialTheme.colorScheme.surfaceContainerLow,
            RoundedCornerShape(16.dp),
        ).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("开始时间", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("08:00", "09:00", "10:00").forEach { value ->
                FilterChip(
                    selected = value == start,
                    onClick = {},
                    label = { Text(value) },
                )
            }
        }
        Text("结束时间", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("09:00", "10:00", "11:00").forEach { value ->
                FilterChip(
                    selected = value == end,
                    onClick = {},
                    label = { Text(value) },
                )
            }
        }
        Text(
            "已选 $start–$end" + (durationText(state.start, state.end)?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "确认时会使用同一组开始/结束选项；本页为合成预览。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewLegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .width(10.dp)
                .height(10.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ───────────────── 按时段找 · 合成预览 ─────────────────
// 全部座号/时段/数量为合成值，不连接学校、无账号、无网络、无真实业务数据。
private val timePreviewCandidates = listOf(
    LibrarySeat("t1", "001", 0, 0, "seat", true, "FREE", false),
    LibrarySeat("t2", "006", 0, 1, "seat", true, "IN_USE", false),
    LibrarySeat("t3", "012", 0, 2, "seat", true, "FREE", false),
    LibrarySeat("t4", "018", 0, 3, "seat", true, "FREE", false),
    LibrarySeat("t5", "024", 0, 4, "seat", true, "FREE", false),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibTimeSearchPreview(scenario: Int) {
    val roomName = if (scenario == 5) "三楼 A 区 · 临窗静音研讨与长时间视听鉴赏综合座位区域" else "三楼 A 区"
    var selectedId by remember { mutableStateOf(if (scenario == 4) "t3" else null) }
    val chosen = timePreviewCandidates.firstOrNull { it.id == selectedId }
    val begin = "14:00"
    val finish = "16:00"
    val hiddenCount = if (scenario == 2 || scenario == 6) 2 else 0
    val visible = when (scenario) {
        2 -> timePreviewCandidates // 全隐藏场景在下方用「可约座位已被隐藏」呈现
        else -> timePreviewCandidates
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(roomName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("唐岛湾 · 11 月 7 日", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
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
                        Text(chosen?.name?.let { "已选 $it" } ?: "请选择座位", fontWeight = FontWeight.SemiBold)
                        Text("$begin–$finish · 2 小时", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = {}, enabled = chosen != null) { Text("确认预约") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { PreviewBanner() }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = false, onClick = {}, label = { Text("按座位选") })
                    FilterChip(selected = true, onClick = {}, label = { Text("按时段找") })
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("开始时间", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(begin) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("结束时间", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(finish) }
                        }
                    }
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("查找座位") }
                }
            }
            when (scenario) {
                1 -> item { CompactNotice("这个时段暂无可约座位", "可以修改时段再找。") }
                2 -> item { CompactNotice("可约座位已被隐藏", "可在右上角查看或恢复隐藏项。") }
                else -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (scenario == 3) "已加载 ${visible.size} 个" else "共 ${visible.size} 个",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            if (hiddenCount > 0) {
                                Text("已隐藏 $hiddenCount", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(
                        if (scenario == 2) emptyList() else visible,
                        key = { it.id },
                    ) { candidate ->
                        var menu by remember { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selectedId == candidate.id) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clickable { selectedId = candidate.id }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(selected = selectedId == candidate.id, onClick = null)
                                Text(candidate.name, Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                    }
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = menu, onDismissRequest = { menu = false },
                                    ) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("隐藏此座位") },
                                            onClick = { menu = false },
                                            leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (scenario == 3) {
                        item {
                            Column {
                                Text("部分结果未加载", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = {}) { Text("重试") }
                            }
                        }
                    } else if (scenario == 0) {
                        item { TextButton(onClick = {}) { Text("加载更多") } }
                    }
                }
            }
            if (scenario == 4) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("确认预约", style = MaterialTheme.typography.titleMedium)
                            Text("$roomName · 012", style = MaterialTheme.typography.bodyMedium)
                            Text("11 月 7 日 14:00–16:00 · 2 小时", style = MaterialTheme.typography.bodyMedium)
                            Text("提交后以学校结果为准。", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {}) { Text("确认预约") }
                                TextButton(onClick = {}) { Text("取消") }
                            }
                        }
                    }
                }
            }
            if (scenario == 6) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("隐藏的座位（2）", style = MaterialTheme.typography.titleMedium)
                            Text("只在本机隐藏，不影响学校状态。", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("006", Modifier.weight(1f))
                                TextButton(onClick = {}) { Text("恢复") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("018", Modifier.weight(1f))
                                TextButton(onClick = {}) { Text("恢复") }
                            }
                            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("全部恢复") }
                        }
                    }
                }
            }
        }
    }
}
