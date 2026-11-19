package com.upcpocket.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.library.AllLibraryCampusPreferenceProvider
import com.upcpocket.app.data.library.LibraryCampus
import com.upcpocket.app.data.library.LibraryCampusPreference
import com.upcpocket.app.data.library.LibraryCampusPreferenceProvider
import com.upcpocket.app.library.ChaoxingCleanupState
import com.upcpocket.app.library.ChaoxingService
import com.upcpocket.app.library.LibraryService
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.SectionHeader

private data class LibraryPlatform(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val actionLabel: String,
    val campus: LibraryCampus,
    val action: () -> Unit,
)

/**
 * 图书馆统一入口：按常用校区展示座位入口。
 *
 * 唐岛湾 libseat 与 Chaoxing seat/seatengine 仍是独立会话；研讨间/空间预约已撤下，
 * 这里不提供入口、房间数据或预约表单。
 *
 * 常用校区为“全部校区”时保留三个入口；选择单一校区时只展示该校区入口，并允许
 * 在页面内临时切换。常用校区未读取完成前不按默认范围裁剪，也不先渲染错误校区。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryHubScreen(
    libraryService: LibraryService,
    chaoxingService: ChaoxingService,
    onBack: () -> Unit,
    onOpenSeat: () -> Unit,
    onOpenChaoxingSeat: (ChaoxingSeatKind) -> Unit,
    onOpenAccount: () -> Unit,
    preferredCampus: LibraryCampusPreferenceProvider = AllLibraryCampusPreferenceProvider,
) {
    val context = LocalContext.current
    val accountState by libraryService.accountState.collectAsStateWithLifecycle()
    val seatSession by libraryService.repository.session.collectAsStateWithLifecycle()
    val chaoxingSession by chaoxingService.session.collectAsStateWithLifecycle()
    val chaoxingCleanup by chaoxingService.cleanupState.collectAsStateWithLifecycle()
    val campusPreferenceFlow = remember(preferredCampus) { preferredCampus.preferredCampus() }
    val campusPreference by campusPreferenceFlow
        .collectAsStateWithLifecycle(initialValue = LibraryCampusPreference.Unread)

    var campusOverrideName by rememberSaveable { mutableStateOf<String?>(null) }
    val campusOverride = campusOverrideName?.let { name ->
        LibraryCampus.entries.firstOrNull { it.name == name }
    }
    val lockedCampus = (campusPreference as? LibraryCampusPreference.Selected)?.campus
    val effectiveCampus = campusOverride ?: lockedCampus

    val platforms = listOf(
        LibraryPlatform(
            title = "座位",
            subtitle = "查询、预约与取消",
            icon = Icons.Filled.EventSeat,
            actionLabel = if (seatSession == null) "登录并查询" else "查询/预约",
            campus = LibraryCampus.TANGDAOWAN,
            action = onOpenSeat,
        ),
        LibraryPlatform(
            title = "东区座位",
            subtitle = "选座与按时段找",
            icon = Icons.Filled.EventSeat,
            actionLabel = "查看座位",
            campus = LibraryCampus.GUZHENKOU,
            action = { onOpenChaoxingSeat(ChaoxingSeatKind.SEAT) },
        ),
        LibraryPlatform(
            title = "求是座位",
            subtitle = "选座与按时段找",
            icon = Icons.Filled.EventSeat,
            actionLabel = "查看座位",
            campus = LibraryCampus.GUZHENKOU,
            action = { onOpenChaoxingSeat(ChaoxingSeatKind.SEATENGINE) },
        ),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
                title = { Text("图书馆") },
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
                        "账号：" + accountStatusLabel(accountState.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "唐岛湾：" + (if (seatSession != null) "已登录" else "未登录") +
                            " · 古镇口/求是：" + when {
                                chaoxingCleanup == ChaoxingCleanupState.FAILED -> "清理失败"
                                chaoxingCleanup == ChaoxingCleanupState.CLEARING -> "清理中"
                                chaoxingSession != null -> "已登录"
                                else -> "未登录"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (accountState.status != SchoolAccountStatus.SAVED_UNVERIFIED) {
                        com.upcpocket.app.ui.components.CampusTextButton(onClick = onOpenAccount) {
                            Text("设置学校账号")
                        }
                    }
                }
            }

            when {
                campusPreference is LibraryCampusPreference.Unread -> CampusPreferenceLoading()
                effectiveCampus == null -> {
                    SectionHeader("唐岛湾")
                    PlatformCard(platforms[0])
                    SectionHeader("古镇口")
                    PlatformCard(platforms[1])
                    SectionHeader("求是")
                    PlatformCard(platforms[2])
                }
                effectiveCampus == LibraryCampus.TANGDAOWAN -> {
                    CampusSwitchRow(effectiveCampus) { campusOverrideName = it.name }
                    SectionHeader("唐岛湾")
                    PlatformCard(platforms[0])
                }
                else -> {
                    CampusSwitchRow(effectiveCampus) { campusOverrideName = it.name }
                    SectionHeader("古镇口")
                    PlatformCard(platforms[1])
                    SectionHeader("求是")
                    PlatformCard(platforms[2])
                }
            }

            com.upcpocket.app.ui.components.CampusTextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://library.upc.edu.cn/")),
                    )
                }
            }) {
                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.padding(start = 4.dp))
                Text("打开图书馆官网")
            }
            Text(
                "选择座位与时段，确认预约。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun CampusPreferenceLoading() {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                "正在读取常用校区…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampusSwitchRow(
    selected: LibraryCampus,
    onSelect: (LibraryCampus) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LibraryCampus.entries.forEach { campus ->
            CampusFilterChip(
                selected = selected == campus,
                onClick = { if (selected != campus) onSelect(campus) },
                label = { Text(campus.label) },
            )
        }
    }
}

private fun accountStatusLabel(status: SchoolAccountStatus): String = when (status) {
    SchoolAccountStatus.LOADING -> "读取中"
    SchoolAccountStatus.NOT_CONFIGURED -> "未设置"
    SchoolAccountStatus.SAVED_UNVERIFIED -> "已保存，尚未验证"
    SchoolAccountStatus.NEEDS_RECONFIGURATION -> "需要重新保存"
    SchoolAccountStatus.SWITCHING -> "切换中"
    SchoolAccountStatus.UPDATING -> "更新中"
    SchoolAccountStatus.REMOVING -> "清理中"
}

@Composable
private fun PlatformCard(platform: LibraryPlatform) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = platform.action,
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
                Text(
                    platform.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    platform.subtitle,
                    style = MaterialTheme.typography.bodySmall,
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
