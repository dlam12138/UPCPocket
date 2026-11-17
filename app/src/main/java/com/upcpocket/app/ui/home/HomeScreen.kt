package com.upcpocket.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.nav.filledIcon
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val CAMPUS_ZONE = ZoneId.of("Asia/Shanghai")

private data class QuickAction(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * 「首页」= 今天的入口和校园服务，不再复制课表或“更多”的整套目录。
 *
 * 页面只展示已激活插件贡献的服务和校园卡区块；官方网页是宿主提供的只读外链
 * 目录，不冒充学校 API。首页不会打开课表服务，也不会为了摘要偷偷发起 CAS 或
 * 校园查询。
 */
@Composable
fun HomeScreen(
    serviceEntries: List<PluginEntry>,
    timetableSections: List<PluginSection>,
    cardSections: List<PluginSection>,
    todoSections: List<PluginSection>,
    sectionHost: PluginSectionHost,
    onOpenRoute: (PluginRouteId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOfficialWeb: () -> Unit,
    pinnedServiceIds: Set<String> = emptySet(),
    onOpenServices: (() -> Unit)? = null,
) {
    val today = rememberCampusDate()
    val dockClearance = LocalDockClearance.current
    val dateLabel = today.format(
        DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA),
    )
    val prioritizedEntries = remember(serviceEntries, pinnedServiceIds) {
        if (pinnedServiceIds.isEmpty()) {
            serviceEntries
        } else {
            val pinned = serviceEntries.filter { it.entryId.value in pinnedServiceIds }
            pinned.ifEmpty { serviceEntries.take(4) }
        }
    }
    val actions = buildList {
        prioritizedEntries.forEach { entry ->
            add(
                QuickAction(
                    title = entry.title,
                    icon = entry.icon.filledIcon(),
                    onClick = { entry.route?.let(onOpenRoute) },
                ),
            )
        }
        if (onOpenServices == null || isEmpty()) {
            add(
                QuickAction(
                    title = "校园网页",
                    icon = Icons.Outlined.Public,
                    onClick = onOpenOfficialWeb,
                ),
            )
        }
    }

    Scaffold(
        containerColor = com.upcpocket.app.ui.liquid.liquidScaffoldColor(),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp + dockClearance),
        ) {
            Header(
                eyebrow = "UPC POCKET",
                date = dateLabel,
                onOpenSettings = onOpenSettings,
            )

            if (timetableSections.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                timetableSections.forEach { section ->
                    androidx.compose.runtime.key(section.entryId) {
                        section.content(sectionHost)
                    }
                }
            }

            if (todoSections.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                todoSections.forEach { section ->
                    androidx.compose.runtime.key(section.entryId) {
                        section.content(sectionHost)
                    }
                }
            }

            if (actions.isNotEmpty()) {
                SectionHeader(
                    title = "常用服务",
                    actionLabel = if (onOpenServices != null) "全部服务" else null,
                    onAction = onOpenServices,
                    modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    actions.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { action ->
                                ServiceTile(
                                    action = action,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                GlassCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("校园服务暂不可用", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "请检查插件是否启用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 校园卡余额保留全部查询/绑定/历史/清缓存能力，只把区块移到首页内容底部。
            if (cardSections.isNotEmpty()) {
                SectionHeader(
                    title = "校园卡",
                    modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
                )
                cardSections.forEach { section ->
                    androidx.compose.runtime.key(section.entryId) {
                        section.content(sectionHost)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun rememberCampusDate(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now(CAMPUS_ZONE)) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now(CAMPUS_ZONE)
            val nextDate = now.toLocalDate().plusDays(1).atStartOfDay(CAMPUS_ZONE)
            delay(Duration.between(now, nextDate).toMillis().coerceAtLeast(1L))
            today = LocalDate.now(CAMPUS_ZONE)
        }
    }
    return today
}

@Composable
private fun Header(
    eyebrow: String,
    date: String,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = date,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "设置",
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ServiceTile(
    action: QuickAction,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = action.onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = action.icon,
                contentDescription = null,
                size = 32.dp,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
