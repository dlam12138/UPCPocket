package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.library.ChaoxingCleanupState
import com.upcpocket.app.library.ChaoxingService
import com.upcpocket.app.library.LibraryService
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.components.StatusPill

private data class LibraryPlatform(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val actionLabel: String,
    val action: () -> Unit,
    val note: String,
)

/**
 * 图书馆统一入口：只展示三个座位入口。
 *
 * 唐岛湾 libseat 与 Chaoxing seat/seatengine 仍是独立会话；研讨间/空间预约已撤下，
 * 这里不提供入口、房间数据或预约表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHubScreen(
    libraryService: LibraryService,
    chaoxingService: ChaoxingService,
    onBack: () -> Unit,
    onOpenSeat: () -> Unit,
    onOpenChaoxingSeat: (ChaoxingSeatKind) -> Unit,
    onOpenAccount: () -> Unit,
) {
    val context = LocalContext.current
    val accountState by libraryService.accountState.collectAsStateWithLifecycle()
    val seatSession by libraryService.repository.session.collectAsStateWithLifecycle()
    val chaoxingSession by chaoxingService.session.collectAsStateWithLifecycle()
    val chaoxingCleanup by chaoxingService.cleanupState.collectAsStateWithLifecycle()

    val platforms = listOf(
        LibraryPlatform(
            title = "座位",
            subtitle = "唐岛湾 · libseat",
            icon = Icons.Filled.EventSeat,
            actionLabel = if (seatSession == null) "登录并查询" else "查询/预约",
            action = onOpenSeat,
            note = "原生查询、预约与取消；沿用既有 libseat 会话与账号保护。",
        ),
        LibraryPlatform(
            title = "东区座位",
            subtitle = "古镇口 · Chaoxing seat",
            icon = Icons.Filled.EventSeat,
            actionLabel = "查看区域/座位/时段",
            action = { onOpenChaoxingSeat(ChaoxingSeatKind.SEAT) },
            note = "原生只读：区域、座位图、30 分钟时段与已用座位；首次需 Chaoxing 网页登录，" +
                "真实预约仍在学校页面完成。",
        ),
        LibraryPlatform(
            title = "求是座位",
            subtitle = "求是 · Chaoxing seatengine",
            icon = Icons.Filled.EventSeat,
            actionLabel = "查看区域/座位/时段",
            action = { onOpenChaoxingSeat(ChaoxingSeatKind.SEATENGINE) },
            note = "原生只读：求是区域、座位图、30 分钟时段与已用座位；首次需 Chaoxing " +
                "网页登录，真实预约仍在学校页面完成。",
        ),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("图书馆")
                        Text(
                            "座位 · 统一入口",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "唐岛湾、古镇口、求是的座位入口集中在这里；各平台仍使用独立登录和会话。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "账号：" + accountStatusLabel(accountState.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "libseat：" + (seatSession?.user?.name?.ifBlank { "已登录" } ?: "未登录") +
                            " · Chaoxing：" + when {
                                chaoxingCleanup == ChaoxingCleanupState.FAILED -> "Cookie 清理失败"
                                chaoxingCleanup == ChaoxingCleanupState.CLEARING -> "Cookie 清理中"
                                chaoxingSession != null -> "已连接"
                                else -> "未登录"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (accountState.status != SchoolAccountStatus.SAVED_UNVERIFIED) {
                        TextButton(onClick = onOpenAccount) {
                            Text("设置统一学校账号")
                        }
                    }
                }
            }

            SectionHeader("唐岛湾")
            PlatformCard(platforms[0])
            SectionHeader("古镇口")
            PlatformCard(platforms[1])
            SectionHeader("求是")
            PlatformCard(platforms[2])

            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://library.upc.edu.cn/")),
                    )
                }
            }) {
                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.padding(start = 4.dp))
                Text("打开学校图书馆官网（查找最新入口）")
            }
            Text(
                "“原生/原生只读”表示应用内调用学校接口；Chaoxing 座位首次需在应用内受限 " +
                    "官方 WebView 登录，之后只读区域/座位/时段。真实预约、选座和签到仍在学校" +
                    "页面完成，应用不代持浏览器 Cookie。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

private fun accountStatusLabel(status: SchoolAccountStatus): String = when (status) {
    SchoolAccountStatus.LOADING -> "恢复中"
    SchoolAccountStatus.NOT_CONFIGURED -> "未设置"
    SchoolAccountStatus.SAVED_UNVERIFIED -> "已保存，尚未验证"
    SchoolAccountStatus.NEEDS_RECONFIGURATION -> "需要重新保存"
    SchoolAccountStatus.SWITCHING -> "切换中，暂不可登录"
    SchoolAccountStatus.UPDATING -> "更新中，暂不可登录"
    SchoolAccountStatus.REMOVING -> "清理中，暂不可登录"
}

@Composable
private fun PlatformCard(platform: LibraryPlatform) {
    Surface(
        onClick = platform.action,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                platform.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        platform.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.padding(start = 6.dp))
                    StatusPill(
                        "原生",
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    platform.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    platform.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    platform.actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
