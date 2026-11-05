package com.upcpocket.app.ui.home

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.nav.filledIcon
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CAMPUS_ZONE = ZoneId.of("Asia/Shanghai")

private data class QuickAction(
    val title: String,
    val subtitle: String,
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
    cardSections: List<PluginSection>,
    sectionHost: PluginSectionHost,
    onOpenRoute: (PluginRouteId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOfficialWeb: () -> Unit,
) {
    val today = LocalDate.now(CAMPUS_ZONE)
    val dateLabel = today.format(
        DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA),
    )
    val greeting = greet(LocalTime.now(CAMPUS_ZONE))

    val actions = buildList {
        serviceEntries.forEach { entry ->
            add(
                QuickAction(
                    title = entry.title,
                    subtitle = entry.subtitle,
                    icon = entry.icon.filledIcon(),
                    onClick = { entry.route?.let(onOpenRoute) },
                ),
            )
        }
        add(
            QuickAction(
                title = "校园网页",
                subtitle = "课表、教室与图书馆的学校页面",
                icon = Icons.Outlined.Public,
                onClick = onOpenOfficialWeb,
            ),
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        ) {
            Header(
                eyebrow = "UPC POCKET",
                date = dateLabel,
                greeting = greeting,
                onOpenSettings = onOpenSettings,
            )

            if (actions.isNotEmpty()) {
                SectionHeader(
                    title = "常用服务",
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
                            "对应插件未启用。已安装的其他页面仍可从底部导航或设置进入。",
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

            Spacer(Modifier.height(22.dp))
            Text(
                text = "非学校官方应用。账号、个人查询缓存与设置保存在本机；" +
                    "结果以学校系统返回为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Header(
    eyebrow: String,
    date: String,
    greeting: String,
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
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$greeting，今天也按自己的节奏来。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(44.dp),
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
        modifier = modifier
            .heightIn(min = 132.dp)
            .clickable(onClick = action.onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(
                    icon = action.icon,
                    contentDescription = null,
                    size = 38.dp,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = action.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun greet(time: LocalTime): String = when (time.hour) {
    in 0..4 -> "夜深了"
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    else -> "晚上好"
}
