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
import androidx.compose.material.icons.filled.MeetingRoom
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
import com.upcpocket.app.data.upc.UpcOfficialEntryLinks
import com.upcpocket.app.library.LibraryService
import com.upcpocket.app.library.NspaceService
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.SectionHeader
import com.upcpocket.app.ui.components.StatusPill

private enum class LibraryAccess {
    NATIVE,
    NATIVE_READ_ONLY,
    OFFICIAL_LOGIN,
    BLOCKED,
}

private data class LibraryPlatform(
    val title: String,
    val subtitle: String,
    val access: LibraryAccess,
    val icon: ImageVector,
    val actionLabel: String?,
    val action: (() -> Unit)?,
    val note: String,
)

/**
 * 图书馆统一入口。
 *
 * 六个平台仍是独立业务/会话；宿主只负责分组呈现，不把 libspace 与 Chaoxing
 * 的 token、Cookie 或签名混在一起。无法原生接入的入口明确标注，而不是伪装成
 * “已支持”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHubScreen(
    libraryService: LibraryService,
    nspaceService: NspaceService,
    onBack: () -> Unit,
    onOpenSeat: () -> Unit,
    onOpenNspace: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val context = LocalContext.current
    val accountState by libraryService.accountState.collectAsStateWithLifecycle()
    val seatSession by libraryService.repository.session.collectAsStateWithLifecycle()
    val nspaceSession by nspaceService.session.collectAsStateWithLifecycle()

    val platforms = listOf(
        LibraryPlatform(
            title = "座位",
            subtitle = "唐岛湾 · libseat",
            access = LibraryAccess.NATIVE,
            icon = Icons.Filled.EventSeat,
            actionLabel = if (seatSession == null) "登录并查询" else "查询/预约",
            action = onOpenSeat,
            note = "原生查询、预约与取消；沿用既有 libseat 会话与账号保护。",
        ),
        LibraryPlatform(
            title = "东馆研讨间",
            subtitle = "唐岛湾 · nspace",
            access = LibraryAccess.NATIVE_READ_ONLY,
            icon = Icons.Filled.MeetingRoom,
            actionLabel = "浏览房间",
            action = onOpenNspace,
            note = "原生只读馆区/空间类型/日期/房间；预约在官方页面完成。",
        ),
        LibraryPlatform(
            title = "东区座位",
            subtitle = "古镇口 · Chaoxing seat",
            access = LibraryAccess.OFFICIAL_LOGIN,
            icon = Icons.Filled.EventSeat,
            actionLabel = "在浏览器打开官方登录",
            action = {
                openOfficial(context, UpcOfficialEntryLinks.SEAT)
            },
            note = "使用稳定的学校/应用入口；仍需正常登录。未取得馆区/座位图，未做原生查询。",
        ),
        LibraryPlatform(
            title = "东区研讨间",
            subtitle = "古镇口 · reservepc",
            access = LibraryAccess.OFFICIAL_LOGIN,
            icon = Icons.Filled.MeetingRoom,
            actionLabel = "在浏览器打开官方登录",
            action = {
                openOfficial(context, UpcOfficialEntryLinks.RESERVEPC_A)
            },
            note = "已从 HAR 提取稳定 reserveId/resvAppId/fidEnc/appId/mappId 入口，" +
                "不含 uid/enc/code/state；需正常登录后查看房间，未做原生查询/预约。",
        ),
        LibraryPlatform(
            title = "求是座位",
            subtitle = "求是 · Chaoxing seatengine",
            access = LibraryAccess.OFFICIAL_LOGIN,
            icon = Icons.Filled.EventSeat,
            actionLabel = "在浏览器打开官方登录",
            action = {
                openOfficial(context, UpcOfficialEntryLinks.SEATENGINE)
            },
            note = "使用稳定的学校/应用/seatId 入口；仍需正常登录。缺座位图，未做原生查询。",
        ),
        LibraryPlatform(
            title = "求是研讨间",
            subtitle = "求是 · reservepc",
            access = LibraryAccess.OFFICIAL_LOGIN,
            icon = Icons.Filled.MeetingRoom,
            actionLabel = "在浏览器打开官方登录",
            action = {
                openOfficial(context, UpcOfficialEntryLinks.RESERVEPC_B)
            },
            note = "已从 HAR 提取稳定的求是租户入口；需正常登录后查看房间，" +
                "未做原生查询/预约。",
        ),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("图书馆")
                        Text(
                            "座位与研讨间 · 统一入口",
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
                        "唐岛湾、古镇口、求是的座位与研讨间集中在这里；各平台仍使用独立登录和会话。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "账号：" + accountStatusLabel(accountState.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "libseat 会话：" + (seatSession?.user?.name?.ifBlank { "已登录" } ?: "未登录") +
                            " · nspace 会话：" +
                            (nspaceSession?.user?.name?.ifBlank { "已登录" } ?: "未登录"),
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
            platforms.take(2).forEach { platform ->
                PlatformCard(platform)
            }
            SectionHeader("古镇口")
            platforms.slice(2..3).forEach { platform ->
                PlatformCard(platform)
            }
            SectionHeader("求是")
            platforms.slice(4..5).forEach { platform ->
                PlatformCard(platform)
            }

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
                "“原生只读”表示已实现协议解析，但没有实测写操作；“官方登录”只保证打开平台官方页面，" +
                    "不保证登录后一定有可用租户。未接入的平台不会被说成已支持。",
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

private fun openOfficial(context: android.content.Context, url: String) {    if (!url.startsWith("https://office.chaoxing.com/")) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun PlatformCard(platform: LibraryPlatform) {    val (pillText, pillColor, pillContent) = when (platform.access) {
        LibraryAccess.NATIVE -> Triple(
            "原生",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        LibraryAccess.NATIVE_READ_ONLY -> Triple(
            "原生只读",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        LibraryAccess.OFFICIAL_LOGIN -> Triple(
            "官方登录",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        LibraryAccess.BLOCKED -> Triple(
            "待入口",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Surface(
        onClick = { platform.action?.invoke() },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        enabled = platform.action != null,
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
                    StatusPill(pillText, pillColor, pillContent)
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
                platform.actionLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
