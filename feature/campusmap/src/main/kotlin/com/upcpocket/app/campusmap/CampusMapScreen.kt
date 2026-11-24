package com.upcpocket.app.campusmap

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class LocationAction { LOCATE, LOCATE_PRECISE, PLAN, NAVIGATE }

@Composable
internal fun CampusMapScreen(initialCampus: CampusId, requestedPlace: CampusMapPlaceRequest?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope, initialCampus) {
        CampusMapController(scope, initialCampus, loadMaps = {
            CampusMapDataSource.load(context.applicationContext)
        })
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val campus = state.campus
    var loadRequest by remember { mutableIntStateOf(0) }
    var locationEnabled by remember { mutableStateOf(false) }
    var locationStatus by remember { mutableStateOf(CampusLocationStatus.OFF) }
    var pendingPlan by remember { mutableStateOf(false) }
    var follows by remember { mutableStateOf(false) }
    var requestedAction by remember { mutableStateOf<LocationAction?>(null) }
    var rationale by remember { mutableStateOf(false) }
    var deniedLocation by remember { mutableStateOf(false) }
    var locationNotice by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var selectStart by remember { mutableStateOf(false) }
    var focusPlace by remember { mutableStateOf<CampusPlace?>(null) }
    var focusRevision by remember { mutableIntStateOf(0) }
    val navigating = isGuidanceVisible(state)

    fun centerOn(place: CampusPlace?) {
        focusPlace = place
        focusRevision++
    }
    fun select(place: CampusPlace) {
        if (place.isEntrance) return
        pendingPlan = false
        controller.choosePlace(place, asStart = selectStart)
        centerOn(place)
        follows = false
        query = ""
        selectStart = false
        searchFocused = false
        focusManager.clearFocus()
        keyboard?.hide()
    }
    fun proceedWithLocation(action: LocationAction) {
        locationEnabled = true
        locationNotice = null
        when (action) {
            LocationAction.LOCATE, LocationAction.LOCATE_PRECISE -> follows = true
            LocationAction.PLAN -> {
                follows = false
                if (state.fix?.usableForCampusNavigation() == true) controller.plan() else pendingPlan = true
            }
            LocationAction.NAVIGATE -> {
                follows = true
                controller.startNavigation()
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = requestedAction ?: return@rememberLauncherForActivityResult
        requestedAction = null
        val access = context.campusLocationAccess()
        if (access == CampusLocationAccess.PRECISE ||
            action == LocationAction.LOCATE && access == CampusLocationAccess.APPROXIMATE
        ) {
            proceedWithLocation(action)
        } else {
            locationNotice = when {
                access == CampusLocationAccess.NONE -> "定位权限未开启，可手动选择起点"
                action == LocationAction.LOCATE_PRECISE -> null
                else -> "路线需要精确定位，也可手动选择起点"
            }
            val activity = context.campusActivity()
            deniedLocation = activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    fun requestLocation(action: LocationAction) {
        val access = context.campusLocationAccess()
        if (access == CampusLocationAccess.PRECISE ||
            action == LocationAction.LOCATE && access == CampusLocationAccess.APPROXIMATE
        ) {
            proceedWithLocation(action)
            return
        }
        val activity = context.campusActivity()
        if (activity == null) {
            locationNotice = "当前无法申请定位权限，可手动选择起点"
            return
        }
        requestedAction = action
        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
            activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            rationale = true
        } else {
            permission.launch(campusLocationPermissions)
        }
    }
    fun plan() {
        if (state.manualStart != null) controller.plan() else requestLocation(LocationAction.PLAN)
    }
    fun exitNavigation() {
        pendingPlan = false
        locationEnabled = false
        follows = false
        controller.stopNavigation()
    }

    LaunchedEffect(controller, requestedPlace, loadRequest) {
        controller.load(requestedPlace)
        if (requestedPlace != null) controller.state.value.destination?.let(::centerOn)
    }
    ObserveCampusLocation(locationEnabled, controller::updatePosition) { locationStatus = it }
    LaunchedEffect(pendingPlan, state.fix) {
        if (pendingPlan && state.fix?.usableForCampusNavigation() == true) {
            pendingPlan = false
            controller.plan()
        }
    }
    LaunchedEffect(state.status) {
        if (state.status == NavigationStatus.ARRIVED) {
            locationEnabled = false
            follows = false
        }
    }

    val scheme = MaterialTheme.colorScheme
    val showResults = !navigating && (selectStart || query.isNotBlank())
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val compactMap = maxHeight < 480.dp
        val sheetLimit = maxHeight * if (compactMap) .42f else .46f
        Column(Modifier.fillMaxSize()) {
            Surface(color = scheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            if (selectStart || searchFocused && query.isNotBlank()) {
                                selectStart = false
                                query = ""
                                focusManager.clearFocus()
                                keyboard?.hide()
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                        Text(
                            "校园地图",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        for (id in CampusId.entries) {
                            FilterChip(
                                selected = state.campusId == id,
                                onClick = {
                                    pendingPlan = false
                                    follows = false
                                    focusPlace = null
                                    query = ""
                                    selectStart = false
                                    locationNotice = null
                                    controller.selectCampus(id)
                                },
                                label = { Text(id.title, maxLines = 1) },
                                enabled = campus != null && !navigating,
                            )
                        }
                    }
                    if (!navigating) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
                                .onFocusChanged { searchFocused = it.isFocused },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = if (query.isNotBlank() || selectStart) {
                                {
                                    IconButton(onClick = {
                                        query = ""
                                        selectStart = false
                                        focusManager.clearFocus()
                                        keyboard?.hide()
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "清除搜索")
                                    }
                                }
                            } else null,
                            placeholder = { Text(if (selectStart) "搜索起点，或在地图上点选" else "搜索楼名、简称或服务") },
                            singleLine = true,
                            shape = RoundedCornerShape(17.dp),
                            enabled = campus != null,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    campus != null -> {
                        CampusMapCanvas(
                            campus = campus,
                            state = state,
                            focusPlace = focusPlace,
                            focusRequest = focusRevision,
                            following = follows,
                            modifier = Modifier.fillMaxSize(),
                            onGesture = { follows = false },
                            onLocate = {
                                focusManager.clearFocus()
                                keyboard?.hide()
                                requestLocation(LocationAction.LOCATE)
                            },
                            onPlace = ::select,
                            onRoute = controller::selectRoute,
                        )
                        val mapNotice = locationNotice ?: locationStatus.message(state, campus)
                        if (mapNotice != null && !showResults) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = scheme.surface,
                                modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp, end = 64.dp),
                            ) {
                                Column {
                                    Text(
                                        mapNotice, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                    )
                                    if (locationStatus == CampusLocationStatus.APPROXIMATE) {
                                        TextButton(onClick = { requestLocation(LocationAction.LOCATE_PRECISE) }) {
                                            Text("开启精确定位")
                                        }
                                    }
                                }
                            }
                        }
                        if (showResults) {
                            val results = remember(campus, query) { campus.search(query).filterNot(CampusPlace::isEntrance) }
                            MapSearchResults(
                                query = query,
                                results = results,
                                selectingStart = selectStart,
                                onPlace = ::select,
                                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp)
                                    .heightIn(max = if (compactMap) 132.dp else 213.dp),
                            )
                        }
                    }
                    state.loadFailed -> Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("校园地图数据读取失败")
                        TextButton(onClick = { loadRequest++ }) { Text("重新加载") }
                    }
                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            if (campus != null && (!showResults || selectStart && query.isBlank())) {
                Surface(color = scheme.surfaceContainer, shape = RoundedCornerShape(topStart = 21.dp, topEnd = 21.dp)) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = sheetLimit).verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (navigating || state.status == NavigationStatus.ARRIVED) {
                            NavigationPanel(state, ::exitNavigation, controller::retry)
                        } else {
                            RoutePlanningPanel(
                                state = state,
                                campus = campus,
                                pendingPlan = pendingPlan,
                                onChooseStart = { selectStart = true; query = "" },
                                onUsePosition = {
                                    pendingPlan = false
                                    controller.useCurrentStart()
                                    requestLocation(LocationAction.LOCATE)
                                },
                                onPlace = ::select,
                                onPlan = ::plan,
                                onCancelPlan = { pendingPlan = false },
                                onMode = controller::setMode,
                                onRoute = controller::selectRoute,
                                onNavigate = { requestLocation(LocationAction.NAVIGATE) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false; requestedAction = null },
            title = { Text("允许校园地图使用位置") },
            text = { Text("在你打开定位后，校园地图用设备位置确定校内起点并进行前台导航。也可以手动选择起点预览路线。") },
            confirmButton = {
                TextButton(onClick = { rationale = false; permission.launch(campusLocationPermissions) }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { rationale = false; requestedAction = null }) { Text("暂不") }
            },
        )
    }
    if (deniedLocation) {
        AlertDialog(
            onDismissRequest = { deniedLocation = false },
            title = { Text("在系统设置开启精确定位") },
            text = { Text("可以在应用权限中开启精确位置，也可以回地图手动选择起点。") },
            confirmButton = {
                TextButton(onClick = {
                    deniedLocation = false
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    try {
                        context.campusActivity()?.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        locationNotice = "请在系统设置中打开 UPC Pocket 的位置权限"
                    }
                }) { Text("打开设置") }
            },
            dismissButton = { TextButton(onClick = { deniedLocation = false }) { Text("稍后") } },
        )
    }
}

@Composable
private fun MapSearchResults(
    query: String,
    results: List<CampusPlace>,
    selectingStart: Boolean,
    onPlace: (CampusPlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (query.isBlank() && selectingStart) {
        Surface(modifier, shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface) {
            Text("在地图上点选起点，或输入楼名搜索", modifier = Modifier.padding(11.dp))
        }
        return
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), tonalElevation = 3.dp) {
        if (results.isEmpty()) {
            Text("当前校区未找到匹配地点", Modifier.padding(15.dp))
        } else {
            LazyColumn {
                items(results, key = CampusPlace::id, contentType = { "place" }) { place ->
                    Column(Modifier.fillMaxWidth().clickable { onPlace(place) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(place.name, style = MaterialTheme.typography.titleSmall)
                        Text(place.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutePlanningPanel(
    state: CampusMapState,
    campus: CampusMapData,
    pendingPlan: Boolean,
    onChooseStart: () -> Unit,
    onUsePosition: () -> Unit,
    onPlace: (CampusPlace) -> Unit,
    onPlan: () -> Unit,
    onCancelPlan: () -> Unit,
    onMode: (TravelMode) -> Unit,
    onRoute: (String) -> Unit,
    onNavigate: () -> Unit,
) {
    val destination = state.destination?.takeUnless(CampusPlace::isEntrance)
    if (destination == null) {
        Text("选择目的地", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            when (state.unresolvedRequest) {
                is CampusMapPlaceRequest.TimetableText -> "未能定位课表地点，请选择校区并搜索楼名"
                is CampusMapPlaceRequest.Exact -> "未能定位该地点，请搜索楼名"
                null -> "点选地图上的地点，或搜索楼名"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(destination.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    val target = remember(campus, destination) { campus.routeTarget(destination) }
    if (state.status != NavigationStatus.READY) {
        Text(destination.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val host = destination.parentId ?: destination.id
    val related = remember(campus, host) {
        campus.places.filter { !it.isEntrance && (it.id == host || it.parentId == host) }
    }
    val canChooseRelated = state.status == NavigationStatus.IDLE || state.status == NavigationStatus.READY ||
        state.status == NavigationStatus.NO_ROUTE
    if (related.size > 1 && canChooseRelated) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(related, key = CampusPlace::id, contentType = { "related_place" }) { place ->
                FilterChip(selected = place.id == destination.id, onClick = { onPlace(place) }, label = { Text(place.name) })
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val startName = state.manualStart?.takeUnless(CampusPlace::isEntrance)?.name ?: "我的位置"
        Text(
            "从 $startName 出发",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (state.manualStart != null) TextButton(onClick = onUsePosition) { Text("用定位") }
        TextButton(onClick = onChooseStart) { Text("换起点") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        for (mode in TravelMode.entries) {
            FilterChip(selected = state.mode == mode, onClick = { onMode(mode) }, label = { Text(mode.title) })
        }
    }
    if (state.status == NavigationStatus.READY) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            itemsIndexed(state.routes, key = { _, route -> route.id }, contentType = { _, _ -> "route_option" }) { index, route ->
                val current = state.selectedRouteId == route.id
                Surface(
                    onClick = { onRoute(route.id) },
                    shape = RoundedCornerShape(11.dp),
                    color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.semantics { selected = current },
                ) {
                    Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
                        Text(if (index == 0) "最快" else "路线 ${index + 1}", style = MaterialTheme.typography.labelSmall)
                        Text(formatCampusDuration(route.seconds), style = MaterialTheme.typography.titleMedium)
                        val distance = formatCampusDistance(route.meters)
                        Text(
                            if (state.mode == TravelMode.BIKE && route.mode == TravelMode.WALK) "步行 $distance" else distance,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (route.walkFromIndex != null) {
                            Text(
                                "骑行 ${formatCampusDistance(route.cyclingMeters)} · 步行 ${formatCampusDistance(route.walkingMeters)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        val selectedRoute = state.routes.firstOrNull { it.id == state.selectedRouteId }
        when {
            selectedRoute?.walkFromIndex != null -> RouteModeLegend("蓝色实线骑行 · 橙色虚线步行")
            state.mode == TravelMode.BIKE && selectedRoute?.mode == TravelMode.WALK ->
                RouteModeLegend("橙色虚线步行")
        }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = onNavigate, modifier = Modifier.fillMaxWidth()) { Text("开始导航") }
    } else if (pendingPlan) {
        Text("正在等待精确定位，也可换一个手动起点", style = MaterialTheme.typography.bodySmall)
        FilledTonalButton(onClick = onCancelPlan, modifier = Modifier.fillMaxWidth()) { Text("取消等待") }
    } else if (state.status == NavigationStatus.PLANNING) {
        FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("正在规划路线") }
    } else {
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val canPlan = target != null
        if (!canPlan && state.notice == null) {
            Text("此地点位置待核，可选附近已标注地点", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onPlan, enabled = canPlan, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.status == NavigationStatus.NO_ROUTE) "重新规划" else "查看路线")
        }
    }
}

@Composable
private fun NavigationPanel(state: CampusMapState, onStop: () -> Unit, onRetry: () -> Unit) {
    val guidance = state.guidance
    val arrival = state.status == NavigationStatus.ARRIVED
    val title = guidance?.instruction ?: state.notice ?: if (arrival) "已到达目的地附近" else "等待定位"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (guidance != null && guidance.metersToInstruction >= 1.0) {
                Text("前方 ${formatCampusDistance(guidance.metersToInstruction)}", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                title,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TextButton(onClick = onStop) { Text(if (arrival) "完成" else "结束导航") }
    }
    guidance?.let {
        Text(
            "剩余 ${formatCampusDistance(it.remainingMeters)} · 约 ${formatCampusDuration(it.remainingSeconds)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    val selectedRoute = state.routes.firstOrNull { it.id == state.selectedRouteId }
    if (!arrival && selectedRoute != null) {
        when {
            selectedRoute.walkFromIndex != null ->
                RouteModeLegend("蓝色实线骑行 · 橙色虚线步行")
            state.mode == TravelMode.BIKE && selectedRoute.mode == TravelMode.WALK ->
                RouteModeLegend("橙色虚线步行")
        }
    }
    if (state.status == NavigationStatus.NAVIGATION_NO_ROUTE) {
        FilledTonalButton(
            onClick = onRetry,
            enabled = state.fix?.let { it.usableForCampusNavigation() && state.campus?.contains(it.point) == true } == true,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("重试规划") }
    }
}

@Composable
private fun RouteModeLegend(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun isGuidanceVisible(state: CampusMapState): Boolean = when (state.status) {
    NavigationStatus.NAVIGATING, NavigationStatus.WAITING_LOCATION, NavigationStatus.REROUTING,
    NavigationStatus.NAVIGATION_NO_ROUTE -> true
    else -> false
}

private fun CampusPlace.subtitle(): String {
    val type = when (kind) {
        "food" -> "餐厅"
        "gate" -> "校门"
        "landmark" -> "校园地标"
        "library" -> "图书馆"
        "medical" -> "校医院"
        "residence" -> "公寓"
        "service" -> "生活服务"
        "sport" -> "体育场地"
        "teaching" -> "教学楼"
        else -> null
    }
    val source = when (confidence) {
        "estimated" -> "位置待核"
        "reference" -> "楼栋参考，地点待核"
        "verified" -> "位置已核实"
        "verified-area" -> "所在区域已核实"
        "verified-building" -> "所在楼栋已核实"
        "surveyed" -> "用户标注"
        else -> "地图已标注"
    }
    return listOfNotNull(region, type, source).joinToString(" · ")
}

private fun CampusLocationStatus.message(state: CampusMapState, campus: CampusMapData): String? = when (this) {
    CampusLocationStatus.OFF -> null
    CampusLocationStatus.WAITING -> "正在获取位置…"
    CampusLocationStatus.NO_FIX -> "暂时无法定位，请到室外开阔处再试"
    CampusLocationStatus.ACCURATE -> {
        state.fix?.let { fix ->
            if (!campus.contains(fix.point)) "定位点在所选校区外"
            else "定位精度约 ${formatCampusDistance(fix.accuracy.coerceAtLeast(1.0))}"
        }
    }
    CampusLocationStatus.LOW_ACCURACY -> state.fix?.let {
        "定位误差约 ${formatCampusDistance(it.accuracy)}，请到室外开阔处再试"
    } ?: "定位精度不足，请到室外开阔处再试"
    CampusLocationStatus.APPROXIMATE -> "系统仅允许大致定位"
    CampusLocationStatus.DISABLED -> "系统定位已关闭"
    CampusLocationStatus.UNAVAILABLE -> "设备定位暂不可用，可手动选择起点"
    CampusLocationStatus.DENIED -> "定位权限未开启"
}

private fun formatCampusDistance(meters: Double): String =
    if (meters < 1_000) "${meters.roundToInt()} 米" else String.format(Locale.ROOT, "%.1f 公里", meters / 1_000)

private fun formatCampusDuration(seconds: Int): String =
    "${ceil(seconds / 60.0).toInt().coerceAtLeast(1)} 分钟"
