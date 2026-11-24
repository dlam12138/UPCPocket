package com.upcpocket.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.upcpocket.app.ui.components.GlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.upcpocket.app.ui.components.GlassSurface as Surface
import androidx.compose.material3.Text
import com.upcpocket.app.ui.components.GlassTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.summaryText
import com.upcpocket.app.data.prefs.PreferredCampus
import com.upcpocket.app.data.prefs.TodoHostPlacement
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.CampusSwitch
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.IconBadge
import com.upcpocket.app.ui.components.SectionHeader

/**
 * 全应用唯一设置入口；不是每项功能唯一快捷入口（校园网页、学习统计仍有各自的快捷路径）。
 *
 * 账号、图书馆登录、桌面组件、官方网页、外观和关于都在这里；不再维护“更多”
 * 页的第二套目录。插件只贡献图书馆登录区，宿主不硬编码图书馆实现。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    accountState: SchoolAccountState,
    schoolSections: List<PluginSection>,
    sectionHost: PluginSectionHost,
    preferredCampus: PreferredCampus? = null,
    onPreferredCampusChange: (PreferredCampus) -> Unit = {},
    todoHostPlacement: TodoHostPlacement? = null,
    showTodoHostPlacement: Boolean = false,
    onTodoHostPlacementChange: (TodoHostPlacement) -> Unit = {},
    moduleReorderEnabled: Boolean? = null,
    onModuleReorderEnabledChange: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenWidgets: () -> Unit,
    onOpenOfficialWeb: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(
        containerColor = com.upcpocket.app.ui.theme.appScaffoldContainerColor(),
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
                title = { Text("设置") },
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
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccountCard(
                state = accountState,
                onClick = onOpenAccount,
            )

            SectionHeader(
                title = "校园",
                modifier = Modifier.padding(top = 14.dp, bottom = 0.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PreferredCampusCard(
                    preferredCampus = preferredCampus,
                    onChange = onPreferredCampusChange,
                )
                schoolSections.forEach { section ->
                    androidx.compose.runtime.key(section.entryId) {
                        section.content(sectionHost)
                    }
                }
                GlassCard(Modifier.fillMaxWidth()) {
                    Column {
                        SettingsNavigationRow(
                            title = "桌面组件",
                            icon = Icons.Outlined.Widgets,
                            onClick = onOpenWidgets,
                        )
                        SettingsDivider()
                        SettingsNavigationRow(
                            title = "校园官方网页",
                            icon = Icons.Outlined.Public,
                            onClick = onOpenOfficialWeb,
                        )
                    }
                }
            }

            SectionHeader(
                title = "应用",
                modifier = Modifier.padding(top = 14.dp, bottom = 0.dp),
            )
            if (showTodoHostPlacement) {
                TodoHostPlacementCard(
                    todoHostPlacement = todoHostPlacement,
                    onChange = onTodoHostPlacementChange,
                )
            }
            ModuleReorderCard(
                moduleReorderEnabled = moduleReorderEnabled,
                onChange = onModuleReorderEnabledChange,
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    SettingsNavigationRow(
                        title = "外观",
                        icon = Icons.Filled.Palette,
                        onClick = onOpenAppearance,
                    )
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = "关于 UPC Pocket",
                        icon = Icons.Filled.Info,
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }
}

/**
 * 常用校区只保存本机默认展示范围，不触发任何校园请求。
 *
 * [preferredCampus] 为 null 表示 DataStore 尚未读完：此时禁用选项，不用默认的
 * “全部校区”冒充真实偏好。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferredCampusCard(
    preferredCampus: PreferredCampus?,
    onChange: (PreferredCampus) -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("常用校区", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PreferredCampus.entries.forEach { campus ->
                    CampusFilterChip(
                        selected = preferredCampus == campus,
                        onClick = { if (preferredCampus != campus) onChange(campus) },
                        enabled = preferredCampus != null,
                        label = { Text(campus.label) },
                    )
                }
            }
            Text(
                text = "功能页默认打开所选校区，页面内可临时切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 日程待办位置只保存在本机，决定宿主把同一份待办渲染在课表页还是专注页。
 *
 * [todoHostPlacement] 为 null 表示 DataStore 尚未读完：此时禁用选项，不用默认的
 * “课表页”冒充真实偏好。两个宿主或待办能力不可用时宿主不展示本卡。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodoHostPlacementCard(
    todoHostPlacement: TodoHostPlacement?,
    onChange: (TodoHostPlacement) -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("日程待办位置", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TodoHostPlacement.entries.forEach { placement ->
                    CampusFilterChip(
                        selected = todoHostPlacement == placement,
                        onClick = { if (todoHostPlacement != placement) onChange(placement) },
                        enabled = todoHostPlacement != null,
                        label = { Text(placement.label) },
                    )
                }
            }
        }
    }
}

/**
 * 首页与课表卡片的长按拖动开关。
 *
 * [moduleReorderEnabled] 为 null 表示 DataStore 尚未读完：此时禁用开关，不用默认的
 * 关闭状态冒充真实偏好。整行是一个开关语义节点，内层 [CampusSwitch] 只做展示。
 */
@Composable
private fun ModuleReorderCard(
    moduleReorderEnabled: Boolean?,
    onChange: (Boolean) -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = moduleReorderEnabled == true,
                    enabled = moduleReorderEnabled != null,
                    role = Role.Switch,
                    onValueChange = onChange,
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("允许拖动卡片", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "长按拖动首页和课表的卡片。关闭后锁定当前顺序。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            CampusSwitch(
                checked = moduleReorderEnabled == true,
                onCheckedChange = null,
                enabled = moduleReorderEnabled != null,
            )
        }
    }
}

@Composable
private fun AccountCard(
    state: SchoolAccountState,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = Icons.Filled.AccountCircle,
                contentDescription = null,
                size = 38.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("学校账号", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = state.summaryText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.cleanupRequired || state.errorCode != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = if (state.cleanupRequired || state.errorCode != null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon = icon, contentDescription = null, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 66.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}
