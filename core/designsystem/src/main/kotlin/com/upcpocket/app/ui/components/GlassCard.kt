package com.upcpocket.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 全应用统一的功能容器。
 *
 * M5 去掉了半透明叠层和重阴影：卡片是近实底、细描边、零高度，信息靠留白和
 * 字体层级组织。类名保留 `GlassCard`，避免每个 feature 同时改调用点；以后若
 * 真要恢复玻璃效果，只需改这一处。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
        modifier = modifier,
    ) {
        Box(content = { content() })
    }
}
