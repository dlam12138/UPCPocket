package com.upcpocket.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.DOCK_CLEARANCE
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.ui.components.GlassCard

/**
 * 「首页」= 校园服务 + 校园卡余额功能区。
 *
 * 入口、顺序和卡片内容来自已激活插件的 UI 贡献；宿主只决定它们放在首页的
 * 哪个位置。现有文案与顺序不变，插件被移除时对应入口/功能区自然消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    serviceEntries: List<PluginEntry>,
    cardSections: List<PluginSection>,
    sectionHost: PluginSectionHost,
    onOpenRoute: (PluginRouteId) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("校园服务") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = DOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "空闲教室、图书馆座位、唐岛湾电费与校园卡余额都在 App 内查询；" +
                    "校园卡余额按当前统一账号中的本人学号查询，不需要登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (serviceEntries.isNotEmpty()) {
                SectionTitle("校园服务")
                serviceEntries.forEach { entry ->
                    ServiceEntry(
                        title = entry.title,
                        subtitle = entry.subtitle,
                        onClick = { entry.route?.let(onOpenRoute) },
                    )
                }
            }

            if (cardSections.isNotEmpty()) {
                SectionTitle("校园卡余额")
                cardSections.forEach { it.content(sectionHost) }
            }

            Text(
                text = "入口地址与开放条件会随学校调整；以学校官网通知为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun ServiceEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
