package com.upcpocket.app.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import kotlinx.coroutines.launch

/**
 * 设置里的图书馆登录入口。折叠行只显示当前会话状态；展开只改变本地 UI 状态，
 * 不触发认证、切换记住登录选项或清理 Cookie。
 */
@Composable
internal fun LibraryAccountSection(service: LibraryService) {
    val rememberLogin by service.rememberLogin.collectAsStateWithLifecycle(initialValue = false)
    val librarySession by service.repository.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LibraryAccountSectionContent(
        rememberLogin = rememberLogin,
        hasSession = librarySession != null,
        onRememberLoginChange = { enabled ->
            scope.launch { service.setRememberLogin(enabled) }
        },
        onLogout = {
            scope.launch { service.setRememberLogin(false) }
        },
    )
}

@Composable
internal fun LibraryAccountSectionContent(
    rememberLogin: Boolean,
    hasSession: Boolean,
    onRememberLoginChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sessionStatus = if (hasSession) "已登录" else "未登录"

    GlassCard(Modifier.fillMaxWidth().testTag("library_account_card")) {
        Column {
            Surface(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().testTag("library_account_toggle"),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconBadge(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        size = 38.dp,
                    )
                    Text(
                        text = "图书馆登录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = sessionStatus,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.ExpandLess
                        } else {
                            Icons.Filled.ExpandMore
                        },
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(120)),
                exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(100)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library_account_details"),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 62.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(start = 14.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("记住学校登录", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "只保存图书馆登录，不保存账号密码。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = rememberLogin,
                            onCheckedChange = onRememberLoginChange,
                            modifier = Modifier.testTag("library_remember_switch"),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "当前会话",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            sessionStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("library_session_status"),
                        )
                    }
                    if (hasSession) {
                        TextButton(
                            onClick = onLogout,
                            modifier = Modifier.testTag("library_logout"),
                        ) {
                            Text("退出图书馆登录")
                        }
                    }

                    Column(
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 2.dp,
                            bottom = 12.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "开启后会加密保存登录状态；关闭或退出会清除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("library_account_explanation"),
                        )
                        Text(
                            "此开关只影响图书馆，不影响学校账号或校园卡。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
