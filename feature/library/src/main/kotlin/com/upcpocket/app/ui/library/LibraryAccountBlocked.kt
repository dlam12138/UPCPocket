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
            Text("正在读取账号…", style = MaterialTheme.typography.titleMedium)
        } else {
            Text("正在清理账号", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = if (loading) {
                "读取完成前不能登录。"
            } else {
                "旧账号的登录信息尚未清理完成；请回到学校账号重试。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack) { Text("返回") }
    }
}
