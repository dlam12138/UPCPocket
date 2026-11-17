package com.upcpocket.app.ui.liquid

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.upcpocket.app.designsystem.UiStyle

/** 当前界面风格（Liquid Glass 或 Material 3 原生）。 */
val LocalUiStyle = compositionLocalOf { UiStyle.LIQUID_GLASS }

/** 供各级玻璃表面采集底层材质的背景 Backdrop。 */
val LocalGlassBackdrop = compositionLocalOf<Backdrop?> { null }

/** 当前主题是否处于深色背景。 */
val LocalGlassDark = compositionLocalOf { false }

val GlassCapsule: Shape = Capsule()
val GlassCapsuleClipShape: Shape = CircleShape
val GlassCardShape: Shape = RoundedRectangle(20.dp)
val GlassCardClipShape: Shape = RoundedCornerShape(20.dp)
val GlassDialogShape: Shape = RoundedRectangle(28.dp)
val GlassDialogClipShape: Shape = RoundedCornerShape(28.dp)
val GlassSheetShape: Shape = RoundedRectangle(28.dp)

@Composable
@ReadOnlyComposable
fun isLiquidGlassActive(): Boolean =
    LocalUiStyle.current == UiStyle.LIQUID_GLASS && LocalGlassBackdrop.current != null

/**
 * 为 Android 12（API 31–32）和 Android 13+（API 33+）提供一致的液态玻璃效果管道：
 * - API 31+：`vibrancy()` + `blur()` + 高光描边 + 阴影
 * - API 33+：额外开启基于 AGSL RuntimeShader 的 `lens()` 真实边缘折射与色散
 */
fun BackdropEffectScope.applyGlassEffects(
    blurRadiusDp: Dp = 12.dp,
    refractionHeightDp: Dp = 10.dp,
    refractionAmountDp: Dp = 20.dp,
    depthEffect: Boolean = true,
    chromaticAberration: Boolean = false,
) {
    vibrancy()
    if (blurRadiusDp > 0.dp) {
        blur(blurRadiusDp.toPx())
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        refractionHeightDp > 0.dp &&
        refractionAmountDp > 0.dp
    ) {
        lens(
            refractionHeight = refractionHeightDp.toPx(),
            refractionAmount = refractionAmountDp.toPx(),
            depthEffect = depthEffect,
            chromaticAberration = chromaticAberration,
        )
    }
}

/**
 * 全局通用的液态玻璃表面修饰器：
 * 在 Liquid Glass 模式且存在 [LocalGlassBackdrop] 时应用 Kyant `drawBackdrop` 折射/模糊/高光；
 * 在 Material 3 模式或无 Backdrop（如独立测试）下平滑回退为干净的实底填充与细描边。
 */
fun Modifier.liquidGlassSurface(
    shape: Shape = GlassCardShape,
    tintColor: Color? = null,
    blurRadius: Dp = 12.dp,
    refractionHeight: Dp = 10.dp,
    refractionAmount: Dp = 20.dp,
    depthEffect: Boolean = true,
    chromaticAberration: Boolean = false,
    ambientHighlightOnly: Boolean = false,
    shadowElevation: Dp = 0.dp,
    innerShadow: InnerShadow? = null,
    fallbackColor: Color? = null,
    fallbackBorder: BorderStroke? = null,
    backdropOverride: Backdrop? = null,
): Modifier = composed {
    val style = LocalUiStyle.current
    val backdrop = backdropOverride ?: LocalGlassBackdrop.current
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlassDark.current || scheme.surface.luminance() < 0.5f

    if (style == UiStyle.LIQUID_GLASS && backdrop != null) {
        val resolvedTint = tintColor ?: if (dark) {
            scheme.surfaceContainerHigh.copy(alpha = 0.54f)
        } else {
            scheme.surfaceContainerLowest.copy(alpha = 0.68f)
        }
        val dropShadow = if (shadowElevation > 0.dp) {
            Shadow(
                radius = shadowElevation,
                color = Color.Black.copy(alpha = if (dark) 0.28f else 0.12f),
            )
        } else {
            null
        }
        val highlight = if (ambientHighlightOnly) Highlight.Ambient else Highlight.Default
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                applyGlassEffects(
                    blurRadiusDp = blurRadius,
                    refractionHeightDp = refractionHeight,
                    refractionAmountDp = refractionAmount,
                    depthEffect = depthEffect,
                    chromaticAberration = chromaticAberration,
                )
            },
            highlight = { highlight },
            shadow = { dropShadow },
            innerShadow = { innerShadow },
            onDrawSurface = { drawRect(resolvedTint) },
        )
    } else {
        val container = fallbackColor ?: tintColor ?: scheme.surfaceContainerLow
        val borderStroke = fallbackBorder ?: BorderStroke(
            1.dp,
            scheme.outlineVariant.copy(alpha = 0.55f),
        )
        this
            .clip(shape)
            .background(container, shape)
            .border(borderStroke, shape)
    }
}

/**
 * 柔和的 Material You 环境光画布：在 Liquid Glass 模式下为各层玻璃卡片、胶囊导航与弹窗
 * 提供细腻流动的色彩纵深，同时保持文字对比度舒适不刺眼。
 */
@Composable
fun LiquidAmbientBackground(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    dark: Boolean = LocalGlassDark.current,
    modifier: Modifier = Modifier,
) {
    val base = colorScheme.surface
    val primaryAura = colorScheme.primary.copy(alpha = if (dark) 0.22f else 0.16f)
    val secondaryAura = colorScheme.secondaryContainer.copy(alpha = if (dark) 0.24f else 0.34f)
    val tertiaryAura = colorScheme.tertiaryContainer.copy(alpha = if (dark) 0.20f else 0.26f)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(base),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primaryAura, Color.Transparent),
                center = Offset(w * 0.16f, h * 0.12f),
                radius = maxOf(w, h) * 0.62f,
            ),
            center = Offset(w * 0.16f, h * 0.12f),
            radius = maxOf(w, h) * 0.62f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tertiaryAura, Color.Transparent),
                center = Offset(w * 0.88f, h * 0.30f),
                radius = maxOf(w, h) * 0.58f,
            ),
            center = Offset(w * 0.88f, h * 0.30f),
            radius = maxOf(w, h) * 0.58f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondaryAura, Color.Transparent),
                center = Offset(w * 0.42f, h * 0.88f),
                radius = maxOf(w, h) * 0.64f,
            ),
            center = Offset(w * 0.42f, h * 0.88f),
            radius = maxOf(w, h) * 0.64f,
        )
    }
}

/**
 * 为局部独立玻璃容器提供可安全采集兄弟背景的宿主。
 */
@Composable
fun LiquidGlassHostBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, content = content)
}
