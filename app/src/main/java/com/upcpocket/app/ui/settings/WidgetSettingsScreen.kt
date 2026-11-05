package com.upcpocket.app.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.widget.CardBalanceWidgetProvider
import com.upcpocket.app.widget.PowerWidgetProvider
import com.upcpocket.app.widget.TimetableWidgetProvider

/**
 * 「设置 → 桌面组件」。
 *
 * 2×2 RemoteViews 组件仍由系统桌面承载，数据、缓存和刷新 Worker 不变。本页只
 * 说明边界，并在桌面支持 `requestPinAppWidget` 时提供添加入口；不会伪造组件
 * 预览数值，也不会替用户自动放置组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
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
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
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
                text = "三个组件默认 2×2，课表可拉到 4×2；点击组件刷新。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            WidgetCard(
                title = "校园卡余额",
                description = "余额与更新时间；换号后旧值会清除。",
                icon = Icons.Filled.CreditCard,
                supported = supported,
                onPin = {
                    pin(
                        ComponentName(context, CardBalanceWidgetProvider::class.java),
                    )
                },
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
            )
            WidgetCard(
                title = "今日课表",
                description = "下一堂或正在上的课；未同步时明确提示。",
                icon = Icons.Outlined.CalendarMonth,
                supported = supported,
                sizeLabel = "2×2 · 可拉到 4×2",
                onPin = {
                    pin(
                        ComponentName(context, TimetableWidgetProvider::class.java),
                    )
                },
            )

            Text(
                text = if (supported) {
                    "没有弹出面板时，可长按桌面添加。"
                } else {
                    "请长按桌面，在小组件中添加。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
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
            Button(
                onClick = onPin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (supported) "添加到桌面" else "查看添加方法")
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
