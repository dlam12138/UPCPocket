package com.upcpocket.app.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.ui.components.EmptyStateCard
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.NavigationRow
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.nav.filledIcon
import com.upcpocket.app.ui.official.OfficialWebCatalog

private enum class ServiceFilterTab(val label: String, val category: UiCategory? = null) {
    ALL("全部"),
    STUDY("学习与空间", UiCategory.STUDY),
    LIFE("校园生活", UiCategory.LIFE),
    CAMPUS("校务与成长", UiCategory.CAMPUS),
    OFFICIAL("官方网页"),
}

/**
 * 校园服务广场：按主流校园场景分类组织所有已激活插件入口与官方外链，
 * 支持关键词检索与一键置顶到首页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServicesScreen(
    entries: List<PluginEntry>,
    pinnedServiceIds: Set<String>,
    onTogglePin: (String) -> Unit,
    onOpenRoute: (PluginRouteId) -> Unit,
    onOpenOfficialWeb: () -> Unit,
) {
    val context = LocalContext.current
    val dockClearance = LocalDockClearance.current
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilterName by rememberSaveable { mutableStateOf(ServiceFilterTab.ALL.name) }
    val selectedFilter = ServiceFilterTab.entries.firstOrNull { it.name == selectedFilterName }
        ?: ServiceFilterTab.ALL
    val normalizedQuery = query.trim()

    val filteredEntries = remember(entries, selectedFilter, normalizedQuery) {
        if (selectedFilter == ServiceFilterTab.OFFICIAL) {
            emptyList()
        } else {
            entries.filter { entry ->
                val matchesCategory = selectedFilter.category == null || entry.category == selectedFilter.category
                val matchesQuery = normalizedQuery.isBlank() ||
                    entry.title.contains(normalizedQuery, ignoreCase = true) ||
                    entry.subtitle.contains(normalizedQuery, ignoreCase = true) ||
                    entry.keywords.contains(normalizedQuery, ignoreCase = true) ||
                    entry.category.label.contains(normalizedQuery, ignoreCase = true)
                matchesCategory && matchesQuery
            }
        }
    }

    val groupedEntries = remember(filteredEntries) {
        filteredEntries
            .groupBy { it.category }
            .entries
            .sortedBy { it.key.sortOrder }
    }

    val showOfficial = selectedFilter == ServiceFilterTab.ALL || selectedFilter == ServiceFilterTab.OFFICIAL
    val filteredOfficial = remember(showOfficial, normalizedQuery) {
        if (!showOfficial) {
            emptyList()
        } else {
            OfficialWebCatalog.all.filter { link ->
                normalizedQuery.isBlank() ||
                    link.title.contains(normalizedQuery, ignoreCase = true) ||
                    link.subtitle.contains(normalizedQuery, ignoreCase = true) ||
                    link.group.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("校园服务")
                        Text(
                            "点图钉可固定到首页",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp + dockClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索服务、教室、校园卡、学时…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ServiceFilterTab.entries.forEach { tab ->
                    FilterChip(
                        selected = selectedFilter == tab,
                        onClick = { selectedFilterName = tab.name },
                        label = { Text(tab.label) },
                    )
                }
            }

            if (groupedEntries.isEmpty() && filteredOfficial.isEmpty()) {
                EmptyStateCard(
                    title = "没有匹配的服务",
                    subtitle = if (normalizedQuery.isNotBlank()) "换个关键词试试，或切换到“全部”。" else "当前分类暂无可用工具。",
                    icon = Icons.Outlined.Search,
                    actionLabel = if (normalizedQuery.isNotBlank()) "清空搜索" else null,
                    onAction = if (normalizedQuery.isNotBlank()) {
                        { query = "" }
                    } else null,
                )
            } else {
                groupedEntries.forEach { (category, items) ->
                    SectionHeader(
                        title = category.label,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { entry ->
                            ServiceDirectoryCard(
                                entry = entry,
                                pinned = entry.entryId.value in pinnedServiceIds,
                                onTogglePin = { onTogglePin(entry.entryId.value) },
                                onClick = { entry.route?.let(onOpenRoute) },
                            )
                        }
                    }
                }

                if (filteredOfficial.isNotEmpty()) {
                    SectionHeader(
                        title = "校园官方网页",
                        actionLabel = "完整目录",
                        onAction = onOpenOfficialWeb,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column {
                            filteredOfficial.forEachIndexed { index, link ->
                                NavigationRow(
                                    title = link.title,
                                    subtitle = "${link.group} · ${link.subtitle}",
                                    icon = Icons.Outlined.Public,
                                    onClick = { OfficialWebCatalog.openUrl(context, link.url) },
                                )
                                if (index != filteredOfficial.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 66.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ServiceDirectoryCard(
    entry: PluginEntry,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = entry.icon.filledIcon(),
                contentDescription = null,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (pinned) "取消首页固定" else "固定到首页",
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
