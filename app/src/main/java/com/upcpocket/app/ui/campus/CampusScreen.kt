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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.core.SchoolWebCookies
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.nav.DOCK_CLEARANCE
import kotlinx.coroutines.launch

/**
 * 「更多」= 设置与信息入口页。
 *
 * 轻量版保留：学习统计、学校登录开关、外观、关于。
 * 课表设置的入口（教学周、节次、演示数据、显示样式）已随课表功能一起删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusScreen(
    onOpenFocusStats: () -> Unit,
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
            GroupTitle("学习")
            EntryCard {
                Entry(
                    Icons.Filled.Insights,
                    "学习统计",
                    "专注时长、热力图、标签分布",
                    onOpenFocusStats,
                )
            }

            GroupTitle("学校登录")
            EntryCard {
                val prefs by AppGraph.uiPrefsStore.flow
                    .collectAsStateWithLifecycle(initialValue = UiPrefs())
                val scope = rememberCoroutineScope()
                val librarySession by AppGraph.librarySeatRepository.session
                    .collectAsStateWithLifecycle()
                ListItem(
                    headlineContent = { Text("记住学校登录") },
                    supportingContent = {
                        Text(
                            "图书馆 token 与校园卡令牌用 Android Keystore 加密保存（不随系统备份）。" +
                                "关闭或退出会清理持久副本与 UPC 业务 Cookie。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = prefs.rememberSchoolLogin,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    AppGraph.uiPrefsStore.setRememberSchoolLogin(enabled)
                                    if (!enabled) {
                                        AppGraph.schoolSessionStore.clear()
                                        AppGraph.librarySeatRepository.clearSession()
                                        AppGraph.campusCardService.signOut()
                                        SchoolWebCookies.clearAllSchoolCookies()
                                    }
                                }
                            },
                        )
                    },
                )
                Text(
                    "图书馆会话：" + (
                        librarySession?.let { "内存中（${it.user.name.ifBlank { "已登录" }}）" }
                            ?: "未登录（未保存时进程结束即需重登）"
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    "校园卡平台会话（余额查询不需要）：" + (
                        if (AppGraph.campusCardService.hasSession()) "内存中（已登录）"
                        else "未登录"
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            AppGraph.uiPrefsStore.setRememberSchoolLogin(false)
                            AppGraph.schoolSessionStore.clear()
                            AppGraph.librarySeatRepository.clearSession()
                            AppGraph.campusCardService.signOut()
                            SchoolWebCookies.clearAllSchoolCookies()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("退出学校登录（清理图书馆与校园卡会话）") }
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
