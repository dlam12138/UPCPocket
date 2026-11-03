package com.upcpocket.app.ui.campus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.summaryText
import com.upcpocket.app.designsystem.DOCK_CLEARANCE
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.ui.components.GlassCard

/**
 * 「更多」= 设置与信息入口页。
 *
 * 学习和学校登录分组由插件贡献生成；外观、关于是宿主设置，继续留在 app。
 * M0 只把入口来源改为插件，分组标题、顺序和文案保持现状。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusScreen(
    learningEntries: List<PluginEntry>,
    schoolSections: List<PluginSection>,
    sectionHost: PluginSectionHost,
    accountState: SchoolAccountState,
    onOpenRoute: (PluginRouteId) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("更多") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // 悬浮 dock 是覆盖层：垫出尾部余量，末尾条目能完整滚出胶囊
                .padding(bottom = DOCK_CLEARANCE),
        ) {
            if (learningEntries.isNotEmpty()) {
                GroupTitle("学习")
                EntryCard {
                    learningEntries.forEach { entry ->
                        Entry(
                            icon = iconOf(entry.icon),
                            title = entry.title,
                            subtitle = entry.subtitle,
                            onClick = { entry.route?.let(onOpenRoute) },
                        )
                    }
                }
            }

            GroupTitle("学校账号")
            EntryCard {
                Entry(
                    icon = Icons.Filled.AccountCircle,
                    title = "学校账号",
                    subtitle = accountState.summaryText(),
                    onClick = onOpenAccount,
                )
            }

            if (schoolSections.isNotEmpty()) {
                GroupTitle("学校登录")
                EntryCard {
                    schoolSections.forEach { it.content(sectionHost) }
                }
            }

            GroupTitle("外观")
            EntryCard {
                Entry(
                    Icons.Filled.Palette,
                    "配色与深色模式",
                    "跟随壁纸动态取色，或选内置配色",
                    onOpenAppearance,
                )
            }

            GroupTitle("关于")
            EntryCard {
                Entry(
                    Icons.Filled.Info,
                    "关于 UPC Pocket",
                    "当前能力、数据边界、开源与致谢",
                    onOpenAbout,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** UI 契约只带稳定的图标枚举；Compose 图标由宿主决定，业务模块不泄漏 UI 类型。 */
private fun iconOf(icon: UiIcon): ImageVector = when (icon) {
    UiIcon.ELECTRICITY -> Icons.Filled.Bolt
    UiIcon.CLASSROOM -> Icons.Filled.MeetingRoom
    UiIcon.LIBRARY -> Icons.AutoMirrored.Filled.MenuBook
    UiIcon.INSIGHTS -> Icons.Filled.Insights
    UiIcon.TIMER -> Icons.Filled.Timer
    UiIcon.TIMETABLE -> Icons.AutoMirrored.Filled.MenuBook
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun EntryCard(content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(content = { content() })
    }
}

@Composable
private fun Entry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
