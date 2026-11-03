package com.upcpocket.app.library

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * 「更多 → 学校登录」中的图书馆会话区。
 *
 * 文案、开关与退出行为沿用迁移前的设置页；插件只通过 [LibraryService] 读写状态，
 * 不直接拿宿主的 DataStore 或 AppGraph。关闭开关和退出按钮都会清理加密副本、
 * 内存 token 与学校业务 Cookie。
 */
@Composable
internal fun LibraryAccountSection(service: LibraryService) {
    val rememberLogin by service.rememberLogin.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val librarySession by service.repository.session.collectAsStateWithLifecycle()

    ListItem(
        headlineContent = { Text("记住学校登录") },
        supportingContent = {
            Text(
                "目前只有图书馆座位会话需要登录：开启后 token 用 Android Keystore 加密保存" +
                    "（不随系统备份）；关闭或退出会清理持久副本与 UPC 业务 Cookie。" +
                    "校园卡余额不需要登录。",
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
    Text(
        "图书馆会话：" + (
            librarySession?.let { "内存中（${it.user.name.ifBlank { "已登录" }}）" }
                ?: "未登录（未保存时进程结束即需重登）"
            ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    TextButton(
        onClick = { scope.launch { service.setRememberLogin(false) } },
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text("退出学校登录（清理图书馆会话与学校 Cookie）") }
}
