package com.upcpocket.app.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * 「设置 → 图书馆登录」中的学校会话区。
 *
 * 独立设置页只保留开关、当前会话与退出动作；完整的 token/Keystore/Cookie 说明
 * 收进可展开的“数据说明”，不因视觉简化删除隐私与数据归属信息。
 */
@Composable
internal fun LibraryAccountSection(service: LibraryService) {
    val rememberLogin by service.rememberLogin.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val librarySession by service.repository.session.collectAsStateWithLifecycle()
    var showDetails by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text("记住学校登录") },
        supportingContent = {
            Text(
                "只保存图书馆登录，不保存账号密码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(
                checked = rememberLogin,
                onCheckedChange = { enabled ->
                    scope.launch { service.setRememberLogin(enabled) }
                },
            )
        },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "图书馆：" + (
                librarySession?.let { "已登录（${it.user.name.ifBlank { "已登录" }}）" }
                    ?: "未登录"
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "收起" else "说明")
        }
    }

    Column(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (librarySession != null) {
            TextButton(
                onClick = { scope.launch { service.setRememberLogin(false) } },
            ) { Text("退出图书馆登录") }
        }

        AnimatedVisibility(visible = showDetails) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "开启后会加密保存登录状态；关闭或退出会清除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
