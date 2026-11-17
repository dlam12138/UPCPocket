/*
 * Adapted from Kyant0/AndroidLiquidGlass (Apache-2.0) LiquidBottomTabs for UPC Pocket
 * floating capsule navigation bar with tabsBackdrop chromatic lens magnification and Android 12+ fallback.
 */
package com.upcpocket.app.ui.liquid

import android.os.Build
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

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
    // 清透玻璃外壳：浅色下用高通透冷白微光，深色下用幽深通透晶层，不再呈现灰蒙蒙底色
    val containerColor = if (isDark) {
        Color(0xFF141822).copy(alpha = 0.28f)
    } else {
        Color.White.copy(alpha = 0.22f)
    }
    val inactiveTint = if (isDark) Color.White.copy(alpha = 0.90f) else scheme.onSurface.copy(alpha = 0.88f)
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    val tabsCount = destinations.size
    var currentIndex by remember { mutableIntStateOf(selectedIndex.coerceIn(0, tabsCount - 1)) }
    val currentOnSelect by rememberUpdatedState(onSelect)

    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    val paddingPx = with(density) { 8.dp.toPx() }
    val dampedDragAnimation = remember(tabsCount, isLtr, paddingPx) {
        LiquidDampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.coerceIn(0, tabsCount - 1).toFloat(),
            valueRange = 0f..(tabsCount - 1).coerceAtLeast(0).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.24f,
            horizontalOnly = true,
            slopFraction = 0.25f,
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = (targetValue + velocity * 0.12f)
                    .roundToInt()
                    .fastCoerceIn(0, tabsCount - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                currentOnSelect(targetIndex)
            },
            onDrag = { size, dragAmount ->
                if (tabsCount > 1 && size.width > 0) {
                    val usableWidth = (size.width.toFloat() - paddingPx).coerceAtLeast(1f)
                    val delta = (dragAmount.x / usableWidth) * tabsCount * if (isLtr) 1f else -1f
                    dragTo((targetValue + delta).fastCoerceIn(0f, (tabsCount - 1).toFloat()))
                }
            },
        )
    }

    LaunchedEffect(selectedIndex) {
        val clamped = selectedIndex.coerceIn(0, tabsCount - 1)
        if (clamped != currentIndex) {
            currentIndex = clamped
        }
        if (!dampedDragAnimation.isDragging && abs(dampedDragAnimation.targetValue - clamped.toFloat()) > 0.01f) {
            dampedDragAnimation.animateToValue(clamped.toFloat())
        }
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

    val interactiveHighlight = remember(animationScope, tabsCount, isLtr) {
        LiquidInteractiveHighlight(
            animationScope = animationScope,
            cancelOnConsumed = false,
            position = { size, _ ->
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
        val pillFraction = dampedDragAnimation.value * tabWidthPx
        val restingPillTint = if (isDark) {
            Color.White.copy(alpha = 0.14f)
        } else {
            Color.Black.copy(alpha = 0.08f)
        }

        // 1. 底层清透液态玻璃胶囊壳
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .graphicsLayer {
                    val progress = dampedDragAnimation.pressProgress
                    val scale = lerp(1f, 1.028f, progress)
                    scaleX = scale
                    scaleY = scale
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = {
                        applyGlassEffects(
                            blurRadiusDp = 4.dp,
                            refractionHeightDp = 16.dp,
                            refractionAmountDp = 28.dp,
                            depthEffect = true,
                        )
                    },
                    highlight = { Highlight.Default },
                    shadow = {
                        Shadow(
                            radius = 16.dp,
                            color = Color.Black.copy(alpha = if (isDark) 0.30f else 0.10f),
                        )
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(interactiveHighlight.modifier),
        )

        // 2. Kyant 隐藏高亮色层：供滑动透镜在拖拽/按压时实时折射出主题强调色图标
        Row(
            modifier = Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = {
                        val p = dampedDragAnimation.pressProgress
                        applyGlassEffects(
                            blurRadiusDp = 4.dp,
                            refractionHeightDp = 16.dp * p,
                            refractionAmountDp = 24.dp * p,
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp)
                .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { dest ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(dest.selectedIcon, contentDescription = null, modifier = Modifier.size(21.dp))
                    Text(
                        dest.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                        maxLines = 1,
                    )
                }
            }
        }

        // 3. 中层果冻阻尼滑动透镜胶囊（按压/滑动时变为透明色散放大镜，弹跳挤压随速度形变）
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(width = pillWidth, height = 56.dp)
                .drawBackdrop(
                    backdrop = combinedBackdrop,
                    shape = { barShape },
                    effects = {
                        val p = dampedDragAnimation.pressProgress
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            lens(
                                refractionHeight = (4.dp + 8.dp * p).toPx(),
                                refractionAmount = (6.dp + 12.dp * p).toPx(),
                                depthEffect = true,
                                chromaticAberration = p > 0.05f,
                            )
                        } else {
                            blur((2.dp * (1f - p * 0.5f)).toPx())
                        }
                    },
                    highlight = {
                        val p = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = 0.55f + 0.45f * p)
                    },
                    shadow = {
                        val p = dampedDragAnimation.pressProgress
                        if (p > 0.01f) Shadow(radius = 8.dp, alpha = 0.16f * p) else null
                    },
                    innerShadow = {
                        val p = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8.dp * p,
                            alpha = p * 0.65f,
                        )
                    },
                    layerBlock = {
                        val maxX = ((tabsCount - 1).coerceAtLeast(0) * tabWidthPx).coerceAtLeast(0f)
                        val xOffset = if (isLtr) pillFraction.fastCoerceIn(0f, maxX)
                        else (maxX - pillFraction).fastCoerceIn(0f, maxX)
                        translationX = xOffset
                        val v = (dampedDragAnimation.velocity / 10f).fastCoerceIn(-0.24f, 0.24f)
                        scaleX = dampedDragAnimation.scaleX / (1f - v * 0.75f).fastCoerceIn(0.8f, 1.25f)
                        scaleY = dampedDragAnimation.scaleY * (1f - v * 0.25f).fastCoerceIn(0.8f, 1.2f)
                        rotationZ = (dampedDragAnimation.velocity * 0.35f).fastCoerceIn(-2.5f, 2.5f)
                    },
                    onDrawSurface = {
                        val p = dampedDragAnimation.pressProgress
                        drawRect(restingPillTint, alpha = 1f - p)
                        drawRect(Color.White.copy(alpha = 0.05f * p))
                    },
                ),
        )

        // 4. 顶层可点击图标与文字行（不被底层 Backdrop 节点阻拦点击或测试语义）
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
                val pressScale = lerp(1f, 1.12f, if (isSelected) dampedDragAnimation.pressProgress else 0f)
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
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        }
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
