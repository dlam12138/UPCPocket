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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.components.CampusIconButton
import com.upcpocket.app.ui.components.glassBackground
import com.upcpocket.app.ui.nav.filledIcon
import com.upcpocket.app.ui.official.OfficialWebCatalog
import com.upcpocket.app.ui.theme.LocalUiStyle
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

internal fun pinnedPluginEntries(
    availableEntries: List<PluginEntry>,
    pinnedIds: Set<String>,
): List<PluginEntry> = availableEntries.filter { it.entryId.value in pinnedIds }

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
    pinnedServiceIds: Set<String> = UiPrefs.DEFAULT_PINNED_SERVICES,
    onOpenServices: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val today = rememberCampusDate()
    val dockClearance = LocalDockClearance.current
    val scrollState = rememberScrollState()
    val dateLabel = today.format(
        DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA),
    )
    val pinnedEntries = remember(serviceEntries, pinnedServiceIds) {
        pinnedPluginEntries(serviceEntries, pinnedServiceIds)
    }
    val pinnedOfficialLinks = remember(pinnedServiceIds) {
        OfficialWebCatalog.serviceLinksFor(UiCategory.STUDY)
            .filter { it.serviceId?.let(pinnedServiceIds::contains) == true }
    }
    val actions = buildList {
        pinnedEntries.forEach { entry ->
            add(
                QuickAction(
                    title = entry.title,
                    icon = entry.icon.filledIcon(),
                    onClick = { entry.route?.let(onOpenRoute) },
                ),
            )
        }
        pinnedOfficialLinks.forEach { link ->
            add(
                QuickAction(
                    title = link.title,
                    icon = link.icon,
                    onClick = { OfficialWebCatalog.open(context, link) },
                ),
            )
        }
        if (isEmpty()) {
            add(
                QuickAction(
                    title = "选择常用服务",
                    icon = Icons.Outlined.Apps,
                    onClick = onOpenServices ?: onOpenOfficialWeb,
                ),
            )
        }
    }

    Scaffold(
        modifier = Modifier.glassBackground(),
        containerColor = if (LocalUiStyle.current == UiStyle.LIQUID_GLASS) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(
                    state = scrollState,
                    overscrollEffect = null,
                )
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
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = date,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        CampusIconButton(
            onClick = onOpenSettings,
            tonal = true,
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
    GlassCard(
        modifier = modifier.heightIn(min = 56.dp),
        onClick = action.onClick,
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
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.5.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
