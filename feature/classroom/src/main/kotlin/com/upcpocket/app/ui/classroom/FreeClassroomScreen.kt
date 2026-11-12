package com.upcpocket.app.ui.classroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.data.classroom.BuildingRoomGroup
import com.upcpocket.app.data.classroom.ClassroomPeriodGroup
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomCampusStore
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
import com.upcpocket.app.data.classroom.FreeClassroomRoom
import com.upcpocket.app.data.classroom.GroupSelectionState
import com.upcpocket.app.data.classroom.NoOpFreeClassroomCampusStore
import com.upcpocket.app.data.classroom.ZhuoyueWingFilter
import com.upcpocket.app.data.classroom.displayGroups
import com.upcpocket.app.data.classroom.filterZhuoyueWing
import com.upcpocket.app.data.classroom.formatPeriods
import com.upcpocket.app.data.classroom.groupSelectionState
import com.upcpocket.app.data.classroom.matches
import com.upcpocket.app.ui.components.GlassCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Native, read-only room availability. No WebView, reservation action or background polling. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FreeClassroomScreen(
    onBack: () -> Unit,
    repository: FreeClassroomRepository? = null,
    campusStore: FreeClassroomCampusStore = NoOpFreeClassroomCampusStore,
) {
    val actualRepository = remember(repository) { repository ?: com.upcpocket.app.data.classroom.PublicFreeClassroomRepository() }
    val factory = remember(actualRepository, campusStore) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FreeClassroomViewModel(actualRepository, campusStore) as T
        }
    }
    val model: FreeClassroomViewModel = viewModel(factory = factory)

    var onlyAvailable by rememberSaveable { mutableStateOf(false) }
    var wingFilter by rememberSaveable(model.campus) { mutableStateOf(ZhuoyueWingFilter.ALL) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showBuildingPicker by rememberSaveable { mutableStateOf(false) }
    var showCustomPeriods by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<RoomDetail?>(null) }

    val currentQuery = model.currentQuery()
    // 只展示完整查询键相同的缓存；筛选变了就回到“等待查询”，不把旧条件下的
    // 房间网格当成当前结果。同一键的旧结果可以保留，但必须标出读取时间/失败状态。
    val queryResult = model.result?.takeIf { it.matches(currentQuery) }
    val displayResult = queryResult?.filterZhuoyueWing(wingFilter)
    val buildings = model.result
        ?.takeIf { it.query.campus == model.campus }
        ?.buildings
        .orEmpty()
        .ifEmpty { listOf("全部") }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = model.date
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            model.chooseDate(
                                Instant.ofEpochMilli(millis)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate(),
                            )
                            showDatePicker = false
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }

    if (showBuildingPicker) {
        BuildingPickerDialog(
            buildings = buildings,
            selected = model.building,
            loading = model.loading,
            hasResult = model.result != null,
            onSelect = {
                model.chooseBuilding(it)
                showBuildingPicker = false
            },
            onQueryAll = {
                model.chooseBuilding("全部")
                showBuildingPicker = false
                model.search()
            },
            onDismiss = { showBuildingPicker = false },
        )
    }

    if (showCustomPeriods) {
        CustomPeriodDialog(
            initial = model.periods,
            onApply = {
                model.applyPeriods(it)
                showCustomPeriods = false
            },
            onDismiss = { showCustomPeriods = false },
        )
    }

    detail?.let { selected ->
        RoomDetailDialog(
            detail = selected,
            onDismiss = { detail = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("空闲教室") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "filter") {
                ClassroomFilterPanel(
                    model = model,
                    wingFilter = wingFilter,
                    onWingFilterChange = { wingFilter = it },
                    onOpenDate = { showDatePicker = true },
                    onOpenBuilding = { showBuildingPicker = true },
                    onOpenCustomPeriods = { showCustomPeriods = true },
                    onQuery = { model.search() },
                )
            }

            if (model.loading || model.restoringCampus) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            model.error?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                    QueryErrorCard(
                        message = message,
                        enabled = model.periods.isNotEmpty() && !model.loading,
                        onRetry = { model.search() },
                    )
                }
            }

            if (displayResult != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "summary") {
                    ResultSummaryCard(
                        result = displayResult,
                        wingFilter = wingFilter,
                        onlyAvailable = onlyAvailable,
                        onOnlyAvailableChange = { onlyAvailable = it },
                        loading = model.loading,
                        error = model.error != null,
                    )
                }
                if (displayResult.rooms.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty-directory") {
                        NoticeCard(
                            if (queryResult?.rooms?.isNotEmpty() == true && wingFilter != ZhuoyueWingFilter.ALL) {
                                "暂无${wingFilter.label}教室。"
                            } else {
                                "未返回教室目录，不代表全部空闲。请调整日期或教学楼。"
                            },
                        )
                    }
                } else {
                    val groups = displayResult.displayGroups(onlyAvailable)
                    val freeCount = displayResult.rooms.count { it.available }
                    if (freeCount == 0 && !onlyAvailable) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "all-occupied") {
                            NoticeCard("所选节次无空闲教室，以下为占用。")
                        }
                    }
                    if (groups.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "no-free") {
                            Text(
                                "没有空闲教室。关闭「仅看空闲」查看占用。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    groups.forEachIndexed { index, group ->
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                            key = "building-$index-${group.building}",
                        ) {
                            BuildingHeader(group)
                        }
                        items(group.rooms, key = { room -> room.code }) { room ->
                            RoomCell(
                                room = room,
                                onClick = {
                                    detail = RoomDetail(room, displayResult.query.periods)
                                },
                            )
                        }
                    }
                }
            } else if (!model.loading && !model.restoringCampus) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "prompt") {
                    Text(
                        when {
                            model.periods.isEmpty() -> "至少选择一节。"
                            model.result != null -> "条件已更改，请重新查询。"
                            else -> "选择校区、日期和节次后查询。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                Text(
                    "绿色：所选节次均空闲。仅反映学校排课；临时占用或调课以现场为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private data class RoomDetail(
    val room: FreeClassroomRoom,
    val periods: Set<Int>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClassroomFilterPanel(
    model: FreeClassroomViewModel,
    wingFilter: ZhuoyueWingFilter,
    onWingFilterChange: (ZhuoyueWingFilter) -> Unit,
    onOpenDate: () -> Unit,
    onOpenBuilding: () -> Unit,
    onOpenCustomPeriods: () -> Unit,
    onQuery: () -> Unit,
) {
    // 查询期间筛选仍可改：ViewModel 会取消在途请求并递增代次，旧响应不能写回。
    // 读取上次校区期间先禁用筛选，保证初始查询只发一次。
    val filtersEnabled = !model.restoringCampus
    val dateLabel = remember(model.date) {
        model.date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA))
    }
    val buildingLabel = if (model.building == "全部") "全部教学楼" else model.building
    val selectedText = formatPeriods(model.periods)

    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeClassroomCampus.entries.forEach { campus ->
                    FilterChip(
                        selected = model.campus == campus,
                        onClick = { model.chooseCampus(campus) },
                        enabled = filtersEnabled,
                        label = { Text(campus.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            // 日期与快捷操作各自保持固有宽度；宽度不够时整组快捷操作换到下一行，
            // 日期按钮始终显示完整的 yyyy-MM-dd，不做 ellipsis。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenDate,
                    enabled = filtersEnabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("classroom_date"),
                ) {
                    Text(dateLabel, maxLines = 1, softWrap = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.moveDate(-1) }, enabled = filtersEnabled) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天")
                    }
                    IconButton(onClick = { model.moveDate(1) }, enabled = filtersEnabled) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "后一天")
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onOpenBuilding,
                    enabled = filtersEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(buildingLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onOpenCustomPeriods, enabled = filtersEnabled) {
                    Text("自选节次")
                }
            }

            if (model.campus == FreeClassroomCampus.GUZHENKOU) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZhuoyueWingFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = wingFilter == filter,
                            onClick = { onWingFilterChange(filter) },
                            enabled = filtersEnabled,
                            label = { Text(filter.label) },
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("classroom_wing_${filter.name}"),
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ClassroomPeriodGroup.entries.forEach { group ->
                    val state = groupSelectionState(model.periods, group)
                    FilterChip(
                        selected = state == GroupSelectionState.ALL,
                        onClick = { model.choosePreset(group) },
                        enabled = filtersEnabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = {
                            Column {
                                Text(group.label, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "${group.rangeLabel} 节",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        // 全选由 chip 的选中底色表达；部分选中用短横图标提示，点击补齐整组。
                        leadingIcon = if (state == GroupSelectionState.PARTIAL) {
                            {
                                Icon(
                                    Icons.Filled.Remove,
                                    contentDescription = "部分选中",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            Text(
                if (selectedText.isEmpty()) {
                    "至少选择一节。"
                } else {
                    "第 $selectedText 节 · 共 ${model.periods.size} 节"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (selectedText.isEmpty()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Button(
                onClick = onQuery,
                enabled = !model.restoringCampus && !model.loading && model.periods.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        model.loading -> "正在查询…"
                        else -> "查询"
                    },
                )
            }
        }
    }
}

@Composable
private fun QueryErrorCard(
    message: String,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry, enabled = enabled) { Text("重试") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultSummaryCard(
    result: FreeClassroomResult,
    wingFilter: ZhuoyueWingFilter,
    onlyAvailable: Boolean,
    onOnlyAvailableChange: (Boolean) -> Unit,
    loading: Boolean,
    error: Boolean,
) {
    val freeCount = result.rooms.count { it.available }
    val total = result.rooms.size
    val status = when {
        loading -> "更新中 · 上次结果"
        error -> "更新失败 · 上次结果"
        else -> "已更新"
    }
    val statusColor = if (error) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val condition = buildString {
        append(result.query.campus.label)
        append(" · ")
        append(result.query.date)
        append(" · ")
        append(if (result.query.building == "全部") "全部教学楼" else result.query.building)
        if (result.query.campus == FreeClassroomCampus.GUZHENKOU && wingFilter != ZhuoyueWingFilter.ALL) {
            append(" · ")
            append(wingFilter.label)
        }
        append(" · 第 ")
        append(formatPeriods(result.query.periods))
        append(" 节")
    }
    val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA)
        .withZone(java.time.ZoneId.of("Asia/Shanghai"))
        .format(result.fetchedAt)

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 统计与开关都保持固有宽度：窄屏/大字号时开关整体落到下一行，
            // 统计文本不参与 weight 压缩，避免“全/部”被拆开。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    if (total == 0) "空闲 —" else "空闲 $freeCount",
                    "全部 $total",
                ).forEach { metric ->
                    Text(
                        metric,
                        modifier = Modifier.padding(end = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Row(
                    Modifier
                        .toggleable(
                            value = onlyAvailable,
                            enabled = total > 0,
                            role = Role.Switch,
                            onValueChange = onOnlyAvailableChange,
                        )
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("仅看空闲", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = onlyAvailable,
                        onCheckedChange = null,
                        enabled = total > 0,
                    )
                }
            }
            Text(
                condition,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "更新于 $time · $status",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
        }
    }
}

@Composable
private fun BuildingHeader(group: BuildingRoomGroup) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.building,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "空闲 ${group.freeCount} / 共 ${group.totalCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoomCell(
    room: FreeClassroomRoom,
    onClick: () -> Unit,
) {
    val colors = roomColors(room.available)
    val status = if (room.available) "空闲" else "占用"
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${room.name}，${room.building}，$status"
                role = Role.Button
            },
        shape = RoundedCornerShape(12.dp),
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(1.dp, colors.content.copy(alpha = 0.22f)),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                room.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Text(
            text,
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun roomColors(available: Boolean): RoomColors {
    val dark = isSystemInDarkTheme()
    return if (available) {
        if (dark) {
            RoomColors(Color(0xFF173B2B), Color(0xFFB7E4C7))
        } else {
            RoomColors(Color(0xFFE1F4E9), Color(0xFF0B5138))
        }
    } else {
        if (dark) {
            RoomColors(Color(0xFF4A2424), Color(0xFFFFDAD6))
        } else {
            RoomColors(Color(0xFFFBE4E4), Color(0xFF6B1B1B))
        }
    }
}

private data class RoomColors(val container: Color, val content: Color)

@Composable
private fun BuildingPickerDialog(
    buildings: List<String>,
    selected: String,
    loading: Boolean,
    hasResult: Boolean,
    onSelect: (String) -> Unit,
    onQueryAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择教学楼") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                when {
                    loading && buildings.size <= 1 -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LinearProgressIndicator(Modifier.width(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("教学楼加载中…")
                    }
                    buildings.size <= 1 -> Column {
                        Text(
                            if (hasResult) {
                                "没有可选教学楼，先用“全部教学楼”查看。"
                            } else {
                                "先查询“全部教学楼”，再选择。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onQueryAll) { Text("查询全部") }
                    }
                    else -> LazyColumn {
                        items(buildings) { option ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(option) }
                                    .heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected == option,
                                    onClick = { onSelect(option) },
                                )
                                Text(if (option == "全部") "全部教学楼" else option)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun CustomPeriodDialog(
    initial: Set<Int>,
    onApply: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自选节次") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text(
                    "可多选，最多 13 节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = { draft = (1..13).toSet() }) { Text("全选") }
                    TextButton(onClick = { draft = emptySet() }) { Text("清空") }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items((1..13).toList()) { period ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    draft = if (period in draft) draft - period else draft + period
                                }
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = period in draft,
                                onCheckedChange = {
                                    draft = if (period in draft) draft - period else draft + period
                                },
                            )
                            Text("第 $period 节")
                        }
                    }
                }
                if (draft.isEmpty()) {
                    Text(
                        "至少选择一节。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotEmpty(),
                onClick = { onApply(draft) },
            ) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RoomDetailDialog(
    detail: RoomDetail,
    onDismiss: () -> Unit,
) {
    val room = detail.room
    val colors = roomColors(room.available)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(room.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("教学楼 ${room.building}")
                Text("校区 ${room.campus}")
                Text(
                    room.capacity?.let { "$it 人" } ?: "容量未知",
                )
                Text("第 ${formatPeriods(detail.periods)} 节")
                Surface(
                    color = colors.container,
                    contentColor = colors.content,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        if (room.available) "空闲" else "占用",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
