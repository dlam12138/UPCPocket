package com.upcpocket.app.shiguang

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShiguangScreen(service: ShiguangService, onBack: () -> Unit, onOpenAccountSettings: () -> Unit) =
    ShiguangScreenContent(service, onBack, onOpenAccountSettings)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ShiguangScreenContent(
    service: ShiguangService,
    onBack: () -> Unit,
    onOpenAccountSettings: () -> Unit,
    syntheticResources: ShiguangWebResourceProvider? = null,
) {
    val state by service.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var keyword by rememberSaveable(state.accountEpoch) { mutableStateOf(state.keyword) }
    var filterName by rememberSaveable(state.accountEpoch) { mutableStateOf(ShiguangActivityFilter.ALL.name) }
    var hideEnded by rememberSaveable(state.accountEpoch) { mutableStateOf(true) }
    val activitiesScroll = rememberLazyListState()
    val mineScroll = rememberLazyListState()
    val detailScroll = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    DisposableEffect(service) {
        onDispose { service.state.value.interactiveAttempt?.let { service.cancelInteractiveLogin(it.id) } }
    }
    state.interactiveAttempt?.let { attempt ->
        ShiguangLoginDialog(
            service,
            attempt,
            syntheticResources,
            onSuccess = { },
            onFailure = { service.cancelInteractiveLogin(attempt.id) },
            onClose = { service.cancelInteractiveLogin(attempt.id) },
        )
    }

    val back = { if (state.selected != null) service.closeDetail() else onBack() }
    BackHandler(state.selected != null) { service.closeDetail() }
    LaunchedEffect(service, state.accountAvailable, state.accountEpoch) {
        if (state.accountAvailable && state.activities == null && state.error == null && !state.busy && state.interactiveAttempt == null) {
            service.activities()
        }
    }
    LaunchedEffect(state.accountEpoch) {
        activitiesScroll.scrollToItem(0)
        mineScroll.scrollToItem(0)
        detailScroll.scrollToItem(0)
    }
    LaunchedEffect(state.selected?.id, state.selected?.kind) {
        if (state.selected != null) detailScroll.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.selected != null) "活动详情" else "石光") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(
                        enabled = state.accountAvailable && !state.busy && state.interactiveAttempt == null,
                        onClick = { scope.launch { service.refresh() } },
                    ) { Icon(Icons.Outlined.Refresh, "刷新") }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                !state.accountAvailable -> AccountRequiredContent(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    onOpenAccountSettings = onOpenAccountSettings,
                )
                state.selected != null -> DetailContent(
                    state = state,
                    scroll = detailScroll,
                    supportsInteractiveLogin = service.supportsInteractiveLogin,
                    onInteractiveLogin = service::openInteractiveLogin,
                    onOpenAccountSettings = onOpenAccountSettings,
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                )
                else -> BrowseContent(
                    state = state,
                    service = service,
                    scope = scope,
                    keyword = keyword,
                    onKeywordChange = { keyword = it },
                    activityFilter = ShiguangActivityFilter.entries.firstOrNull { it.name == filterName } ?: ShiguangActivityFilter.ALL,
                    onActivityFilterChange = { filterName = it.name },
                    hideEnded = hideEnded,
                    onHideEndedChange = { hideEnded = it },
                    activitiesScroll = activitiesScroll,
                    mineScroll = mineScroll,
                    keyboard = keyboard,
                    onOpenAccountSettings = onOpenAccountSettings,
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BrowseContent(
    state: ShiguangState,
    service: ShiguangService,
    scope: kotlinx.coroutines.CoroutineScope,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    activityFilter: ShiguangActivityFilter,
    onActivityFilterChange: (ShiguangActivityFilter) -> Unit,
    hideEnded: Boolean,
    onHideEndedChange: (Boolean) -> Unit,
    activitiesScroll: LazyListState,
    mineScroll: LazyListState,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onOpenAccountSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMine = state.tab == ShiguangTab.MINE
    val page = if (isMine) state.participations else state.activities
    val visibleActivities = if (!isMine && page != null) page.visibleActivities(activityFilter, hideEnded) else emptyList()
    val hasPreviousResults = if (isMine) state.participations != null || state.summary != null else state.activities != null
    val scroll = if (isMine) mineScroll else activitiesScroll
    val inputEnabled = !state.busy && state.interactiveAttempt == null

    val submitSearch: () -> Unit = {
        keyboard?.hide()
        scope.launch {
            activitiesScroll.scrollToItem(0)
            service.activities(keyword.trim())
        }
    }

    LazyColumn(
        state = scroll,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            PageHeader(
                title = if (isMine) "我的第二课堂" else "校园活动",
                state = state,
                supportsInteractiveLogin = service.supportsInteractiveLogin,
                onInteractiveLogin = service::openInteractiveLogin,
            )
        }
        item(key = "tabs") {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(
                    selected = state.tab == ShiguangTab.ACTIVITIES,
                    enabled = inputEnabled,
                    onClick = { scope.launch { service.selectTab(ShiguangTab.ACTIVITIES) } },
                    text = { Text("活动") },
                )
                Tab(
                    selected = state.tab == ShiguangTab.MINE,
                    enabled = inputEnabled,
                    onClick = { scope.launch { service.selectTab(ShiguangTab.MINE) } },
                    text = { Text("我的") },
                )
            }
        }
        if (state.busy) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { reason ->
            item(key = "error") {
                FailureCard(reason, hasPreviousResults, onOpenAccountSettings)
            }
        }

        if (!isMine) {
            item(key = "search") {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { if (it.length <= 40) onKeywordChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = inputEnabled,
                    label = { Text("搜索活动") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (keyword.isNotEmpty()) {
                            IconButton(onClick = { onKeywordChange("") }, enabled = inputEnabled) {
                                Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                )
            }
            item(key = "filters") {
                ActivityFilters(
                    selected = activityFilter,
                    onSelected = onActivityFilterChange,
                    hideEnded = hideEnded,
                    onHideEndedChange = onHideEndedChange,
                    enabled = inputEnabled,
                )
            }
        } else {
            state.summary?.let { summary ->
                item(key = "hours-summary") { TotalHoursCard(summary) }
                item(key = "modules-summary") { ModuleSummaryCard(summary) }
            }
            item(key = "participations-heading") {
                Text("参与记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }

        if (page != null) {
            when {
                page.items.isEmpty() && isMine -> item(key = "empty") { EmptyState("暂无参与记录") }
                !isMine && visibleActivities.isEmpty() -> item(key = "empty") {
                    EmptyState("本页暂无符合条件的活动")
                }
            }
            items(
                items = if (isMine) page.items else visibleActivities,
                key = { "${it.kind}-${it.id}" },
            ) { activity ->
                ActivityCard(
                    activity = activity,
                    enabled = inputEnabled,
                    onClick = { scope.launch { service.detail(activity) } },
                )
            }
            item(key = "pagination") {
                Pagination(
                    page = page,
                    busy = !inputEnabled,
                    onPrevious = {
                        scope.launch {
                            scroll.scrollToItem(0)
                            if (isMine) service.mine(page.page - 1) else service.activities(state.keyword, page.page - 1)
                        }
                    },
                    onNext = {
                        scope.launch {
                            scroll.scrollToItem(0)
                            if (isMine) service.mine(page.page + 1) else service.activities(state.keyword, page.page + 1)
                        }
                    },
                )
            }
        } else if (!state.busy && state.error == null) {
            item(key = "not-loaded") {
                EmptyState(if (isMine) "点击刷新查看" else "点击刷新查看活动")
            }
        }
    }
}

@Composable
private fun AccountRequiredContent(modifier: Modifier, onOpenAccountSettings: () -> Unit) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
            Icon(Icons.Outlined.School, contentDescription = null, modifier = Modifier.padding(18.dp).size(32.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text("石光", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("保存学校账号后查看活动与参与记录", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenAccountSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("学校账号") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityFilters(
    selected: ShiguangActivityFilter,
    onSelected: (ShiguangActivityFilter) -> Unit,
    hideEnded: Boolean,
    onHideEndedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FilterChip(selected == ShiguangActivityFilter.ALL, { onSelected(ShiguangActivityFilter.ALL) }, enabled = enabled, label = { Text("全部") })
            FilterChip(selected == ShiguangActivityFilter.CAMPUS, { onSelected(ShiguangActivityFilter.CAMPUS) }, enabled = enabled, label = { Text("校园活动") })
            FilterChip(selected == ShiguangActivityFilter.SOCIAL_PRACTICE, { onSelected(ShiguangActivityFilter.SOCIAL_PRACTICE) }, enabled = enabled, label = { Text("社会实践") })
            FilterChip(hideEnded, { onHideEndedChange(!hideEnded) }, enabled = enabled, label = { Text("隐藏已结束") })
        }
        Text("仅筛本页", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PageHeader(
    title: String,
    state: ShiguangState,
    supportsInteractiveLogin: Boolean,
    onInteractiveLogin: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("第二课堂", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (supportsInteractiveLogin) {
            ShiguangInteractiveLoginAction(state, onInteractiveLogin)
        }
    }
}

@Composable
internal fun ShiguangInteractiveLoginAction(state: ShiguangState, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = !state.busy && state.interactiveAttempt == null,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(if (state.interactionRequired) "前往验证" else "网页登录")
    }
}

@Composable
private fun FailureCard(reason: ShiguangFailure, showingPreviousResults: Boolean, onOpenAccountSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reason.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (showingPreviousResults) {
                Text("当前显示上次结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            if (reason in setOf(ShiguangFailure.INVALID_CREDENTIALS, ShiguangFailure.ACCOUNT_CHANGED, ShiguangFailure.IDENTITY_MISMATCH)) {
                TextButton(onClick = onOpenAccountSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("检查账号") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityCard(activity: ShiguangActivity, enabled: Boolean, onClick: () -> Unit) {
    val category = activity.fields.firstValue("活动分类", "活动标签", "分类")
    val eventTime = activity.fields.firstValue("活动时间")
    val place = activity.fields.firstValue("活动地点", "地点")
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                activity.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ActivityTag(if (activity.kind == ShiguangKind.SOCIAL_PRACTICE) "社会实践" else "校园活动", emphasized = true)
                category?.let { ActivityTag(it) }
                activity.fields.firstValue("学时")?.let { ActivityTag("学时 $it") }
                activity.fields.firstValue("学分")?.let { ActivityTag("学分 $it") }
            }
            eventTime?.let { MetadataRow(Icons.Outlined.Event, it) }
            place?.let { MetadataRow(Icons.Outlined.LocationOn, it) }
        }
    }
}

@Composable
private fun ActivityTag(value: String, emphasized: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Text(
            value,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetadataRow(icon: ImageVector, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TotalHoursCard(summary: ShiguangSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("累计学时", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "${summary.totalHours.display()} 学时",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ModuleSummaryCard(summary: ShiguangSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            summary.modules.forEachIndexed { index, module ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(module.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${module.hours.display()} 学时", style = MaterialTheme.typography.bodySmall)
                            Text("${module.credits.display()} 学分", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    module.rank?.let { rank ->
                        Text("专业排名 ${rank.position} / ${rank.total}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (index < summary.modules.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Text(message, modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DetailContent(
    state: ShiguangState,
    scroll: LazyListState,
    supportsInteractiveLogin: Boolean,
    onInteractiveLogin: () -> Unit,
    onOpenAccountSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fields = state.detail?.fields ?: state.selected?.fields.orEmpty()
    val timePlaceKeys = setOf("活动时间", "结束时间", "活动地点", "地点")
    val signupKeys = setOf("报名时间", "报名截止时间")
    val organizationKeys = setOf("归属组织", "活动院系", "活动年级", "组织形式", "培养目标", "能力指标", "活动标签", "活动分类")
    val timePlace = fields.filterKeys { it in timePlaceKeys }
    val signup = fields.filterKeys { it in signupKeys }
    val organization = fields.filterKeys { it in organizationKeys }
    val other = fields.filterKeys { it !in timePlaceKeys && it !in signupKeys && it !in organizationKeys }

    LazyColumn(
        state = scroll,
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title") {
            Text(state.detail?.title ?: state.selected?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
        }
        if (supportsInteractiveLogin) {
            item(key = "interactive-login") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ShiguangInteractiveLoginAction(state, onInteractiveLogin)
                }
            }
        }
        if (state.busy) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { reason -> item(key = "error") { FailureCard(reason, state.detail != null, onOpenAccountSettings) } }
        if (timePlace.isNotEmpty()) item(key = "time-place") { DetailGroup("时间与地点", timePlace) }
        if (signup.isNotEmpty()) item(key = "signup") { DetailGroup("报名时间", signup) }
        if (organization.isNotEmpty()) item(key = "organization") { DetailGroup("组织与对象", organization) }
        if (other.isNotEmpty()) item(key = "other") { DetailGroup("活动信息", other) }
        state.detail?.description?.takeIf(String::isNotBlank)?.let { description ->
            item(key = "description") { DetailDescription(description) }
        }
        if (fields.isEmpty() && !state.busy && state.error == null) item(key = "empty") { EmptyState("暂无活动详情") }
    }
}

@Composable
private fun DetailGroup(title: String, fields: Map<String, String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            fields.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun DetailDescription(description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("活动介绍", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Pagination(page: ShiguangPage, busy: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onPrevious, enabled = !busy && page.page > 1, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("上一页")
        }
        Text(
            if (page.totalPages != null) "第 ${page.page} / ${page.totalPages} 页" else "第 ${page.page} 页",
            style = MaterialTheme.typography.labelMedium,
        )
        TextButton(onClick = onNext, enabled = !busy && page.nextPage != null, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("下一页")
        }
    }
}

private fun Map<String, String>.firstValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> get(key)?.takeIf(String::isNotBlank) }

private fun BigDecimal?.display(): String = this?.stripTrailingZeros()?.toPlainString() ?: "未知"
