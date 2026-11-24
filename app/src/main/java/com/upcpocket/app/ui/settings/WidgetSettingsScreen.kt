package com.upcpocket.app.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.upcpocket.app.ui.components.CampusIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.upcpocket.app.ui.components.GlassRadioButton as RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.widget.CardBalanceWidgetProvider
import com.upcpocket.app.widget.CardBalanceGlassWidgetProvider
import com.upcpocket.app.widget.PowerWidgetProvider
import com.upcpocket.app.widget.PowerGlassWidgetProvider
import com.upcpocket.app.widget.TimetableWidgetProvider
import com.upcpocket.app.widget.TimetableGlassWidgetProvider
import com.upcpocket.app.widget.TimetableTodayTomorrowWidgetProvider
import com.upcpocket.app.widget.TimetableWeekWidgetProvider
import com.upcpocket.app.widget.TodoWidgetProvider
import com.upcpocket.app.widget.TodoGlassWidgetProvider
import kotlinx.coroutines.launch

/**
 * 「设置 → 桌面组件」。
 *
 * RemoteViews 组件仍由系统桌面承载，数据、缓存和刷新 Worker 不变。本页只
 * 说明边界，并在桌面支持 `requestPinAppWidget` 时提供添加入口；不会伪造组件
 * 预览数值，也不会替用户自动放置组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())
    val scope = rememberCoroutineScope()
    val manager = remember { AppWidgetManager.getInstance(context) }
    val supported = remember(manager) { manager.isRequestPinAppWidgetSupported }

    fun pin(provider: ComponentName) {
        if (!supported) {
            Toast.makeText(
                context,
                "请长按桌面添加组件。",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val requested = runCatching {
            manager.requestPinAppWidget(provider, null, null)
        }.getOrDefault(false)
        Toast.makeText(
            context,
            if (requested) "请在系统面板确认" else "请长按桌面添加。",
            Toast.LENGTH_LONG,
        ).show()
    }

    Scaffold(
        containerColor = com.upcpocket.app.ui.theme.appScaffoldContainerColor(),
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(
                title = { Text("桌面组件") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "点击组件打开功能页。课表和校园卡自动更新；电量可点右上角刷新。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("组件外观", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "标准版使用这里的背景；玻璃版固定为静态玻璃。桌面效果取决于系统桌面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WidgetAppearanceOption(
                        title = "近实底",
                        description = "默认外观，背景遮挡更充分。",
                        appearance = WidgetAppearance.SOLID,
                        selected = prefs.widgetAppearance == WidgetAppearance.SOLID,
                    ) {
                        scope.launch { AppGraph.uiPrefsStore.setWidgetAppearance(WidgetAppearance.SOLID) }
                    }
                    WidgetAppearanceOption(
                        title = "通透",
                        description = "轻度透出桌面背景。",
                        appearance = WidgetAppearance.TRANSLUCENT,
                        selected = prefs.widgetAppearance == WidgetAppearance.TRANSLUCENT,
                    ) {
                        scope.launch { AppGraph.uiPrefsStore.setWidgetAppearance(WidgetAppearance.TRANSLUCENT) }
                    }
                    WidgetAppearanceOption(
                        title = "静态玻璃",
                        description = "半透明底色与静态高光；不读取壁纸或捕获屏幕。",
                        appearance = WidgetAppearance.GLASS,
                        selected = prefs.widgetAppearance == WidgetAppearance.GLASS,
                    ) {
                        scope.launch { AppGraph.uiPrefsStore.setWidgetAppearance(WidgetAppearance.GLASS) }
                    }
                }
            }

            WidgetCard(
                title = "校园卡余额",
                description = "自动更新总余额，下方显示过渡余额；换号后旧值会清除。",
                icon = Icons.Filled.CreditCard,
                supported = supported,
                onPin = {
                    pin(
                        ComponentName(context, CardBalanceWidgetProvider::class.java),
                    )
                },
                onPinGlass = { pin(ComponentName(context, CardBalanceGlassWidgetProvider::class.java)) },
            )
            WidgetCard(
                title = "唐岛湾电量",
                description = "楼号、房间与剩余电量。",
                icon = Icons.Filled.Bolt,
                supported = supported,
                onPin = {
                    pin(
                        ComponentName(context, PowerWidgetProvider::class.java),
                    )
                },
                onPinGlass = { pin(ComponentName(context, PowerGlassWidgetProvider::class.java)) },
            )
            WidgetCard(
                title = "今日课表",
                description = "同步一次后，随上下课和日期自动切换；未同步时明确提示。",
                icon = Icons.Outlined.CalendarMonth,
                supported = supported,
                sizeLabel = "2×2 · 可拉到 4×2",
                onPin = {
                    pin(
                        ComponentName(context, TimetableWidgetProvider::class.java),
                    )
                },
                onPinGlass = { pin(ComponentName(context, TimetableGlassWidgetProvider::class.java)) },
            )
            WidgetCard(
                title = "今明课表",
                description = "两列显示今天与明天已同步课程的节次和名称；缺日、冲突与学期外分别标注。",
                icon = Icons.Outlined.CalendarMonth,
                supported = supported,
                sizeLabel = "4×2 · 可缩放",
                onPin = {
                    pin(
                        ComponentName(context, TimetableTodayTomorrowWidgetProvider::class.java),
                    )
                },
            )
            WidgetCard(
                title = "本周课表",
                description = "周日至周六的已同步课程；缺日、冲突与学期外分别标注。",
                icon = Icons.Outlined.CalendarMonth,
                supported = supported,
                sizeLabel = "4×4 · 可缩放",
                onPin = {
                    pin(
                        ComponentName(context, TimetableWeekWidgetProvider::class.java),
                    )
                },
            )
            WidgetCard(
                title = "待办清单",
                description = "显示本机未完成待办；可在桌面完成事项或新建。",
                icon = Icons.Outlined.CheckBox,
                supported = supported,
                sizeLabel = "2×2 · 可拉伸",
                onPin = {
                    pin(ComponentName(context, TodoWidgetProvider::class.java))
                },
                onPinGlass = { pin(ComponentName(context, TodoGlassWidgetProvider::class.java)) },
            )

            Text(
                text = "桌面小组件列表中也可选择标有“玻璃版”的组件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = if (supported) {
                    "没有弹出面板时，长按桌面空白处 → 小组件 → UPC Pocket。"
                } else {
                    "长按桌面空白处 → 小组件 → UPC Pocket。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "自动更新可能因系统省电而延迟。课表只切换已同步的课程，未缓存时需打开应用同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WidgetAppearanceOption(
    title: String,
    description: String,
    appearance: WidgetAppearance,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WidgetAppearancePreview(appearance)
    }
}

@Composable
private fun WidgetAppearancePreview(appearance: WidgetAppearance) {
    val surfaceColor = colorResource(
        when (appearance) {
            WidgetAppearance.SOLID -> DesignSystemR.color.widget_surface
            WidgetAppearance.TRANSLUCENT -> DesignSystemR.color.widget_surface_translucent
            WidgetAppearance.GLASS,
            WidgetAppearance.FROSTED_GLASS -> DesignSystemR.color.widget_surface_glass
        },
    )
    val glassDrawable = when (appearance) {
        WidgetAppearance.GLASS -> DesignSystemR.drawable.widget_surface_glass
        WidgetAppearance.FROSTED_GLASS -> DesignSystemR.drawable.widget_surface_frosted_glass
        else -> null
    }
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.size(width = 88.dp, height = 56.dp),
    ) {
        if (glassDrawable != null) {
            AndroidView(
                factory = { context ->
                    View(context).apply {
                        background = context.getDrawable(glassDrawable)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(surfaceColor, shape))
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "今日课表",
                color = colorResource(DesignSystemR.color.widget_primary),
                fontSize = 8.sp,
                lineHeight = 9.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 2.dp, height = 21.dp)
                        .background(colorResource(DesignSystemR.color.widget_accent)),
                )
                Column(Modifier.padding(start = 5.dp)) {
                    Text(
                        "上课中 · 09:55",
                        color = colorResource(DesignSystemR.color.widget_secondary),
                        fontSize = 6.sp,
                        lineHeight = 7.sp,
                    )
                    Text(
                        "计算机组成原理",
                        color = colorResource(DesignSystemR.color.widget_primary),
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(
    title: String,
    description: String,
    icon: ImageVector,
    supported: Boolean,
    sizeLabel: String = "2×2",
    onPin: () -> Unit,
    onPinGlass: (() -> Unit)? = null,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WidgetPreview(icon)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        sizeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            com.upcpocket.app.ui.components.CampusButton(
                onClick = onPin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (supported) "添加到桌面" else "查看添加方法")
            }
            onPinGlass?.let { pinGlass ->
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = pinGlass,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (supported) "添加玻璃版" else "桌面列表中添加玻璃版")
                }
            }
        }
    }
}

@Composable
private fun WidgetPreview(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(28.dp),
        )
    }
}
