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
import com.upcpocket.app.data.library.LibraryHiddenSeat
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.data.library.LibraryTimeSearchKey
import com.upcpocket.app.data.library.LibraryTimeSearchResult

@Composable
fun LibrarySeatPreviewPage(page: String) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            page.startsWith("cx-") -> ChaoxingProductionPreview(page)
            else -> when (page) {
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
// 本预览直接复用生产组件 LibraryTimeSearchContent / TimeSearchBottomBar 与
// 同一个 UiState 派生规则（计数、hasMore、时长、确认可用性），不复制第二份布局。
private val timePreviewCandidates = listOf(
    LibrarySeat("t1", "001", 0, 0, "seat", true, "FREE", false),
    LibrarySeat("t2", "006", 0, 1, "seat", true, "IN_USE", false),
    LibrarySeat("t3", "012", 0, 2, "seat", true, "FREE", false),
    LibrarySeat("t4", "018", 0, 3, "seat", true, "FREE", false),
    LibrarySeat("t5", "024", 0, 4, "seat", true, "FREE", false),
)

private val timePreviewKey = LibraryTimeSearchKey(
    buildingId = "1", roomId = "r1", date = "2026-11-07",
    beginMinute = 840, endMinute = 960, beginText = "14:00", endText = "16:00",
)

/**
 * 可控合成服务：用生产 UiState + 同一套派生规则驱动生产组件，真实点
 * 「查找 / 选座(官方 time 映射) / 隐藏 / 撤销 / 恢复 / 失败重试」不联网。
 * 状态变化全部经 [LibraryTimeSearchResult] 与 canConfirmTimeReservation 等生产函数。
 */
private class TimeSearchPreviewController(
    initial: LibrarySeatViewModel.UiState,
) {
    var state by mutableStateOf(initial)
        private set

    private val officialStarts = listOf(LibraryTimeOption("840", "14:00"))
    private val officialEnds = listOf(LibraryTimeOption("960", "16:00"))
    private var failNext = false

    fun search() {
        if (failNext) {
            state = state.copy(
                timeResult = LibraryTimeSearchResult(
                    key = timePreviewKey, failed = true, hasMore = true,
                    failureMessage = "查询失败",
                ),
            )
            failNext = false
            return
        }
        state = state.copy(
            timeResult = LibraryTimeSearchResult(
                key = timePreviewKey, seats = timePreviewCandidates,
                loadedPages = 1, nextPage = 2, hasMore = false, complete = true,
            ),
            error = null,
        )
    }

    /** 真实地将「失败」注入后重试：第一次失败，第二次成功。 */
    fun searchWithFailureThenRetry() {
        failNext = true
        search()
        // 模拟用户看到失败后点「重试」：真正重新发出并走成功分支。
        search()
    }

    fun select(seat: LibrarySeat) {
        // 官方 startTimes/endTimes 映射：按真实 HH:mm 匹配并用原始 id。
        if (matchOfficialTime(officialStarts, "14:00") == null) return
        if (matchOfficialTime(officialEnds, "16:00") == null) return
        state = state.copy(
            timeSelectedSeat = seat,
            timeStartTimes = officialStarts,
            timeEndTimes = officialEnds,
            timeStart = officialStarts.first(),
            timeStop = officialEnds.first(),
        )
    }

    fun hide(seat: LibrarySeat) {
        val entry = LibraryHiddenSeat("libseat", "1", "r1", seat.id, seat.name)
        state = state.copy(
            hiddenSeatIds = state.hiddenSeatIds + seat.id,
            hiddenEntries = state.hiddenEntries + entry,
            undoHidden = entry,
            timeSelectedSeat = state.timeSelectedSeat?.takeIf { it.id != seat.id },
            seat = state.seat?.takeIf { it.id != seat.id },
            showConfirm = false,
        )
    }

    fun undo() {
        val e = state.undoHidden ?: return
        state = state.copy(
            hiddenSeatIds = state.hiddenSeatIds - e.seatId,
            hiddenEntries = state.hiddenEntries.filter { it.seatId != e.seatId },
            undoHidden = null,
        )
    }

    fun restore(seatId: String) {
        state = state.copy(
            hiddenSeatIds = state.hiddenSeatIds - seatId,
            hiddenEntries = state.hiddenEntries.filter { it.seatId != seatId },
        )
    }

    fun restoreAll() {
        state = state.copy(hiddenSeatIds = emptySet(), hiddenEntries = emptyList())
    }

    fun retry() {
        // 失败/部分失败后真正重新查当前条件。
        search()
    }

    fun loadMore() {
        val r = state.timeResult ?: return
        if (!r.hasMore) return
        state = state.copy(
            timeResult = r.copy(
                hasMore = false, complete = true, partialFailure = false,
                seats = r.seats + listOf(
                    LibrarySeat("t6", "030", 0, 5, "seat", true, "FREE", false),
                ),
                loadedPages = r.loadedPages + 1,
            ),
        )
    }
}

/** 用真实 UiState + 生产派生规则构造每个合成场景，不硬编码计数/时长/按钮状态。 */
private fun timePreviewState(scenario: Int): LibrarySeatViewModel.UiState {
    val base = LibrarySeatViewModel.UiState(
        filters = previewFilters,
        building = previewFilters.buildings.first(),
        date = "2026-11-07",
        room = LibraryRoom("r1",
            if (scenario == 5) "三楼 A 区 · 临窗静音研讨与长时间视听鉴赏综合座位区域" else "三楼 A 区",
            3, "3 楼"),
        seatMode = RoomSeatMode.TIME,
        timeBegin = "14:00",
        timeFinish = "16:00",
    )
    val officialTimes = listOf(LibraryTimeOption("840", "14:00"))
    val officialEnds = listOf(LibraryTimeOption("960", "16:00"))
    val selected = if (scenario == 4) timePreviewCandidates.first { it.id == "t3" } else null
    return when (scenario) {
        // 0：完整读完 5 个、无更多 → 生产规则写「共 5 个」，不显示加载更多。
        0 -> base.copy(
            timeResult = LibraryTimeSearchResult(
                key = timePreviewKey, seats = timePreviewCandidates,
                loadedPages = 1, nextPage = 2, hasMore = false, complete = true,
            ),
        )
        // 1：完整空结果 → 「这个时段暂无可约座位」。
        1 -> base.copy(
            timeResult = LibraryTimeSearchResult(
                key = timePreviewKey, seats = emptyList(),
                loadedPages = 1, nextPage = 2, hasMore = false, complete = true,
            ),
        )
        // 2：候选中 2 个被隐藏 → 「已加载中隐藏 2」，可见 3 个。
        2 -> base.copy(
            timeResult = LibraryTimeSearchResult(
                key = timePreviewKey, seats = timePreviewCandidates,
                loadedPages = 1, nextPage = 2, hasMore = false, complete = true,
            ),
            hiddenSeatIds = setOf("t2", "t4"),
            hiddenEntries = listOf(
                com.upcpocket.app.data.library.LibraryHiddenSeat("libseat", "1", "r1", "t2", "006"),
                com.upcpocket.app.data.library.LibraryHiddenSeat("libseat", "1", "r1", "t4", "018"),
            ),
        )
        // 3：下一页失败 → 已加载 2 个 + 「部分结果未加载」+ 可重试，仍保留 hasMore。
        // 只用 2 个候选让页脚在 1.0/1.5 字体下都可见，便于视觉核对生产规则。
        3 -> base.copy(
            timeResult = LibraryTimeSearchResult(
                key = timePreviewKey, seats = timePreviewCandidates.take(2),
                loadedPages = 1, nextPage = 2, hasMore = true, complete = false,
                partialFailure = true, failureMessage = "部分结果未加载",
            ),
        )
        // 4：选中候选并带官方时间映射 → 生产底部摘要 14:00–16:00 · 2 小时，确认可用。
        4 -> base.copy(
            timeResult = LibraryTimeSearchResult(
                key = timePreviewKey, seats = timePreviewCandidates,
                loadedPages = 1, nextPage = 2, hasMore = false, complete = true,
            ),
            timeSelectedSeat = selected,
            timeStartTimes = officialTimes,
            timeEndTimes = officialEnds,
            timeStart = officialTimes.first(),
            timeStop = officialEnds.first(),
        )
        // 5：长房间名 + 1.5 字体由 Activity 控制，状态同 0。
        5 -> base.copy(
            timeResult = LibraryTimeSearchResult(
                key = timePreviewKey, seats = timePreviewCandidates,
                loadedPages = 1, nextPage = 2, hasMore = false, complete = true,
            ),
        )
        // 6：隐藏管理面板空态数据（面板由生产 HiddenSeatsSheet 同规则呈现）。
        6 -> base.copy(
            timeResult = LibraryTimeSearchResult(
                key = timePreviewKey, seats = timePreviewCandidates,
                loadedPages = 1, nextPage = 2, hasMore = false, complete = true,
            ),
            hiddenEntries = listOf(
                com.upcpocket.app.data.library.LibraryHiddenSeat("libseat", "1", "r1", "t2", "006"),
                com.upcpocket.app.data.library.LibraryHiddenSeat("libseat", "1", "r1", "t4", "018"),
            ),
            hiddenSeatIds = setOf("t2", "t4"),
        )
        else -> base
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibTimeSearchPreview(scenario: Int) {
    // 用可控合成服务驱动生产组件：真实点查找/选座映射/隐藏/撤销/恢复/失败重试，
    // 但全部数据为合成、不联网、无账号、无真实业务记录。
    val controller = remember(scenario) { TimeSearchPreviewController(timePreviewState(scenario)) }
    val state = controller.state
    val roomName = state.room?.name.orEmpty()
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
                    Text("隐藏的座位（${state.hiddenEntries.size}）", style = MaterialTheme.typography.labelSmall)
                },
            )
        },
        bottomBar = {
            TimeSearchBottomBar(state = state, onConfirm = {})
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PreviewBanner()
            // 合成交互条：显式点这些按钮会经真实 UiState 派生规则更新生产组件。
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = controller::search) { Text("合成查找") }
                TextButton(onClick = controller::searchWithFailureThenRetry) { Text("失败→重试") }
                TextButton(onClick = controller::undo) { Text("撤销") }
                TextButton(onClick = controller::restoreAll) { Text("全部恢复") }
            }
            Box(Modifier.weight(1f)) {
                LibraryTimeSearchContent(
                    state = state,
                    onMode = {},
                    onPickBegin = {},
                    onPickFinish = {},
                    onSearch = controller::search,
                    onSelect = controller::select,
                    onHide = controller::hide,
                    onRetry = controller::retry,
                    onLoadMore = controller::loadMore,
                )
            }
            if (scenario == 4) {
                Text(
                    "确认对话框（生产派生：${state.date} ${state.timeStart?.value}–${state.timeStop?.value} · " +
                        (durationText(state.timeStart, state.timeStop) ?: "时长未知") + "）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (scenario == 6) {
                Text(
                    "隐藏的座位（${state.hiddenEntries.size}）：逐个/全部恢复面板由生产 HiddenSeatsSheet 同规则呈现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** All synthetic inputs; the screen, map, controls, rows, sheets and dialog are production composables. */
@Composable
private fun ChaoxingProductionPreview(page: String) {
    val kind = if (page.contains("engine")) ChaoxingSeatKind.SEATENGINE else ChaoxingSeatKind.SEAT
    val places = (1..18).map { n -> ChaoxingSeatPlace("%03d".format(n),
        ((n-1)%6)*65.5f, ((n-1)/6)*80.5f, 45f, 45f, if (n%3==0) 270f else 0f) }
    val detail = ChaoxingSeatRoomDetail(1,
        if (page.contains("long")) "合成阅览区 · 三楼东侧靠窗长房间名称与安静学习空间" else "合成阅览区 A",
        18,30,480,1320,places, seatRulesKnown=true)
    var state by remember(page) { mutableStateOf(ChaoxingSeatUiState(kind=kind,
        selectedDate="2099-11-09",detail=detail,selectedStart=840,selectedEnd=960,
        seatMode=if(page.contains("map")) RoomSeatMode.SEAT else RoomSeatMode.TIME,
        usedSeats=if(page.contains("empty")) places.map{it.seatNum}.toSet() else setOf("002","005"),usedSeatsLoaded=!page.contains("failed"),
        detailError=if(page.contains("failed")) "查询失败，请重试。" else null,
        hiddenLoaded=true,selectedSeat=if(page.contains("confirm")) "006" else null,
        showHiddenSheet=page.contains("hidden"),
        hiddenEntries=if(page.contains("hidden") || page.contains("map")) listOf(
            LibraryHiddenSeat(kind.hiddenSystem,kind.hiddenBuilding,"1","003","003"),
            LibraryHiddenSeat(kind.hiddenSystem,kind.hiddenBuilding,"1","009","009")) else emptyList())) }
    fun restore(id:String) { state=state.copy(hiddenEntries=state.hiddenEntries.filterNot {it.seatId==id},undoHidden=null) }
    ChaoxingSeatDetailContent(state, {}, {},
        { state=state.copy(seatMode=it,selectedSeat=null,usedSeatsLoaded=false,queryGeneration=state.queryGeneration+1) },
        { state=state.copy(selectedStart=it, selectedEnd=it+30, selectedSeat=null,usedSeatsLoaded=false,queryGeneration=state.queryGeneration+1) },
        { state=state.copy(selectedEnd=it,selectedSeat=null,usedSeatsLoaded=false,queryGeneration=state.queryGeneration+1) },
        { state=state.copy(usedSeatsLoaded=true,detailError=null) },
        { state=state.copy(selectedSeat=it) },
        { id -> val e=LibraryHiddenSeat(kind.hiddenSystem,kind.hiddenBuilding,"1",id,id)
            state=state.copy(hiddenEntries=state.hiddenEntries+e,selectedSeat=state.selectedSeat?.takeUnless{it==id},undoHidden=e) },
        { state=state.copy(showHiddenSheet=true) }, {state=state.copy(showHiddenSheet=false)}, ::restore,
        {state=state.copy(hiddenEntries=emptyList())}, {}, {state=state.copy(undoHidden=null)}, {})
}
