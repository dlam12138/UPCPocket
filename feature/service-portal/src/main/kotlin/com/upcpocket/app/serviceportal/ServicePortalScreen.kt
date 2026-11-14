package com.upcpocket.app.serviceportal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicePortalScreen(
    service: ServicePortalService,
    onBack: () -> Unit,
    onOpenAccountSettings: () -> Unit,
) {
    val state by service.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var keyword by rememberSaveable(state.accountEpoch) { mutableStateOf(state.keyword) }
    val servicesScroll = rememberLazyListState()
    val todosScroll = rememberLazyListState()
    val applicationsScroll = rememberLazyListState()
    val guideScroll = rememberScrollState()
    val error = state.error

    LaunchedEffect(service, state.accountEpoch) {
        if (state.catalog == null && !state.busy) service.search(state.keyword)
    }
    LaunchedEffect(state.accountEpoch) {
        servicesScroll.scrollToItem(0)
        todosScroll.scrollToItem(0)
        applicationsScroll.scrollToItem(0)
        guideScroll.scrollTo(0)
    }
    BackHandler(state.selected != null) { service.closeGuide() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(state.selected?.let { "办事指南" } ?: "e站通") },
                navigationIcon = {
                    IconButton(onClick = { if (state.selected != null) service.closeGuide() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !state.busy,
                        onClick = {
                            when {
                                state.selected != null -> service.refreshGuide()
                                state.tab == ServicePortalTab.SERVICES -> service.search(keyword)
                                state.tab == ServicePortalTab.TODOS -> service.todos(state.todos?.page ?: 1, state.pageSize)
                                else -> service.applications(state.applications?.page ?: 1, state.pageSize)
                            }
                        },
                    ) { Icon(Icons.Outlined.Refresh, contentDescription = "刷新") }
                    TextButton(
                        enabled = !state.busy && !state.accountBlocked,
                        onClick = onOpenAccountSettings,
                    ) { Text("学校账号") }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
                if (state.selected == null) {
                    TabRow(selectedTabIndex = state.tab.ordinal) {
                        ServicePortalTab.entries.forEach { tab ->
                            Tab(
                                selected = state.tab == tab,
                                onClick = { service.selectTab(tab) },
                                text = { Text(tab.label) },
                            )
                        }
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        when {
                            state.accountBlocked -> "账号正在切换"
                            state.loginStatus == ServicePortalLoginStatus.WORKING -> "登录中…"
                            state.loginStatus == ServicePortalLoginStatus.SUCCEEDED -> "已登录"
                            state.loginStatus == ServicePortalLoginStatus.FAILED -> "登录失败"
                            !state.accountAvailable -> "未登录"
                            else -> "尚未登录"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.accountAvailable) {
                        TextButton(
                            enabled = !state.busy && !state.accountBlocked,
                            onClick = service::loginOrRetry,
                        ) {
                            Text(if (state.loginStatus == ServicePortalLoginStatus.FAILED) "重试登录" else "登录")
                        }
                    }
                }
                state.authenticationNotice?.let { reason ->
                    val canOpenAccountSettings = reason != ServicePortalFailure.INTERACTION_REQUIRED
                    NoticeCard(
                        text = when (reason) {
                            ServicePortalFailure.INTERACTION_REQUIRED -> "请在学校页面继续登录，当前显示公开内容。"
                            ServicePortalFailure.INVALID_CREDENTIALS -> "学校账号暂不可用，当前显示公开内容。"
                            ServicePortalFailure.ACCOUNT_UNAVAILABLE -> "当前显示公开内容，登录后可见事项可能更多。"
                            ServicePortalFailure.IDENTITY_MISMATCH -> "账号身份未确认，当前显示公开内容。"
                            else -> "当前显示公开内容，登录后可见事项可能更多。"
                        },
                        action = if (canOpenAccountSettings) "学校账号" else null,
                        onAction = if (canOpenAccountSettings) onOpenAccountSettings else null,
                    )
                }
                if (
                    state.selected == null && state.tab == ServicePortalTab.SERVICES &&
                    state.results?.scope == ServicePortalScope.PUBLIC && state.authenticationNotice == null
                ) {
                    NoticeCard(
                        "当前为公开目录，登录后可见事项可能更多。",
                        action = "学校账号",
                        onAction = onOpenAccountSettings,
                    )
                }
                if (state.refreshFailed && error != null) {
                    NoticeCard("刷新失败：${error.label()}，仍显示上次结果。")
                } else if (error != null) {
                    val canOpenAccountSettings = error in setOf(
                        ServicePortalFailure.ACCOUNT_UNAVAILABLE,
                        ServicePortalFailure.ACCOUNT_CHANGED,
                        ServicePortalFailure.INVALID_CREDENTIALS,
                        ServicePortalFailure.IDENTITY_MISMATCH,
                        ServicePortalFailure.AUTH_REQUIRED,
                    )
                    NoticeCard(
                        "${error.label()}。",
                        action = if (canOpenAccountSettings) "学校账号" else null,
                        onAction = if (canOpenAccountSettings) onOpenAccountSettings else null,
                    )
                }

                when {
                    state.selected != null -> GuideContent(
                        state = state,
                        scroll = guideScroll,
                        onBack = service::closeGuide,
                    )
                    state.tab == ServicePortalTab.SERVICES -> ServiceListContent(
                        state = state,
                        keyword = keyword,
                        onKeywordChange = { keyword = it },
                        scroll = servicesScroll,
                        onSearch = {
                            keyboard?.hide()
                            service.search(keyword)
                        },
                        onPage = service::pageSearch,
                        onOpen = service::openGuide,
                    )
                    state.tab == ServicePortalTab.TODOS -> TodoListContent(
                        state = state,
                        scroll = todosScroll,
                        onPrevious = service::previousTodosPage,
                        onNext = service::nextTodosPage,
                        onFirst = { service.todos(1, state.pageSize) },
                        onRefresh = { service.todos(state.todos?.page ?: 1, state.pageSize) },
                    )
                    else -> ApplicationListContent(
                        state = state,
                        scroll = applicationsScroll,
                        onPrevious = service::previousApplicationsPage,
                        onNext = service::nextApplicationsPage,
                        onFirst = { service.applications(1, state.pageSize) },
                        onRefresh = { service.applications(state.applications?.page ?: 1, state.pageSize) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceListContent(
    state: ServicePortalState,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    scroll: LazyListState,
    onSearch: () -> Unit,
    onPage: (Int) -> Unit,
    onOpen: (ServicePortalItem) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("事项名称或部门") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                trailingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            )
            Button(onClick = onSearch, enabled = !state.busy) { Text("搜索") }
        }
        val page = state.results
        when {
            page == null && state.busy -> LoadingState()
            page == null -> EmptyState("搜索事项")
            page.items.isEmpty() && !page.pageInRange -> ServiceSearchPageRecovery(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onFirstPage = { onPage(1) },
            )
            page.items.isEmpty() -> EmptyState(if (page.total == 0) "没有匹配事项" else "此页暂无事项")
            else -> LazyColumn(
                state = scroll,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "scope") {
                    Text(
                        if (page.scope == ServicePortalScope.ACCOUNT) "当前账号可见目录 · ${page.total} 项" else "公开目录 · ${page.total} 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(page.items, key = ServicePortalItem::rowKey) { item ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(item) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            item.department?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            Text(item.access.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item(key = "pagination") {
                    PageControls(
                        page = page.page,
                        pageSize = page.pageSize,
                        total = page.total,
                        canPrevious = page.page > 1,
                        canNext = page.hasMore && page.page < MAX_PAGE,
                        onPrevious = { onPage(page.page - 1) },
                        onNext = { onPage(page.page + 1) },
                    )
                    if (page.hasMore && page.page == MAX_PAGE) {
                        Text("最多显示前 50 页，后续事项未加载。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ServiceSearchPageRecovery(
    modifier: Modifier = Modifier,
    onFirstPage: () -> Unit,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("此页暂无事项", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onFirstPage) { Text("回到第一页") }
    }
}

@Composable
private fun TodoListContent(
    state: ServicePortalState,
    scroll: LazyListState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFirst: () -> Unit,
    onRefresh: () -> Unit,
) {
    val page = state.todos
    when {
        page == null && state.busy -> LoadingState()
        page == null -> EmptyState("待处理事项尚未加载")
        !page.pageInRange -> OutOfRangePage(
            page = page.page,
            pageSize = page.pageSize,
            total = page.total,
            busy = state.busy,
            onPrevious = onPrevious,
            onFirst = onFirst,
            onRefresh = onRefresh,
        )
        page.total == 0 -> EmptyState("暂无待处理事项")
        else -> LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "todo-summary") {
                Text("本人待处理 ${page.total} 项 · 第 ${page.page} 页", style = MaterialTheme.typography.labelMedium)
                if (!page.completeForPage) Text("本页结果可能不完整。", style = MaterialTheme.typography.bodySmall)
                if (!page.completeForScope) Text("仅显示当前页；其他页未加载。", style = MaterialTheme.typography.bodySmall)
            }
            items(page.items, key = ServicePortalTodo::rowKey) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title.ifBlank { "未命名事项" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        item.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        item.currentNode?.let { Text("当前节点：$it", style = MaterialTheme.typography.bodyMedium) }
                        item.createdAt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (item.thirdParty) Text("第三方事项", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item(key = "todo-pages") {
                PageControls(page.page, page.pageSize, page.total, page.page > 1, page.hasMore && page.page < MAX_PAGE, onPrevious, onNext)
                if (page.hasMore && page.page == MAX_PAGE) Text("最多查看 50 页，后续事项未加载。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新") }
            }
        }
    }
}

@Composable
private fun ApplicationListContent(
    state: ServicePortalState,
    scroll: LazyListState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFirst: () -> Unit,
    onRefresh: () -> Unit,
) {
    val page = state.applications
    when {
        page == null && state.busy -> LoadingState()
        page == null -> EmptyState("在办申请尚未加载")
        !page.pageInRange -> OutOfRangePage(
            page = page.page,
            pageSize = page.pageSize,
            total = page.total,
            busy = state.busy,
            onPrevious = onPrevious,
            onFirst = onFirst,
            onRefresh = onRefresh,
        )
        page.total == 0 -> EmptyState("暂无在办申请")
        else -> LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "application-summary") {
                Text("本人在办 ${page.total} 项 · 第 ${page.page} 页", style = MaterialTheme.typography.labelMedium)
                if (!page.completeForPage) Text("本页结果可能不完整。", style = MaterialTheme.typography.bodySmall)
                if (!page.completeForScope) Text("仅显示当前页；其他页未加载。", style = MaterialTheme.typography.bodySmall)
            }
            items(page.items, key = ServicePortalApplication::rowKey) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title.ifBlank { "未命名申请" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        item.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        item.createdAt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (item.thirdParty) Text("第三方事项", style = MaterialTheme.typography.labelSmall)
                        item.progress?.let {
                            Text("进度 ${it.percent}% · ${it.nodeCount} 个节点", style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(
                                progress = { it.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } ?: Text("进度未知 · 可能正在等待他人处理", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item(key = "application-pages") {
                PageControls(page.page, page.pageSize, page.total, page.page > 1, page.hasMore && page.page < MAX_PAGE, onPrevious, onNext)
                if (page.hasMore && page.page == MAX_PAGE) Text("最多查看 50 页，后续申请未加载。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新") }
            }
        }
    }
}

@Composable
private fun OutOfRangePage(
    page: Int,
    pageSize: Int,
    total: Int,
    busy: Boolean,
    onPrevious: () -> Unit,
    onFirst: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("此页已超出当前范围。", style = MaterialTheme.typography.bodyLarge)
        PageControls(
            page = page,
            pageSize = pageSize,
            total = total,
            canPrevious = page > 1,
            canNext = false,
            onPrevious = onPrevious,
            onNext = {},
        )
        TextButton(onClick = onFirst, enabled = !busy && page > 1) { Text("回到第一页") }
        TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
    }
}

@Composable
private fun GuideContent(
    state: ServicePortalState,
    scroll: androidx.compose.foundation.ScrollState,
    onBack: () -> Unit,
) {
    val guide = state.guide
    if (guide == null) {
        if (state.busy) LoadingState() else EmptyState("指南暂不可用")
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回事项") }
        Text(guide.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        guide.department?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        Text(guide.access.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (guide.scope == ServicePortalScope.PUBLIC) Text("公开指南", style = MaterialTheme.typography.bodySmall)
        if (guide.selectionMayBeAmbiguous) {
            NoticeCard("多个事项共用编号，当前指南名称可能与所选事项不同。")
        }
        Text("办事指南不代表已申请或可申请。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (guide.description.isNotBlank()) {
            Text("办理说明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(guide.description)
            if (guide.descriptionTruncated) Text("说明可能不完整。", style = MaterialTheme.typography.bodySmall)
        }
        listOfNotNull(
            guide.handlingPlace?.let { "办理地点" to it },
            guide.handlingTimes?.let { "办理时间" to it },
            guide.chargeType?.let { "收费" to it },
            guide.charge?.let { "费用" to it },
            guide.formCount?.let { "表单" to "${it} 项" },
            guide.externalHost?.let { "外部入口" to it },
        guide.startNodes.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "流程节点" to it },
        ).forEach { (label, value) ->
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(value)
        }
        Text("办理材料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when (guide.materialsState) {
            ServicePortalMaterialsState.MISSING -> Text("材料信息未提供。")
            ServicePortalMaterialsState.EMPTY -> Text("暂无材料。")
            ServicePortalMaterialsState.AVAILABLE -> guide.materials.forEach { material ->
                Text(material.label, style = MaterialTheme.typography.titleSmall)
                if (material.value.isNotBlank()) Text(material.value)
                if (material.truncated) Text("此项内容可能不完整。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (guide.materialsTruncated) Text("仅显示部分材料。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PageControls(
    page: Int,
    pageSize: Int,
    total: Int,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(enabled = canPrevious, onClick = onPrevious) { Text("上一页") }
        val pages = maxOf(1, ((total.toLong() + pageSize - 1) / pageSize).toInt())
        Text("第 $page 页 / $pages 页", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = canNext, onClick = onNext) { Text("下一页") }
    }
}

@Composable
private fun NoticeCard(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}

private val ServicePortalTab.label: String
    get() = when (this) {
        ServicePortalTab.SERVICES -> "事项"
        ServicePortalTab.TODOS -> "待处理"
        ServicePortalTab.APPLICATIONS -> "在办"
    }

private fun ServicePortalAccess.label(): String = when (this) {
    ServicePortalAccess.NATIVE_FORM -> "在线表单"
    ServicePortalAccess.EXTERNAL -> "外部办理"
    ServicePortalAccess.MINI_PROGRAM -> "小程序办理"
    ServicePortalAccess.FORM_UNKNOWN -> "办理方式待确认"
    ServicePortalAccess.GUIDE_ONLY -> "办事指南"
    ServicePortalAccess.UNKNOWN -> "类型未知"
}

private fun ServicePortalFailure.label(): String = when (this) {
    ServicePortalFailure.INVALID_INPUT -> "关键词最多 40 个字"
    ServicePortalFailure.INTERACTION_REQUIRED -> "请在学校页面继续登录"
    ServicePortalFailure.INVALID_CREDENTIALS -> "账号或密码有误"
    ServicePortalFailure.NETWORK -> "网络连接失败"
    ServicePortalFailure.TLS -> "安全连接失败"
    ServicePortalFailure.UPSTREAM -> "学校服务暂不可用"
    ServicePortalFailure.PROTOCOL -> "学校数据格式发生变化"
    ServicePortalFailure.ACCOUNT_UNAVAILABLE -> "请先保存学校账号"
    ServicePortalFailure.ACCOUNT_CHANGED -> "账号已变化，请刷新"
    ServicePortalFailure.ACCESS_DENIED -> "学校暂不允许访问"
    ServicePortalFailure.IDENTITY_MISMATCH -> "登录身份与当前账号不符"
    ServicePortalFailure.AUTH_REQUIRED -> "登录已失效，请刷新"
    ServicePortalFailure.BUSY -> "认证正在进行，请稍后"
}

private const val MAX_PAGE = 50
