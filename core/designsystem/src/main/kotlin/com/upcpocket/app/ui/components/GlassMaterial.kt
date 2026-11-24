package com.upcpocket.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalHasCustomBackground
import com.upcpocket.app.ui.theme.LocalUiStyle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance

/** 按当前视口和主题缓存渐变，供透明页面共用；启用自定义背景图时跳过不透明底色渐变。 */
@Composable
fun Modifier.glassBackground(): Modifier {
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS || LocalHasCustomBackground.current) return this

    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val baseColor = scheme.surface
    val topColor = scheme.primary.copy(alpha = if (dark) 0.26f else 0.32f)
        .compositeOver(baseColor)
    val middleColor = scheme.tertiary.copy(alpha = if (dark) 0.15f else 0.18f)
        .compositeOver(baseColor)
    val bottomColor = scheme.primary.copy(alpha = if (dark) 0.29f else 0.34f)
        .compositeOver(baseColor)

    return drawWithCache {
        val background = Brush.linearGradient(
            0f to topColor,
            0.34f to middleColor,
            0.66f to baseColor,
            1f to bottomColor,
            start = Offset(size.width * 0.24f, 0f),
            end = Offset(size.width, size.height),
        )

        onDrawBehind { drawRect(background) }
    }
}

/** 填充颜色单独透出底层，文字和图标继承 Surface 的前景色。 */
@Composable
fun glassSurfaceColor(baseColor: Color): Color {
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS || baseColor == Color.Unspecified) return baseColor

    return baseColor.copy(alpha = baseColor.alpha * 0.26f)
}

/**
 * 在内容下方绘制静态玻璃光泽；底色、裁剪、文字颜色和交互语义由外层 Surface 提供。
 * Brush 和形状轮廓按尺寸、形状及主题输入缓存，也可用于半透明的导航胶囊。
 */
@Composable
fun Modifier.glassHighlight(shape: Shape): Modifier {
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS) return this

    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val upperSheen = Color.White.copy(alpha = if (dark) 0.13f else 0.41f)
    val upperFade = Color.White.copy(alpha = if (dark) 0.05f else 0.16f)

    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val sheen = Brush.linearGradient(
            0f to upperSheen,
            0.30f to upperFade,
            1f to Color.Transparent,
            start = Offset(size.width * 0.40f, 0f),
            end = Offset(size.width * 0.58f, size.height),
        )

        onDrawBehind {
            drawOutline(outline = outline, brush = sheen)
        }
    }
}

/** 使用原控件的按压事件，在绘制阶段完成短时高光和回弹。 */
@Composable
fun Modifier.glassPressHighlight(
    shape: Shape,
    interactions: InteractionSource?,
    compress: Boolean = false,
    indicatorSize: Dp? = null,
): Modifier {
    if (interactions == null || LocalUiStyle.current != UiStyle.LIQUID_GLASS) return this

    val intensity = remember(interactions) { Animatable(0f) }
    val pressPosition = remember(interactions) { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(interactions) {
        val presses = mutableListOf<PressInteraction.Press>()
        interactions.interactions.filterIsInstance<PressInteraction>().collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> presses.add(interaction)
                is PressInteraction.Release -> presses.remove(interaction.press)
                is PressInteraction.Cancel -> presses.remove(interaction.press)
                else -> return@collectLatest
            }

            val currentPress = presses.lastOrNull()
            if (currentPress != null) {
                pressPosition.value = currentPress.pressPosition
                intensity.animateTo(1f, tween(durationMillis = 90, easing = LinearOutSlowInEasing))
            } else {
                // A tap can finish before the first press frame, so let it produce a visible pulse.
                if (interaction is PressInteraction.Release && intensity.value < 0.55f) {
                    intensity.animateTo(0.55f, tween(durationMillis = 55, easing = LinearOutSlowInEasing))
                }
                intensity.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 560f))
            }
        }
    }

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val edge = Color.White.copy(alpha = if (dark) 0.82f else 0.94f)
    val reflection = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.48f else 0.34f)
    return drawWithCache {
        val stroke = Stroke(width = 1.5.dp.toPx())
        val indicator = indicatorSize?.toPx()?.coerceAtMost(size.minDimension)
        val width = ((indicator ?: size.width) - stroke.width).coerceAtLeast(0f)
        val height = ((indicator ?: size.height) - stroke.width).coerceAtLeast(0f)
        if (width == 0f || height == 0f) return@drawWithCache onDrawWithContent { drawContent() }
        val origin = Offset((size.width - width) / 2f, (size.height - height) / 2f)
        val point = pressPosition.value
        val lightCenter = if (point.isSpecified &&
            point.x in origin.x..(origin.x + width) && point.y in origin.y..(origin.y + height)
        ) point - origin else Offset(width / 2f, height / 2f)
        val outline = shape.createOutline(Size(width, height), layoutDirection, this)
        val glow = Brush.radialGradient(
            0f to edge,
            0.55f to reflection,
            1f to Color.Transparent,
            center = lightCenter,
            radius = maxOf(width, height).coerceAtLeast(1f) * 0.78f,
        )
        onDrawWithContent {
            val motion = intensity.value
            if (motion == 0f) {
                drawContent()
                return@onDrawWithContent
            }

            val amount = motion.coerceIn(0f, 1f)
            val scale = if (compress) 1f - 0.045f * motion.coerceIn(-0.16f, 1f) else 1f
            scale(scale, scale, center) {
                this@onDrawWithContent.drawContent()
                if (amount > 0f) {
                    translate(origin.x, origin.y) {
                        drawOutline(outline, glow, alpha = amount * 0.13f)
                        drawOutline(outline, glow, alpha = amount, style = stroke)
                    }
                }
            }
        }
    }
}

/** 卡片、分组控件和导航共用的材质边缘。 */
@Composable
fun glassBorder(width: Dp = 1.dp): BorderStroke {
    val scheme = MaterialTheme.colorScheme
    if (LocalUiStyle.current != UiStyle.LIQUID_GLASS) {
        return BorderStroke(width, scheme.outlineVariant.copy(alpha = 0.55f))
    }

    val dark = scheme.surface.luminance() < 0.5f
    val upperEdge = Color.White.copy(alpha = if (dark) 0.57f else 0.96f)
    val sideEdge = Color.White.copy(alpha = if (dark) 0.15f else 0.40f)
    val lowerEdge = scheme.primary.copy(alpha = if (dark) 0.44f else 0.34f)
    val edgeBrush = remember(upperEdge, sideEdge, lowerEdge) {
        Brush.verticalGradient(0f to upperEdge, 0.50f to sideEdge, 1f to lowerEdge)
    }
    return BorderStroke(width, edgeBrush)
}
