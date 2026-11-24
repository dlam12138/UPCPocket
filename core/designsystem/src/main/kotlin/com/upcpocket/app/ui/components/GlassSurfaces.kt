package com.upcpocket.app.ui.components

import android.os.Build
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.upcpocket.app.designsystem.DEFAULT_CUSTOM_BACKGROUND_OPACITY
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.designsystem.sanitizeCustomBackgroundOpacity
import com.upcpocket.app.ui.theme.LocalHasCustomBackground
import com.upcpocket.app.ui.theme.LocalUiStyle

private class GlassSource(val backdrop: LayerBackdrop, val owner: View)

private val LocalGlassSource = compositionLocalOf<GlassSource?> { null }

/**
 * 宿主共享背景与玻璃采样层。
 *
 * 采样层只包含主题光源与可选自定义背景图，不录制页面内容或交互控件。
 */
@Composable
fun GlassEnvironment(
    backgroundImage: ImageBitmap? = null,
    backgroundOpacity: Float = DEFAULT_CUSTOM_BACKGROUND_OPACITY,
    content: @Composable () -> Unit,
) {
    val glass = LocalUiStyle.current == UiStyle.LIQUID_GLASS
    val hasCustomBackground = backgroundImage != null
    val sheets = remember { GlassSheetVisibility() }
    val source = if (glass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.surface.luminance() < 0.5f
        val brush = remember(scheme, dark, hasCustomBackground) {
            val primaryAlpha = if (hasCustomBackground) {
                if (dark) 0.08f else 0.12f
            } else {
                if (dark) 0.17f else 0.26f
            }
            val tertiaryAlpha = if (hasCustomBackground) {
                if (dark) 0.06f else 0.09f
            } else {
                if (dark) 0.13f else 0.20f
            }
            Brush.linearGradient(
                0f to scheme.primary.copy(alpha = primaryAlpha),
                0.54f to Color.Transparent,
                1f to scheme.tertiary.copy(alpha = tertiaryAlpha),
            )
        }
        val backdrop = rememberLayerBackdrop(onDraw = remember(brush) { { drawRect(brush) } })
        val owner = LocalView.current
        remember(backdrop, owner) { GlassSource(backdrop, owner) }
    } else null

    val backdrop = source?.backdrop
    val clampedOpacity = sanitizeCustomBackgroundOpacity(backgroundOpacity)
    Box(Modifier.fillMaxSize()) {
        // Keep this slot mounted across appearance changes so navigation and page state survive.
        Box(
            Modifier.matchParentSize().then(
                if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
            ),
        ) {
            if (backgroundImage != null) {
                Image(
                    bitmap = backgroundImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = clampedOpacity },
                )
            }
        }
        CompositionLocalProvider(
            LocalHasCustomBackground provides hasCustomBackground,
            LocalGlassSource provides source,
            LocalGlassSheetVisibility provides if (glass) sheets else null,
            content = content,
        )
    }
}

/** The same-window backdrop contains only the static theme gradient, never page content. */
@Composable
fun glassThemeBackdrop(): Backdrop? {
    val source = LocalGlassSource.current
    val view = LocalView.current
    return source?.takeIf { it.owner === view }?.backdrop
}

/** Child controls share the surface's appearance using tint, sheen and edge treatment. */
@Composable
fun GlassNestedSurface(content: @Composable () -> Unit) {
    if (LocalGlassSource.current == null) {
        content()
    } else {
        CompositionLocalProvider(LocalGlassSource provides null, content = content)
    }
}

/** Material for custom containers with existing hit areas or semantics. */
@Composable
fun Modifier.glassPane(shape: Shape, color: Color, optical: Boolean = false): Modifier =
    glassPane(shape, color, optical, border = null)

@Composable
private fun Modifier.glassPane(
    shape: Shape,
    color: Color,
    optical: Boolean,
    border: BorderStroke?,
): Modifier {
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS || color.alpha == 0f) {
        return background(color, shape).then(if (border != null) Modifier.border(border, shape) else Modifier)
    }

    val source = LocalGlassSource.current
    val view = LocalView.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val glassFill = if (optical && source != null && source.owner === view && shape is RoundedCornerShape) {
        drawBackdrop(
            backdrop = source.backdrop,
            shape = { shape },
            effects = { lens(8.dp.toPx(), 12.dp.toPx(), depthEffect = false) },
            highlight = null,
            shadow = null,
            innerShadow = null,
            onDrawSurface = {
                drawRect(color.copy(alpha = color.alpha * if (dark) 0.16f else 0.22f))
            },
        )
    } else {
        background(glassSurfaceColor(color), shape)
    }
    return glassFill.glassHighlight(shape).border(border ?: glassBorder(), shape)
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    optical: Boolean = false,
    content: @Composable () -> Unit,
) {
    val glass = usesGlassSurface(shape, color)
    Surface(
        modifier = if (glass) modifier.glassPane(shape, color, optical, border) else modifier,
        shape = shape,
        color = if (glass) Color.Transparent else color,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        shadowElevation = if (glass) 0.dp else shadowElevation,
        border = if (glass) null else border,
    ) {
        if (glass && optical) GlassNestedSurface(content) else content()
    }
}

@Composable
fun GlassSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    optical: Boolean = false,
    content: @Composable () -> Unit,
) {
    val glass = usesGlassSurface(shape, color)
    val animated = LocalUiStyle.current == UiStyle.LIQUID_GLASS && enabled
    val interactions = if (animated && interactionSource == null) {
        remember { MutableInteractionSource() }
    } else interactionSource
    val surfaceModifier = if (glass) modifier.glassPane(shape, color, optical, border) else modifier
    Surface(
        onClick = onClick,
        modifier = if (animated) surfaceModifier.glassPressHighlight(shape, interactions) else surfaceModifier,
        enabled = enabled,
        shape = shape,
        color = if (glass) Color.Transparent else color,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        shadowElevation = if (glass) 0.dp else shadowElevation,
        border = if (glass) null else border,
        interactionSource = interactions,
    ) {
        if (glass && optical) GlassNestedSurface(content) else content()
    }
}

@Composable
private fun usesGlassSurface(shape: Shape, color: Color): Boolean =
    LocalUiStyle.current == UiStyle.LIQUID_GLASS && shape != RectangleShape && color.alpha > 0f

@Composable
fun GlassMaterialCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS) {
        Card(modifier = modifier, shape = shape, colors = colors, elevation = elevation, border = border, content = content)
        return
    }
    GlassSurface(
        modifier = modifier,
        shape = shape,
        color = colors.containerColor,
        contentColor = colors.contentColor,
        border = border,
        optical = true,
    ) { Column(content = content) }
}

@Composable
fun GlassMaterialCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS) {
        Card(
            onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
            elevation = elevation, border = border, interactionSource = interactionSource, content = content,
        )
        return
    }
    GlassSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = border,
        optical = true,
        interactionSource = interactionSource,
    ) { Column(content = content) }
}
