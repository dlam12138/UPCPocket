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
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.data.prefs.HomeModuleId
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.ReorderableModuleColumn
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.components.CampusIconButton
import com.upcpocket.app.ui.components.glassBackground
import com.upcpocket.app.ui.components.mergeReorderedSubset
import com.upcpocket.app.ui.components.normalizeModuleOrder
import com.upcpocket.app.ui.learning.LearningPlatformCatalog
import com.upcpocket.app.ui.nav.filledIcon
import com.upcpocket.app.ui.official.OfficialLink
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
private val HOME_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

private fun homeModuleKey(module: HomeModuleId): Any = module.name
private fun homeModuleLabel(module: HomeModuleId): String = module.label
private fun homeModuleTestTag(module: HomeModuleId): String = "home_module_${module.name.lowercase()}"
private fun isHomeModuleGrouped(module: HomeModuleId): Boolean = when (module) {
    HomeModuleId.TIMETABLE, HomeModuleId.TODO -> false
    HomeModuleId.SERVICES, HomeModuleId.CARD -> true
}

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
 * 首页固定的官方网页：按全部常规浏览器条目的稳定 ID 匹配，不限定分类或校区。
 *
 * 只包含带 [OfficialLink.serviceId] 的条目；微信交接入口没有 ID，因此不会出现在
 * 首页快捷项。点击仍由调用方交给系统浏览器，不附加登录参数或自动请求。
 */
internal fun pinnedOfficialWebLinks(pinnedIds: Set<String>): List<OfficialLink> =
    OfficialWebCatalog.all.filter { link ->
        link.serviceId?.let(pinnedIds::contains) == true
    }

/**
 * 原生服务与官方网页同名且同时固定时，只给网页版快捷项加「（网页）」后缀，
 * 官方目录本身的名称保持不变。
 */
internal fun officialWebQuickActionTitle(title: String, pinnedNativeTitles: Set<String>): String =
    if (title in pinnedNativeTitles) "$title（网页）" else title

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
    onOpenLearningPlatforms: () -> Unit = {},
    moduleOrder: List<HomeModuleId> = HomeModuleId.DEFAULT_ORDER,
    reorderEnabled: Boolean = false,
    onModuleOrderChange: (List<HomeModuleId>) -> Unit = {},
) {
    val context = LocalContext.current
    val today = rememberCampusDate()
    val dockClearance = LocalDockClearance.current
    val scrollState = rememberScrollState()
    val dateLabel = remember(today) { today.format(HOME_DATE_FORMATTER) }
    val pinnedEntries = remember(serviceEntries, pinnedServiceIds) {
        pinnedPluginEntries(serviceEntries, pinnedServiceIds)
    }
    val pinnedOfficialLinks = remember(pinnedServiceIds) {
        pinnedOfficialWebLinks(pinnedServiceIds)
    }
    val pinnedEntryTitles = remember(pinnedEntries) {
        pinnedEntries.mapTo(mutableSetOf()) { it.title }
    }
    val actions = remember(
        pinnedEntries,
        pinnedOfficialLinks,
        pinnedEntryTitles,
        pinnedServiceIds,
        onOpenRoute,
        onOpenServices,
        onOpenOfficialWeb,
        onOpenLearningPlatforms,
        context,
    ) {
        buildList {
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
                        title = officialWebQuickActionTitle(link.title, pinnedEntryTitles),
                        icon = link.icon,
                        onClick = { OfficialWebCatalog.open(context, link) },
                    ),
                )
            }
            // 学习平台是宿主二级页，不是外链；固定后仍打开同一聚合页。
            if (LearningPlatformCatalog.SERVICE_ID in pinnedServiceIds) {
                add(
                    QuickAction(
                        title = LearningPlatformCatalog.TITLE,
                        icon = LearningPlatformCatalog.icon,
                        onClick = onOpenLearningPlatforms,
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
    }
    val normalizedOrder = remember(moduleOrder) {
        normalizeModuleOrder(moduleOrder, HomeModuleId.DEFAULT_ORDER)
    }
    val visibleModules = remember(
        normalizedOrder,
        timetableSections.isNotEmpty(),
        todoSections.isNotEmpty(),
        actions.isNotEmpty(),
        cardSections.isNotEmpty(),
    ) {
        normalizedOrder.filter { module ->
            when (module) {
                HomeModuleId.TIMETABLE -> timetableSections.isNotEmpty()
                HomeModuleId.TODO -> todoSections.isNotEmpty()
                HomeModuleId.SERVICES -> actions.isNotEmpty()
                HomeModuleId.CARD -> cardSections.isNotEmpty()
            }
        }
    }
    val onReorderFinished = remember(normalizedOrder, onModuleOrderChange) {
        { reordered: List<HomeModuleId> ->
            onModuleOrderChange(mergeReorderedSubset(normalizedOrder, reordered))
        }
    }

    Scaffold(
        modifier = Modifier.glassBackground(),
        containerColor = if (LocalUiStyle.current == UiStyle.LIQUID_GLASS) {
            Color.Transparent
        } else {
            com.upcpocket.app.ui.theme.appScaffoldContainerColor()
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

            if (visibleModules.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ReorderableModuleColumn(
                    items = visibleModules,
                    key = ::homeModuleKey,
                    scrollState = scrollState,
                    spacing = 14.dp,
                    enabled = reorderEnabled,
                    itemLabel = ::homeModuleLabel,
                    itemTestTag = ::homeModuleTestTag,
                    itemGrouped = ::isHomeModuleGrouped,
                    onReorderFinished = onReorderFinished,
                ) { module, _ ->
                    when (module) {
                        HomeModuleId.TIMETABLE -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                timetableSections.forEach { section ->
                                    androidx.compose.runtime.key(section.entryId) {
                                        section.content(sectionHost)
                                    }
                                }
                            }
                        }
                        HomeModuleId.TODO -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                todoSections.forEach { section ->
                                    androidx.compose.runtime.key(section.entryId) {
                                        section.content(sectionHost)
                                    }
                                }
                            }
                        }
                        HomeModuleId.SERVICES -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(
                                    title = "常用服务",
                                    actionLabel = if (onOpenServices != null) "全部服务" else null,
                                    onAction = onOpenServices,
                                    modifier = Modifier.padding(bottom = 2.dp),
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
                        }
                        HomeModuleId.CARD -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(
                                    title = "校园卡",
                                    modifier = Modifier.padding(bottom = 8.dp),
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
                // 两列卡片宽度有限，较长的官方网页名可换到第二行，避免只剩省略号。
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
