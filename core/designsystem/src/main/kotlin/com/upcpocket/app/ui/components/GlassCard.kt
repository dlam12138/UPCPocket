package com.upcpocket.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.upcpocket.app.ui.liquid.GlassCardClipShape
import com.upcpocket.app.ui.liquid.GlassCardShape
import com.upcpocket.app.ui.liquid.LiquidInteractiveHighlight
import com.upcpocket.app.ui.liquid.isLiquidGlassActive
import com.upcpocket.app.ui.liquid.liquidGlassSurface

/**
 * 全应用统一的功能容器。
 *
 * - Liquid Glass 模式：调用 Kyant0 AndroidLiquidGlass 的 `drawBackdrop`（实时模糊、
 *   API 33+ 边缘折射、高光描边与半透明表面着色）。玻璃渲染置于独立底层兄弟节点，
 *   不干扰容器自身或内部子组件的指针点击与测试语义；若传入 `onClick`，则同步激活
 *   AGSL 指尖高光与轻弹跟手反馈。
 * - Material 3 模式：保持 M5 的近实底、细描边、零高度洁净卡片。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (isLiquidGlassActive()) {
        val animationScope = rememberCoroutineScope()
        val highlight = remember(animationScope, onClick != null) {
            if (onClick != null) LiquidInteractiveHighlight(animationScope) else null
        }
        val interactionSource = remember { MutableInteractionSource() }
        val cardModifier = if (onClick != null && highlight != null) {
            modifier
                .graphicsLayer {
                    if (!enabled) return@graphicsLayer
                    val s = lerp(1f, 0.978f, highlight.pressProgress)
                    scaleX = s
                    scaleY = s
                }
                .clip(GlassCardClipShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .then(if (enabled) highlight.gestureModifier else Modifier)
        } else {
            modifier
        }
        Box(modifier = cardModifier) {
            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    .liquidGlassSurface(
                        shape = GlassCardShape,
                        blurRadius = 4.dp,
                        refractionHeight = 10.dp,
                        refractionAmount = 20.dp,
                        shadowElevation = 6.dp,
                    )
                    .then(if (enabled && highlight != null) highlight.modifier else Modifier),
            )
            Box(modifier = Modifier.clip(GlassCardClipShape)) {
                content()
            }
        }
    } else if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
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
    } else {
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
}
