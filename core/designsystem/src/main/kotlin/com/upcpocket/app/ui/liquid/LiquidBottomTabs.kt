/*
 * Adapted from Kyant0/AndroidLiquidGlass (Apache-2.0) LiquidBottomTabs for UPC Pocket
 * floating capsule navigation bar with Pager synchronization and Android 12+ fallback.
 */
package com.upcpocket.app.ui.liquid

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorFilter
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Immutable
data class LiquidDockDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val tabModifier: Modifier = Modifier,
)

@Composable
fun LiquidBottomTabs(
    destinations: List<LiquidDockDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    darkSurface: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier,
) {
    if (destinations.isEmpty()) return

    val scheme = MaterialTheme.colorScheme
    val isDark = LocalGlassDark.current || darkSurface
    val accentColor = scheme.primary
    val containerColor = if (isDark) {
        scheme.surfaceContainerHigh.copy(alpha = 0.44f)
    } else {
        scheme.surfaceContainerLowest.copy(alpha = 0.48f)
    }
    val inactiveTint = if (isDark) Color.White.copy(alpha = 0.86f) else scheme.onSurface.copy(alpha = 0.86f)
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    val tabsCount = destinations.size
    var currentIndex by remember { mutableIntStateOf(selectedIndex.coerceIn(0, tabsCount - 1)) }

    val panelLayerBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, panelLayerBackdrop)

    val dampedDragAnimation = remember(tabsCount, isLtr) {
        LiquidDampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.coerceIn(0, tabsCount - 1).toFloat(),
            valueRange = 0f..(tabsCount - 1).coerceAtLeast(0).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.24f,
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = value.roundToInt().fastCoerceIn(0, tabsCount - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                onSelect(targetIndex)
            },
            onDrag = { size, dragAmount ->
                if (tabsCount > 1 && size.width > 0) {
                    val delta = (dragAmount.x / size.width.toFloat()) * if (isLtr) 1f else -1f
                    updateValue((value + delta).fastCoerceIn(0f, (tabsCount - 1).toFloat()))
                }
            },
        )
    }

    LaunchedEffect(selectedIndex) {
        val clamped = selectedIndex.coerceIn(0, tabsCount - 1)
        if (clamped != currentIndex) {
            currentIndex = clamped
        }
        dampedDragAnimation.animateToValue(clamped.toFloat())
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { dampedDragAnimation.value }
            .distinctUntilChanged()
            .collectLatest { value ->
                val target = value.roundToInt().fastCoerceIn(0, tabsCount - 1)
                if (target != currentIndex) {
                    currentIndex = target
                }
            }
    }

    val interactiveHighlight = remember(animationScope) {
        LiquidInteractiveHighlight(
            animationScope = animationScope,
            position = { size, offset ->
                Offset(
                    x = if (isLtr) (dampedDragAnimation.value + 0.5f) * (size.width / tabsCount)
                    else size.width - (dampedDragAnimation.value + 0.5f) * (size.width / tabsCount),
                    y = size.height / 2f,
                )
            },
        )
    }

    val barShape = Capsule()

    BoxWithConstraints(
        modifier = modifier
            .height(64.dp)
            .then(interactiveHighlight.gestureModifier)
            .then(dampedDragAnimation.modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        val tabWidthPx = with(density) { (maxWidth - 8.dp).toPx() } / tabsCount.coerceAtLeast(1)
        val pillWidth = ((maxWidth - 8.dp) / tabsCount.coerceAtLeast(1)).coerceAtLeast(48.dp)
        val pillFraction = (dampedDragAnimation.value * tabWidthPx)
        val tintNeutral = if (isDark) {
            Color.White.copy(alpha = 0.14f)
        } else {
            scheme.primary.copy(alpha = 0.10f)
        }

        // 1. 底层液态玻璃胶囊壳（采集底层导航滚动内容并导出到 panelLayerBackdrop）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .graphicsLayer {
                    val progress = dampedDragAnimation.pressProgress
                    val scale = 1f + 0.03f * EaseOut.transform(progress)
                    scaleX = scale
                    scaleY = scale
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = {
                        applyGlassEffects(
                            blurRadiusDp = 10.dp,
                            refractionHeightDp = 12.dp,
                            refractionAmountDp = 24.dp,
                            depthEffect = true,
                        )
                    },
                    highlight = { Highlight.Default },
                    shadow = {
                        Shadow(
                            radius = 16.dp,
                            color = Color.Black.copy(alpha = if (isDark) 0.34f else 0.14f),
                        )
                    },
                    layerBlock = null,
                    exportedBackdrop = panelLayerBackdrop,
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(interactiveHighlight.modifier),
        )

        // 2. 中层阻尼滑动液态选区透镜胶囊（位于标签行下方，不阻挡点击事件）
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(width = pillWidth, height = 56.dp)
                .drawBackdrop(
                    backdrop = combinedBackdrop,
                    shape = { barShape },
                    effects = {
                        val pressProgress = dampedDragAnimation.pressProgress
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            lens(
                                refractionHeight = (8.dp + 4.dp * pressProgress).toPx(),
                                refractionAmount = (16.dp + 12.dp * pressProgress).toPx(),
                                depthEffect = true,
                                chromaticAberration = pressProgress > 0.05f,
                            )
                        } else {
                            blur(4.dp.toPx())
                        }
                    },
                    highlight = {
                        val p = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = 0.55f + 0.45f * p)
                    },
                    innerShadow = {
                        val p = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 6.dp,
                            alpha = 0.18f * p,
                        )
                    },
                    layerBlock = {
                        val maxX = ((tabsCount - 1).coerceAtLeast(0) * tabWidthPx).coerceAtLeast(0f)
                        val xOffset = if (isLtr) pillFraction.fastCoerceIn(0f, maxX)
                        else (maxX - pillFraction).fastCoerceIn(0f, maxX)
                        translationX = xOffset
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val v = abs(dampedDragAnimation.angularVelocity).fastCoerceIn(0f, 12f)
                        rotationZ = (dampedDragAnimation.angularVelocity * 0.15f).fastCoerceIn(-3f, 3f)
                        alpha = (0.94f + 0.06f * (v / 12f)).coerceIn(0f, 1f)
                    },
                    onDrawSurface = {
                        drawRect(tintNeutral)
                    },
                ),
        )

        // 3. 顶层可点击标签栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEachIndexed { index, dest ->
                val isSelected = index == currentIndex
                val contentColor = if (isSelected) accentColor else inactiveTint
                Column(
                    modifier = dest.tabModifier
                        .clip(GlassCapsuleClipShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = {
                                currentIndex = index
                                dampedDragAnimation.animateToValue(index.toFloat())
                                onSelect(index)
                            },
                        )
                        .semantics {
                            selected = isSelected
                        }
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        Icon(
                            imageVector = if (isSelected) dest.selectedIcon else dest.icon,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                        )
                        Text(
                            text = dest.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = contentColor,
                        )
                    }
                }
            }
        }
    }
}
