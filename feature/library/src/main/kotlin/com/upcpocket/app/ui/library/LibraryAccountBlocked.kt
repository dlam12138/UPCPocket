package com.upcpocket.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus

/**
 * 统一账号恢复/清理期间的个人业务门闩界面。
 *
 * 这不是唯一防线：LibraryService 与 LibrarySeatRepository 还会在捕获上下文、
 * 恢复 token、发布会话和每次带 token 请求时检查同一状态。
 */
@Composable
internal fun LibraryAccountBlocked(
    state: SchoolAccountState,
    onBack: () -> Unit,
) {
    val loading = state.status == SchoolAccountStatus.LOADING
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator()
            Text("正在读取学校账号状态…", style = MaterialTheme.typography.titleMedium)
        } else {
            Text("学校账号正在清理", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = if (loading) {
                "账号状态完成读取前，图书馆不会恢复旧会话或发起新登录。"
            } else {
                "换号或删号的会话与 Cookie 清理尚未完成。完成前不会恢复旧图书馆会话，" +
                    "也不会接受新的学校登录；清理失败可回到“学校账号”重试。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack) { Text("返回") }
    }
}
