package com.upcpocket.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 当前宿主是否已启用自定义背景图。
 *
 * 仅在背景图有无变化时切换，避免调节背景可见度滑块时触发页面 Scaffold 整树重组。
 */
val LocalHasCustomBackground = staticCompositionLocalOf { false }

/**
 * 页面级 Scaffold / 根容器背景色。
 *
 * 未设置自定义背景图时返回主题 surface；启用自定义背景图后返回透明，
 * 让宿主外层绘制的背景图与可读性遮罩自然透出。
 */
@Composable
fun appScaffoldContainerColor(
    fallback: Color = MaterialTheme.colorScheme.surface,
): Color = if (LocalHasCustomBackground.current) Color.Transparent else fallback
