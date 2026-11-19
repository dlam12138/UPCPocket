package com.upcpocket.app.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upcpocket.app.data.prefs.PreferredCampus
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.CampusIconButton
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.CampusTopAppBar
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
    preferredCampus: PreferredCampus? = null,
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

    val officialCandidates = remember(selectedFilter, preferredCampus) {
        val candidates = when (selectedFilter) {
            ServiceFilterTab.ALL, ServiceFilterTab.OFFICIAL -> OfficialWebCatalog.all
            else -> selectedFilter.category?.let { OfficialWebCatalog.serviceLinksFor(it) }.orEmpty()
        }
        OfficialWebCatalog.visibleFor(candidates, preferredCampus)
    }
    val filteredOfficial = remember(officialCandidates, normalizedQuery) {
        officialCandidates.filter { link ->
            normalizedQuery.isBlank() ||
                link.title.contains(normalizedQuery, ignoreCase = true) ||
                link.subtitle.contains(normalizedQuery, ignoreCase = true) ||
                link.group.contains(normalizedQuery, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            CampusTopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "校园服务",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                        TopBarSearchField(
                            query = query,
                            onQueryChange = { query = it },
                            modifier = Modifier.weight(1f),
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
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp + dockClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ServiceFilterTab.entries.forEach { tab ->
                    CampusFilterChip(
                        selected = selectedFilter == tab,
                        onClick = { selectedFilterName = tab.name },
                        label = {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }
            }

            if (groupedEntries.isEmpty() && filteredOfficial.isEmpty()) {
                EmptyStateCard(
                    title = "没有匹配的服务",
                    subtitle = if (normalizedQuery.isNotBlank()) "换个关键词，或切回“全部”" else "当前分类暂无可用工具",
                    icon = Icons.Outlined.Search,
                    actionLabel = if (normalizedQuery.isNotBlank()) "清空搜索" else null,
                    onAction = if (normalizedQuery.isNotBlank()) {
                        { query = "" }
                    } else null,
                )
                if (officialCandidates.isNotEmpty()) {
                    CampusTextButton(onClick = onOpenOfficialWeb) {
                        Text("查看完整目录")
                    }
                }
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
                                subtitle = entry.subtitleWithDefaultCampus(preferredCampus),
                                pinned = entry.entryId.value in pinnedServiceIds,
                                onTogglePin = { onTogglePin(entry.entryId.value) },
                                onClick = { entry.route?.let(onOpenRoute) },
                            )
                        }
                    }
                }

                if (filteredOfficial.isNotEmpty()) {
                    SectionHeader(
                        title = if (selectedFilter == ServiceFilterTab.ALL || selectedFilter == ServiceFilterTab.OFFICIAL) {
                            "校园官方网页"
                        } else {
                            "官方入口"
                        },
                        actionLabel = "完整目录",
                        onAction = onOpenOfficialWeb,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column {
                            filteredOfficial.forEachIndexed { index, link ->
                                val serviceId = link.serviceId
                                NavigationRow(
                                    title = link.title,
                                    subtitle = "${link.group} · ${link.subtitle}",
                                    icon = link.icon,
                                    onClick = { OfficialWebCatalog.open(context, link) },
                                    trailing = if (serviceId == null) null else {
                                        {
                                            IconButton(onClick = { onTogglePin(serviceId) }) {
                                                val pinned = serviceId in pinnedServiceIds
                                                Icon(
                                                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                                    contentDescription = if (pinned) "取消首页固定" else "固定到首页",
                                                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    },
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

/** 常用校区为单一校区时，给三张受其影响的服务卡加一句默认范围提示。 */
private fun PluginEntry.subtitleWithDefaultCampus(preferredCampus: PreferredCampus?): String {
    if (preferredCampus == null || preferredCampus == PreferredCampus.ALL) return subtitle
    return when (entryId.value) {
        "classroom.free", "library.home", "power.electricity" ->
            "$subtitle · 默认${preferredCampus.label}"
        else -> subtitle
    }
}

@Composable
private fun ServiceDirectoryCard(
    entry: PluginEntry,
    subtitle: String,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
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
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CampusIconButton(
                onClick = onTogglePin,
                tonal = pinned,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (pinned) "取消首页固定" else "固定到首页",
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun TopBarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    com.upcpocket.app.ui.components.CampusPanel(
        modifier = modifier.height(38.dp),
        shape = CircleShape,
        containerColor = scheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索服务",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.5.sp,
                        color = scheme.onSurface,
                    ),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onQueryChange("") }
                        .semantics { contentDescription = "清空搜索" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}
