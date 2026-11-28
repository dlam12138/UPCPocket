package com.upcpocket.app.floorplan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.CampusOutlinedButton
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.CampusTopAppBar
import com.upcpocket.app.ui.components.EmptyStateCard
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.GlassIconButton
import com.upcpocket.app.ui.components.GlassOutlinedTextField
import com.upcpocket.app.ui.theme.appScaffoldContainerColor

@Composable
internal fun FloorPlanLoadingScreen(
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    FloorPlanScaffold(onBack) { modifier ->
        Box(modifier, contentAlignment = Alignment.Center) {
            if (onRetry == null) {
                CircularProgressIndicator()
            } else {
                EmptyStateCard(
                    title = "平面图数据加载失败",
                    subtitle = "未能读取随包内置的矢量楼层数据。请重试；该功能不需要联网。",
                    actionLabel = "重试",
                    onAction = onRetry,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
internal fun FloorPlanScreen(
    catalog: FloorPlanCatalog,
    onBack: () -> Unit,
) {
    val initialBuilding = catalog.defaultBuilding
    val initialSheet = catalog.defaultSheet(initialBuilding)

    var buildingId by rememberSaveable { mutableStateOf(initialBuilding.id) }
    var sheetId by rememberSaveable { mutableStateOf(initialSheet.id) }
    var query by rememberSaveable { mutableStateOf("") }
    var activeQuery by rememberSaveable { mutableStateOf("") }
    var highlightSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var highlightRoomIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var focusRevision by rememberSaveable { mutableIntStateOf(0) }

    val building = FloorPlanBuilding.fromId(buildingId)
        ?.takeIf { candidate -> catalog.buildings.any { it == candidate } }
        ?: initialBuilding
    val sheets = catalog.sheetsFor(building)
    val sheet = sheets.firstOrNull { it.id == sheetId } ?: sheets.firstOrNull() ?: initialSheet
    val renderedSheets = catalog.sheetsForCanvas(sheet)
    val sharedLinework = catalog.sharedLineworkFor(sheet)
    val highlight = highlightSheetId?.let { highlightedSheet ->
        highlightRoomIndex?.let { index -> catalog.roomRef(highlightedSheet, index) }
    }?.takeIf { it.sheet.id == sheet.id }
    val searchResult = activeQuery.takeIf { it.isNotBlank() }?.let(catalog::search)

    // 视口放在 BoxWithConstraints 之外，随 sheet 保存缩放、图面中心和已消费的定位代次；
    // 旋转或键盘切换紧凑/常规布局时画布重建也不会丢当前视图。
    val viewportKey = catalog.viewportKey(sheet)
    val viewportSaver = remember(viewportKey) { floorPlanViewportStateSaver(viewportKey) }
    val viewport = rememberSaveable(viewportKey, saver = viewportSaver) {
        FloorPlanViewportState.initial(sheet)
    }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun dismissSearchInput() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun clearHighlight() {
        highlightSheetId = null
        highlightRoomIndex = null
    }

    fun selectSheet(targetBuilding: FloorPlanBuilding, targetSheet: FloorPlanSheet) {
        buildingId = targetBuilding.id
        sheetId = targetSheet.id
        clearHighlight()
        activeQuery = ""
        dismissSearchInput()
    }

    fun selectRoom(room: FloorPlanRoomRef) {
        buildingId = room.building.id
        sheetId = room.sheet.id
        highlightSheetId = room.sheet.id
        highlightRoomIndex = room.roomIndex
        focusRevision++
        dismissSearchInput()
    }

    fun submitSearch() {
        val submitted = query.trim()
        activeQuery = submitted
        dismissSearchInput()
        if (submitted.isEmpty()) {
            clearHighlight()
            return
        }
        when (val result = catalog.search(submitted)) {
            is FloorPlanSearchResult.Match -> selectRoom(result.room)
            is FloorPlanSearchResult.Ambiguous,
            is FloorPlanSearchResult.NotFound,
            FloorPlanSearchResult.Empty -> {
                // 未唯一命中时不改动楼区、楼层和画布，避免把未知房号误显示成已定位。
                clearHighlight()
            }
        }
    }

    fun clearQuery() {
        query = ""
        activeQuery = ""
        clearHighlight()
    }

    FloorPlanScaffold(onBack) { modifier ->
        BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
            // 先取局部值，避免在嵌套 Column 中受接收者影响；同一尺寸同时驱动布局与画布高度。
            val availableHeight = maxHeight
            val compactHeight = availableHeight < 560.dp
            val hasSearchFeedback = searchResult is FloorPlanSearchResult.NotFound ||
                searchResult is FloorPlanSearchResult.Ambiguous
            val controlsScroll = rememberScrollState()
            val feedbackScroll = rememberScrollState()

            // 控件与画布各只保留一处调用点：键盘压缩高度时只切换 Modifier，
            // 不销毁已聚焦的房号输入框，也不重建画布。
            Column(Modifier.fillMaxSize()) {
                Column(
                    modifier = if (compactHeight) {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(controlsScroll)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                ) {
                    FloorPlanControls(
                        catalog = catalog,
                        building = building,
                        sheet = sheet,
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = ::submitSearch,
                        onClearQuery = ::clearQuery,
                        onBuilding = { option ->
                            selectSheet(option, catalog.sheetForBuildingSwitch(sheet, option))
                        },
                        onSheet = { option -> selectSheet(building, option) },
                    )
                    if (hasSearchFeedback) {
                        if (compactHeight) {
                            FloorPlanSearchFeedback(
                                result = searchResult,
                                onSelect = ::selectRoom,
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 190.dp)
                                    .verticalScroll(feedbackScroll),
                            ) {
                                FloorPlanSearchFeedback(
                                    result = searchResult,
                                    onSelect = ::selectRoom,
                                )
                            }
                        }
                    }
                }
                FloorPlanCanvas(
                    building = building,
                    sheet = sheet,
                    renderedSheets = renderedSheets,
                    sharedLinework = sharedLinework,
                    highlight = highlight,
                    focusRevision = focusRevision,
                    viewport = viewport,
                    modifier = if (compactHeight) {
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height((availableHeight * 0.48f).coerceIn(150.dp, 300.dp))
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    },
                )
                FloorPlanHint()
            }
        }
    }
}

@Composable
private fun FloorPlanScaffold(
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = appScaffoldContainerColor(),
        topBar = {
            CampusTopAppBar(
                title = {
                    Text("卓越楼平面图", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        content(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloorPlanControls(
    catalog: FloorPlanCatalog,
    building: FloorPlanBuilding,
    sheet: FloorPlanSheet,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearQuery: () -> Unit,
    onBuilding: (FloorPlanBuilding) -> Unit,
    onSheet: (FloorPlanSheet) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassOutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text("查找房号") },
                placeholder = { Text("如 A233") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        GlassIconButton(onClick = onClearQuery) {
                            Icon(Icons.Default.Clear, contentDescription = "清除房号")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            CampusOutlinedButton(onClick = onSearch) {
                Text("查找")
            }
        }

        FloorPlanChooserRow(label = "楼区") {
            catalog.buildings.forEach { option ->
                CampusFilterChip(
                    selected = option == building,
                    onClick = { onBuilding(option) },
                    label = { Text(option.label) },
                )
            }
        }
        FloorPlanChooserRow(label = "楼层") {
            catalog.sheetsFor(building).forEach { option ->
                CampusFilterChip(
                    selected = option.id == sheet.id,
                    onClick = { onSheet(option) },
                    label = { Text(option.displayLevel) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloorPlanChooserRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun FloorPlanSearchFeedback(
    result: FloorPlanSearchResult?,
    onSelect: (FloorPlanRoomRef) -> Unit,
) {
    when (result) {
        null, FloorPlanSearchResult.Empty, is FloorPlanSearchResult.Match -> Unit
        is FloorPlanSearchResult.NotFound -> {
            GlassCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "未找到「${result.query}」",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "请输入图上完整印出的房号，例如 A233 或 卓越 A233；合并标注可输入其中任一完整房号，未印出的中间号不参与定位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is FloorPlanSearchResult.Ambiguous -> {
            // 同一房号可能印在多个图块上；按图层次序和图上位置稳定排列，便于逐处选择。
            val candidates = result.matches.sortedWith(
                compareBy(
                    { it.sheet.id },
                    { it.room.bounds.left },
                    { it.room.bounds.top },
                    { it.roomIndex },
                ),
            )
            GlassCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "找到 ${candidates.size} 处包含「${result.query}」的标注",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "同名标注按图层和图上从左到右排列，请选择具体位置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    candidates.forEachIndexed { index, room ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        CampusTextButton(
                            onClick = { onSelect(room) },
                            modifier = Modifier.fillMaxWidth(),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = room.room.label,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = floorPlanCandidateDetail(room, candidates),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun floorPlanCandidateDetail(
    room: FloorPlanRoomRef,
    matches: List<FloorPlanRoomRef>,
): String {
    val sameSheet = matches.filter { it.sheet.id == room.sheet.id }
    val ordinal = if (sameSheet.size > 1) {
        sameSheet
            .sortedWith(
                compareBy(
                    { it.room.bounds.left },
                    { it.room.bounds.top },
                    { it.roomIndex },
                ),
            )
            .indexOfFirst { it.key == room.key } + 1
    } else {
        0
    }
    return buildString {
        append(room.building.label)
        append(" · ")
        append(room.sheet.displayLevel)
        if (ordinal > 0) {
            append(" · 本层第 ")
            append(ordinal)
            append(" 处")
        }
        append(" · 来源第 ")
        append(room.sheet.sourcePage)
        append(" 页")
        if (room.sheet.buildingLevel != null) {
            append(" · 图注「")
            append(room.sheet.level)
            append("」")
        }
    }
}

@Composable
private fun FloorPlanHint() {
    Text(
        text = "双指缩放 · 单指平移 · 未核实的楼层注明为图注，现场门牌请再核对",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
