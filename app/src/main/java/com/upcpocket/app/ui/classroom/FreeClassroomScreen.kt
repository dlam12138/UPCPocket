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
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
import com.upcpocket.app.data.classroom.FreeClassroomRoom
import com.upcpocket.app.data.classroom.GroupSelectionState
import com.upcpocket.app.data.classroom.displayGroups
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
) {
    val actualRepository = remember(repository) { repository ?: com.upcpocket.app.data.classroom.PublicFreeClassroomRepository() }
    val factory = remember(actualRepository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FreeClassroomViewModel(actualRepository) as T
        }
    }
    val model: FreeClassroomViewModel = viewModel(factory = factory)

    var onlyAvailable by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showBuildingPicker by rememberSaveable { mutableStateOf(false) }
    var showCustomPeriods by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<RoomDetail?>(null) }

    val currentQuery = model.currentQuery()
    val currentResult = model.result?.takeIf { it.matches(currentQuery) }
    val staleResult = model.result?.takeIf { !it.matches(currentQuery) }
    val displayResult = currentResult
        ?: staleResult?.takeIf { model.error != null && !model.loading }
    val isStale = displayResult != null && !displayResult.matches(currentQuery)
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
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "filter") {
                ClassroomFilterPanel(
                    model = model,
                    onOpenDate = { showDatePicker = true },
                    onOpenBuilding = { showBuildingPicker = true },
                    onOpenCustomPeriods = { showCustomPeriods = true },
                    onQuery = { model.search() },
                )
            }

            if (model.loading) {
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
                        onlyAvailable = onlyAvailable,
                        onOnlyAvailableChange = { onlyAvailable = it },
                        isStale = isStale,
                        loading = model.loading,
                        error = model.error != null,
                    )
                }
                if (displayResult.rooms.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty-directory") {
                        NoticeCard(
                            "学校未返回该条件下的教室目录，不能判断为全部空闲。请调整日期或教学楼后重试。",
                        )
                    }
                } else {
                    val groups = displayResult.displayGroups(onlyAvailable)
                    val freeCount = displayResult.rooms.count { !it.occupied }
                    if (freeCount == 0 && !onlyAvailable) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "all-occupied") {
                            NoticeCard("所选节次下没有空闲教室，以下为占用教室。")
                        }
                    }
                    if (groups.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "no-free") {
                            Text(
                                "当前筛选下没有空闲教室。关闭「仅看空闲」可查看占用教室。",
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
            } else if (!model.loading) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "prompt") {
                    Text(
                        when {
                            model.periods.isEmpty() -> "请至少选择一节，再查询空闲教室。"
                            model.result != null -> "筛选条件已变更，点击「查询空闲教室」查看新结果。"
                            else -> "选择校区、日期和节次后，点击「查询空闲教室」。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                Text(
                    "数据来源：学校空闲教室公开页面，只读查询，不提供预约。",
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
    onOpenDate: () -> Unit,
    onOpenBuilding: () -> Unit,
    onOpenCustomPeriods: () -> Unit,
    onQuery: () -> Unit,
) {
    val enabled = !model.loading
    val dateLabel = remember(model.date) {
        model.date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.CHINA))
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
                        enabled = enabled,
                        label = { Text(campus.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onOpenDate,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(dateLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { model.moveDate(-1) }, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天")
                }
                TextButton(onClick = { model.chooseDate(LocalDate.now()) }, enabled = enabled) {
                    Text("今天")
                }
                IconButton(onClick = { model.moveDate(1) }, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "后一天")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onOpenBuilding,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(buildingLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onOpenCustomPeriods, enabled = enabled) {
                    Text("自选节次")
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
                        onClick = { model.toggleGroup(group) },
                        enabled = enabled,
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
                    "未选择节次；至少选择一节才能查询。"
                } else {
                    "已选节次：第 $selectedText 节（共 ${model.periods.size} 节）"
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
                enabled = enabled && model.periods.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        model.loading -> "正在查询…"
                        model.error != null -> "重试查询"
                        else -> "查询空闲教室"
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

@Composable
private fun ResultSummaryCard(
    result: FreeClassroomResult,
    onlyAvailable: Boolean,
    onOnlyAvailableChange: (Boolean) -> Unit,
    isStale: Boolean,
    loading: Boolean,
    error: Boolean,
) {
    val freeCount = result.rooms.count { !it.occupied }
    val total = result.rooms.size
    val status = when {
        isStale -> "本次查询失败，以下为上次成功结果（旧条件）"
        loading -> "正在更新，以下为上次成功结果"
        error -> "本次未能更新，以下为上次成功结果（同一条件）"
        else -> "查询成功"
    }
    val statusColor = if (isStale || error) {
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
        append(" · 第 ")
        append(formatPeriods(result.query.periods))
        append(" 节")
    }
    val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA)
        .withZone(java.time.ZoneId.systemDefault())
        .format(result.fetchedAt)

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (total == 0) "空闲 — / 全部 0" else "空闲 $freeCount / 全部 $total",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(8.dp))
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
    val colors = roomColors(room.occupied)
    val status = if (room.occupied) "占用" else "空闲"
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
private fun roomColors(occupied: Boolean): RoomColors {
    val dark = isSystemInDarkTheme()
    return if (occupied) {
        if (dark) {
            RoomColors(Color(0xFF4A2424), Color(0xFFFFDAD6))
        } else {
            RoomColors(Color(0xFFFBE4E4), Color(0xFF6B1B1B))
        }
    } else {
        if (dark) {
            RoomColors(Color(0xFF173B2B), Color(0xFFB7E4C7))
        } else {
            RoomColors(Color(0xFFE1F4E9), Color(0xFF0B5138))
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
                        Text("教学楼列表加载中…")
                    }
                    buildings.size <= 1 -> Column {
                        Text(
                            if (hasResult) {
                                "学校没有返回可选教学楼，可先用“全部教学楼”查看。"
                            } else {
                                "教学楼列表随查询结果返回。先用“全部教学楼”查询一次。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onQueryAll) { Text("查询全部教学楼") }
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
                    "学校公开页使用 1–13 节，可多选。",
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
                        "至少选择一节才能应用。",
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
    val colors = roomColors(room.occupied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(room.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("教学楼：${room.building}")
                Text("校区：${room.campus}")
                Text(
                    room.capacity?.let { "容量：$it 人" } ?: "容量：未提供",
                )
                Text("所选节次：第 ${formatPeriods(detail.periods)} 节")
                Surface(
                    color = colors.container,
                    contentColor = colors.content,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        if (room.occupied) "占用" else "空闲",
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
