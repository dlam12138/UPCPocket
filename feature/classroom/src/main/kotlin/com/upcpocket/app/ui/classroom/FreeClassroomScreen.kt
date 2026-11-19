package com.upcpocket.app.ui.classroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
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
import com.upcpocket.app.data.classroom.AllFreeClassroomPreferredCampus
import com.upcpocket.app.data.classroom.BuildingRoomGroup
import com.upcpocket.app.data.classroom.ClassroomPeriodGroup
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomCampusStore
import com.upcpocket.app.data.classroom.FreeClassroomPreferredCampus
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
import com.upcpocket.app.ui.components.RatioRingBadge
import com.upcpocket.app.ui.components.RatioSegment
import com.upcpocket.app.ui.components.SegmentedRatioBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PeriodSetSaver = listSaver<Set<Int>, Int>(
    save = { value -> value.sorted() },
    restore = { value -> value.toSet() },
)

/** Native, read-only room availability. No WebView, reservation action or background polling. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FreeClassroomScreen(
    onBack: () -> Unit,
    repository: FreeClassroomRepository? = null,
    campusStore: FreeClassroomCampusStore = NoOpFreeClassroomCampusStore,
    preferredCampus: FreeClassroomPreferredCampus = AllFreeClassroomPreferredCampus,
) {
    val actualRepository = remember(repository) { repository ?: com.upcpocket.app.data.classroom.PublicFreeClassroomRepository() }
    val factory = remember(actualRepository, campusStore, preferredCampus) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FreeClassroomViewModel(actualRepository, campusStore, preferredCampus) as T
        }
    }
    val model: FreeClassroomViewModel = viewModel(factory = factory)

    var onlyAvailable by rememberSaveable { mutableStateOf(false) }
    var wingFilter by rememberSaveable(model.campus) { mutableStateOf(ZhuoyueWingFilter.ALL) }
    var draftWingFilter by rememberSaveable(model.campus) { mutableStateOf(ZhuoyueWingFilter.ALL) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var showBuildingPicker by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<RoomDetail?>(null) }
    var draftBuilding by rememberSaveable { mutableStateOf("全部") }
    var draftPeriods by rememberSaveable(stateSaver = PeriodSetSaver) {
        mutableStateOf(emptySet())
    }
    var draftCustomPeriodsExplicitlySelected by rememberSaveable(model.campus) { mutableStateOf(false) }

    val currentQuery = model.currentQuery()
    // 只展示完整查询键相同的结果；条件改变时旧房间不能冒充当前结果。
    val queryResult = model.result?.takeIf { it.matches(currentQuery) }
    val displayResult = queryResult?.filterZhuoyueWing(wingFilter)
    val buildings = model.result
        ?.takeIf { it.query.campus == model.campus }
        ?.buildings
        .orEmpty()
        .ifEmpty { listOf("全部") }
    val activeDetail = detail?.takeIf { selected ->
        currentQuery != null && selected.query == currentQuery
    }?.let { selected ->
        displayResult?.rooms
            ?.firstOrNull { room -> room.code == selected.room.code && (!onlyAvailable || room.available) }
            ?.let { latestRoom -> selected.copy(room = latestRoom) }
    }

    LaunchedEffect(showFilterSheet, model.campus, model.building, model.periods, wingFilter) {
        if (showFilterSheet) {
            draftBuilding = model.building
            draftPeriods = model.periods
            draftWingFilter = wingFilter
            draftCustomPeriodsExplicitlySelected = false
        }
    }
    LaunchedEffect(activeDetail, detail) {
        if (detail != null && activeDetail == null) detail = null
    }

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
                com.upcpocket.app.ui.components.CampusTextButton(
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
                com.upcpocket.app.ui.components.CampusTextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }

    if (showBuildingPicker) {
        BuildingPickerDialog(
            buildings = buildings,
            selected = draftBuilding,
            loading = model.loading,
            hasResult = model.result?.query?.campus == model.campus,
            canQueryAll = draftPeriods.isNotEmpty() && !model.restoringCampus && !model.loading,
            onSelect = {
                draftBuilding = it
                showBuildingPicker = false
            },
            onQueryAll = {
                model.applyFilters(
                    buildingValue = "全部",
                    periodValues = draftPeriods,
                    customPeriodsExplicitlySelected = draftCustomPeriodsExplicitlySelected,
                )
                wingFilter = draftWingFilter
                showBuildingPicker = false
                showFilterSheet = false
                model.search()
            },
            onDismiss = { showBuildingPicker = false },
        )
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            ClassroomFilterSheet(
                building = draftBuilding,
                periods = draftPeriods,
                campus = model.campus,
                wingFilter = draftWingFilter,
                onOpenBuilding = { showBuildingPicker = true },
                onPeriodsChange = {
                    draftPeriods = it
                    draftCustomPeriodsExplicitlySelected = true
                },
                onWingFilterChange = { draftWingFilter = it },
                onCancel = { showFilterSheet = false },
                onApply = {
                    model.applyFilters(
                        buildingValue = draftBuilding,
                        periodValues = draftPeriods,
                        customPeriodsExplicitlySelected = draftCustomPeriodsExplicitlySelected,
                    )
                    wingFilter = draftWingFilter
                    showFilterSheet = false
                },
            )
        }
    }

    activeDetail?.let { selected ->
        RoomDetailDialog(
            detail = selected,
            onDismiss = { detail = null },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
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
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "filter", contentType = "filter") {
                ClassroomFilterPanel(
                    model = model,
                    wingFilter = wingFilter,
                    onOpenDate = { showDatePicker = true },
                    onOpenFilters = {
                        showFilterSheet = true
                        draftBuilding = model.building
                        draftPeriods = model.periods
                        draftWingFilter = wingFilter
                        draftCustomPeriodsExplicitlySelected = false
                    },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "query-actions", contentType = "status") {
                ClassroomQueryActions(
                    model = model,
                    hasCurrentResult = queryResult != null,
                    onQuery = { model.search() },
                )
            }

            if (displayResult != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "summary", contentType = "summary") {
                    ResultSummaryCard(
                        result = displayResult,
                        onlyAvailable = onlyAvailable,
                        onOnlyAvailableChange = { onlyAvailable = it },
                        loading = model.loading,
                        error = model.error != null,
                    )
                }
                if (displayResult.rooms.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty-directory", contentType = "notice") {
                        NoticeCard(
                            if (queryResult?.rooms?.isNotEmpty() == true && wingFilter != ZhuoyueWingFilter.ALL) {
                                "没有符合分类的教室。"
                            } else {
                                "学校未返回教室目录，无法判断空闲情况。"
                            },
                        )
                    }
                } else {
                    val groups = displayResult.displayGroups(onlyAvailable)
                    val freeCount = displayResult.rooms.count { it.available }
                    if (freeCount == 0 && !onlyAvailable) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "all-occupied", contentType = "notice") {
                            NoticeCard("所选节次无空闲，以下房间均为占用。")
                        }
                    }
                    if (groups.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "no-free", contentType = "notice") {
                            Text(
                                "没有空闲教室。关闭“仅看空闲”查看占用。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    groups.forEach { group ->
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                            key = "building-${group.building}",
                            contentType = "building",
                        ) {
                            BuildingHeader(group)
                        }
                        items(
                            items = group.rooms,
                            key = { room -> room.code },
                            contentType = { "room" },
                        ) { room ->
                            RoomCell(
                                room = room,
                                onClick = {
                                    detail = RoomDetail(room, displayResult.query)
                                },
                            )
                        }
                    }
                }
            } else if (!model.loading && !model.restoringCampus && model.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "prompt", contentType = "notice") {
                    Text(
                        when {
                            model.periods.isEmpty() -> "至少选择一节。"
                            model.result != null -> "条件已更改，请查询。"
                            else -> "等待查询。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class RoomDetail(
    val room: FreeClassroomRoom,
    val query: com.upcpocket.app.data.classroom.FreeClassroomQuery,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClassroomFilterPanel(
    model: FreeClassroomViewModel,
    wingFilter: ZhuoyueWingFilter,
    onOpenDate: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    val filtersEnabled = !model.restoringCampus
    val dateLabel = remember(model.date) {
        model.date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA))
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("校区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeClassroomCampus.entries.forEach { campus ->
                    com.upcpocket.app.ui.components.CampusFilterChip(
                        selected = model.campus == campus,
                        onClick = { model.chooseCampus(campus) },
                        enabled = filtersEnabled,
                        label = { Text(campus.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            Text("日期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.4f
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        com.upcpocket.app.ui.components.CampusOutlinedButton(
                            onClick = onOpenDate,
                            enabled = filtersEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("classroom_date"),
                        ) {
                            Text(dateLabel)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            IconButton(
                                onClick = { model.moveDate(-1) },
                                enabled = filtersEnabled,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天")
                            }
                            IconButton(
                                onClick = { model.moveDate(1) },
                                enabled = filtersEnabled,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "后一天")
                            }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        com.upcpocket.app.ui.components.CampusOutlinedButton(
                            onClick = onOpenDate,
                            enabled = filtersEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("classroom_date"),
                        ) {
                            Text(dateLabel, maxLines = 1, softWrap = false)
                        }
                        IconButton(
                            onClick = { model.moveDate(-1) },
                            enabled = filtersEnabled,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "前一天")
                        }
                        IconButton(
                            onClick = { model.moveDate(1) },
                            enabled = filtersEnabled,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "后一天")
                        }
                    }
                }
            }

            Text("快捷时段", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ClassroomPeriodGroup.entries.forEach { group ->
                    val state = groupSelectionState(model.periods, group)
                    com.upcpocket.app.ui.components.CampusFilterChip(
                        selected = state == GroupSelectionState.ALL,
                        onClick = { model.choosePreset(group) },
                        enabled = filtersEnabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = {
                            Column {
                                Text(group.label, style = MaterialTheme.typography.labelLarge)
                                Text(group.rangeLabel, style = MaterialTheme.typography.labelSmall)
                            }
                        },
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

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前条件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conditionSummary(model, wingFilter),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    com.upcpocket.app.ui.components.CampusTextButton(onClick = onOpenFilters, enabled = filtersEnabled) { Text("筛选") }
                }
            }
        }
    }
}

@Composable
private fun ClassroomQueryActions(
    model: FreeClassroomViewModel,
    hasCurrentResult: Boolean,
    onQuery: () -> Unit,
) {
    val status = when {
        model.restoringCampus -> "正在恢复校区"
        model.loading && hasCurrentResult -> "更新中 · 上次结果仍可查看"
        model.loading -> "正在查询"
        model.error != null && hasCurrentResult -> "更新失败 · 已保留上次结果"
        model.error != null -> "查询失败"
        hasCurrentResult -> "结果已就绪"
        model.result != null -> "条件已更改，请查询"
        else -> "正在准备查询"
    }
    GlassCard(
        Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            model.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            com.upcpocket.app.ui.components.CampusButton(
                onClick = onQuery,
                enabled = !model.restoringCampus && !model.loading && model.periods.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        model.loading -> "查询中…"
                        model.error != null -> "重试"
                        hasCurrentResult -> "刷新"
                        else -> "查询"
                    },
                )
            }
        }
    }
}

private fun conditionSummary(
    model: FreeClassroomViewModel,
    wingFilter: ZhuoyueWingFilter,
): String = buildString {
    append(model.campus.label)
    append(" · ")
    append(model.date.format(DateTimeFormatter.ISO_LOCAL_DATE))
    append(" · ")
    append(if (model.building == "全部") "全部教学楼" else model.building)
    if (model.campus == FreeClassroomCampus.GUZHENKOU && wingFilter != ZhuoyueWingFilter.ALL) {
        append(" · ")
        append(wingFilter.label)
    }
    append(" · ")
    if (model.periods.isEmpty()) {
        append("未选节次")
    } else {
        append("第 ")
        append(formatPeriods(model.periods))
        append(" 节")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClassroomFilterSheet(
    building: String,
    periods: Set<Int>,
    campus: FreeClassroomCampus,
    wingFilter: ZhuoyueWingFilter,
    onOpenBuilding: () -> Unit,
    onPeriodsChange: (Set<Int>) -> Unit,
    onWingFilterChange: (ZhuoyueWingFilter) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("详细筛选", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("教学楼", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.upcpocket.app.ui.components.CampusOutlinedButton(
                onClick = onOpenBuilding,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("classroom_building_picker"),
            ) {
                Text(if (building == "全部") "全部教学楼" else building, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (campus == FreeClassroomCampus.GUZHENKOU) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("卓越楼栋", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZhuoyueWingFilter.entries.forEach { filter ->
                        com.upcpocket.app.ui.components.CampusFilterChip(
                            selected = wingFilter == filter,
                            onClick = { onWingFilterChange(filter) },
                            label = { Text(filter.label) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("classroom_wing_${filter.name}"),
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("自选节次", style = MaterialTheme.typography.labelMedium)
            Text("第 1–13 节", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                (1..13).forEach { period ->
                    com.upcpocket.app.ui.components.CampusFilterChip(
                        selected = period in periods,
                        onClick = {
                            onPeriodsChange(if (period in periods) periods - period else periods + period)
                        },
                        label = { Text(period.toString()) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("classroom_period_$period"),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.upcpocket.app.ui.components.CampusTextButton(onClick = { onPeriodsChange((1..13).toSet()) }) { Text("全选") }
                com.upcpocket.app.ui.components.CampusTextButton(onClick = { onPeriodsChange(emptySet()) }) { Text("清空") }
            }
            Text(
                if (periods.isEmpty()) "至少选择一节。" else "第 ${formatPeriods(periods)} 节 · 共 ${periods.size} 节",
                style = MaterialTheme.typography.labelMedium,
                color = if (periods.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.upcpocket.app.ui.components.CampusOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("取消")
            }
            com.upcpocket.app.ui.components.CampusButton(
                onClick = onApply,
                enabled = periods.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("应用") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultSummaryCard(
    result: FreeClassroomResult,
    onlyAvailable: Boolean,
    onOnlyAvailableChange: (Boolean) -> Unit,
    loading: Boolean,
    error: Boolean,
) {
    val freeCount = result.rooms.count { it.available }
    val total = result.rooms.size
    val status = when {
        loading -> "更新中 · 保留上次结果"
        error -> "更新失败 · 保留上次结果"
        else -> "已更新"
    }
    val statusColor = if (error) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA)
        .withZone(java.time.ZoneId.of("Asia/Shanghai"))
        .format(result.fetchedAt)

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val freeRatio = if (total > 0) freeCount.toFloat() / total.toFloat() else 0f
                val pct = (freeRatio * 100f).toInt().coerceIn(0, 100)
                RatioRingBadge(
                    fraction = freeRatio,
                    centerText = "$pct%",
                    size = 48.dp,
                    strokeWidth = 5.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            "空闲 $freeCount",
                            "共 $total 间",
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
                    }
                    Text(
                        "读取于 $time · $status",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
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
                    com.upcpocket.app.ui.components.CampusSwitch(
                        checked = onlyAvailable,
                        onCheckedChange = null,
                        enabled = total > 0,
                    )
                }
            }
            if (total > 0) {
                val occupied = (total - freeCount).coerceAtLeast(0)
                SegmentedRatioBar(
                    segments = listOf(
                        RatioSegment(
                            label = "空闲",
                            value = freeCount.toFloat(),
                            color = MaterialTheme.colorScheme.primary,
                            valueText = "$freeCount 间",
                        ),
                        RatioSegment(
                            label = "占用",
                            value = occupied.toFloat(),
                            color = MaterialTheme.colorScheme.outlineVariant,
                            valueText = "$occupied 间",
                        ),
                    ),
                    barHeight = 6.dp,
                )
            }
        }
    }
}

@Composable
private fun BuildingHeader(group: BuildingRoomGroup) {
    val freeRatio = if (group.totalCount > 0) {
        group.freeCount.toFloat() / group.totalCount.toFloat()
    } else 0f
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
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
        SegmentedRatioBar(
            segments = listOf(
                RatioSegment("空闲", group.freeCount.toFloat(), MaterialTheme.colorScheme.primary),
                RatioSegment("占用", (group.totalCount - group.freeCount).coerceAtLeast(0).toFloat(), MaterialTheme.colorScheme.surfaceVariant),
            ),
            showLegend = false,
            barHeight = 4.dp,
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
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
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
                color = colors.status,
            )
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    GlassCard(Modifier.fillMaxWidth()) {
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
            RoomColors(Color(0xFF203B2D), Color(0xFFE2F2E4), Color(0xFFB2D8B9))
        } else {
            RoomColors(Color(0xFFE8F3EA), Color(0xFF1D2B20), Color(0xFF356B41))
        }
    } else {
        if (dark) {
            RoomColors(Color(0xFF303236), Color(0xFFE5E1E6), Color(0xFFCBC7CC))
        } else {
            RoomColors(Color(0xFFF0EFF1), Color(0xFF242326), Color(0xFF625F63))
        }
    }
}

private data class RoomColors(val container: Color, val content: Color, val status: Color)

@Composable
private fun BuildingPickerDialog(
    buildings: List<String>,
    selected: String,
    loading: Boolean,
    hasResult: Boolean,
    canQueryAll: Boolean,
    onSelect: (String) -> Unit,
    onQueryAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.upcpocket.app.ui.components.CampusAlertDialog(
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
                    }
                    else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(
                            items = buildings,
                            key = { it },
                            contentType = { "building" },
                        ) { option ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(option) }
                                    .heightIn(min = 48.dp)
                                    .testTag("classroom_building_option"),
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
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = onQueryAll,
                    enabled = canQueryAll,
                    modifier = Modifier.testTag("classroom_query_all_buildings"),
                ) {
                    Text("查询全部教学楼")
                }
            }
        },
        confirmButton = { com.upcpocket.app.ui.components.CampusTextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun RoomDetailDialog(
    detail: RoomDetail,
    onDismiss: () -> Unit,
) {
    val room = detail.room
    val colors = roomColors(room.available)
    com.upcpocket.app.ui.components.CampusAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(room.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("教学楼 ${room.building}")
                Text("校区 ${room.campus}")
                Text(
                    room.capacity?.let { "$it 人" } ?: "容量未知",
                )
                Text("日期 ${detail.query.date}")
                Text("第 ${formatPeriods(detail.query.periods)} 节")
                Surface(
                    color = colors.container,
                    contentColor = colors.content,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        if (room.available) "空闲" else "占用",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).testTag("classroom_detail_status"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = { com.upcpocket.app.ui.components.CampusTextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
