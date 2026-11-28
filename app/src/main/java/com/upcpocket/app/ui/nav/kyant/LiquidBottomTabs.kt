/*
 * Copyright 2025 Kyant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Source: Kyant0/AndroidLiquidGlass, tag 1.0.6 (896a94a3ade1cc1a940b92365f942a34971fecda).
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/1.0.6/catalog/src/main/java/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt
 * Modified for UPC Pocket: relocated package; integration changes are recorded in NOTICE.md.
 */
package com.upcpocket.app.ui.nav.kyant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.upcpocket.app.ui.components.glassBorder
import com.upcpocket.app.ui.components.glassHighlight
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private class LensTap {
    var index: Int? = null
}

private val DockBarHeight = 56.dp
private val DockHorizontalPadding = 4.dp
private val DockIndicatorHeight = 40.dp
private val DockIndicatorMaxWidth = 56.dp
private val DockIndicatorMinWidth = 36.dp
private val DockIndicatorSlotClearance = 12.dp

/**
 * Kyant 1.0.6 bottom tabs adapted for UPC Pocket's lightweight glass mode.
 *
 * [selectedIndex] is the host's controlled selection. Dragging reports [onTabSelected] once
 * when released; host-driven changes animate the pill without emitting another navigation.
 * The entire capsule composites offscreen (`CompositingStrategy.Offscreen`) inside a strict
 * `shadow -> clip -> background -> border` chain so semi-transparent layers blend once with
 * the page below and the compact indicator stays strictly inside the outer rim.
 * Use [LiquidBottomTab] for each equal-width tab. The host owns API-level fallback and insets.
 */
@Composable
fun LiquidBottomTabs(
    selectedIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable RowScope.() -> Unit
) {
    if (tabsCount <= 0) return
    val isLightTheme = !darkTheme
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val lensTap = remember { LensTap() }
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val panelShape = remember { Capsule() }
    val indicatorShape = remember { Capsule() }
    // GlassEnvironment does not record page content to avoid full-page blur jank; use a higher
    // frosted scrim alpha so underlying high-contrast text and borders do not bleed sharply through.
    val panelColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
        alpha = if (isLightTheme) 0.88f else 0.90f,
    )
    val indicatorRestAlpha = if (isLightTheme) 0.16f else 0.24f
    val panelBorder = glassBorder(0.85.dp)
    val panelElevation = if (isLightTheme) 4.dp else 2.dp
    val shadowColor = Color.Black.copy(alpha = if (isLightTheme) 0.12f else 0.28f)
    val accentFilter = remember(accentColor) { ColorFilter.tint(accentColor) }
    val selectedIconFilter = remember(accentFilter) { { accentFilter } }

    BoxWithConstraints(
        modifier.pointerInput(tabsCount, isLtr) {
            // Resolve taps in the fixed bar coordinates before drag cancellation/release runs,
            // while leaving the drag handler in charge of continuous sliding.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                lensTap.index = null
                var moved = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id } ?: break
                    moved = moved || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    if (!change.pressed) {
                        if (!moved) {
                            val insetPx = DockHorizontalPadding.toPx()
                            val tabWidth = ((size.width - insetPx * 2f) / tabsCount).coerceAtLeast(1f)
                            val x = if (isLtr) down.position.x else size.width - down.position.x
                            lensTap.index = ((x - insetPx) / tabWidth).toInt().coerceIn(0, tabsCount - 1)
                        }
                        break
                    }
                }
            }
        },
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val horizontalInsetPx = with(density) { DockHorizontalPadding.toPx() }
        val tabWidth = ((constraints.maxWidth.toFloat() - horizontalInsetPx * 2f) / tabsCount)
            .coerceAtLeast(1f)
        val indicatorWidth = with(density) {
            minOf(
                DockIndicatorMaxWidth.toPx(),
                (tabWidth - DockIndicatorSlotClearance.toPx()).coerceAtLeast(DockIndicatorMinWidth.toPx()),
            ).toDp()
        }
        val indicatorWidthPx = with(density) { indicatorWidth.toPx() }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, constraints.maxWidth) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth.coerceAtLeast(1))
                    .fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(tabsCount) {
            mutableIntStateOf(selectedIndex().coerceIn(0, tabsCount - 1))
        }
        val dampedDragAnimation = remember(animationScope, tabsCount, tabWidth, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex().coerceIn(0, tabsCount - 1).toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // Keep press expansion compact so the 40dp indicator stays well clear of the 56dp rim.
                pressedScale = 1.08f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = lensTap.index
                        ?: targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    lensTap.index = null
                    currentIndex = targetIndex
                    val targetFloat = targetIndex.toFloat()
                    if (abs(value - targetFloat) > 0.001f || abs(velocity) > 0.001f) {
                        animateToValue(targetFloat)
                    }
                    currentOnTabSelected(targetIndex)
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    if (dragAmount.x != 0f) {
                        updateValue(
                            (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                                .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                }
            )
        }
        LaunchedEffect(dampedDragAnimation, tabsCount) {
            snapshotFlow { currentSelectedIndex().coerceIn(0, tabsCount - 1) }
                .collectLatest { index ->
                    // A Pager-driven selection is an input, not another navigation event.
                    // Re-emitting it would cancel/restart the host's active page animation.
                    if (index != currentIndex) {
                        currentIndex = index
                        dampedDragAnimation.animateToValue(index.toFloat())
                    }
                }
        }

        val opticalActive by remember(dampedDragAnimation) {
            derivedStateOf {
                dampedDragAnimation.pressProgress > 0.001f ||
                    abs(dampedDragAnimation.value - dampedDragAnimation.targetValue) > 0.001f ||
                    abs(dampedDragAnimation.scaleX - 1f) > 0.001f ||
                    abs(dampedDragAnimation.scaleY - 1f) > 0.001f ||
                    abs(dampedDragAnimation.velocity) > 0.001f
            }
        }

        val interactiveHighlight = remember(animationScope, dampedDragAnimation, isLtr, tabWidth, horizontalInsetPx) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) horizontalInsetPx + (dampedDragAnimation.value + 0.5f) * tabWidth
                        else size.width - horizontalInsetPx - (dampedDragAnimation.value + 0.5f) * tabWidth,
                        size.height / 2f
                    )
                }
            )
        }

        val indicatorMaterial = Modifier
            .drawWithContent {
                if (opticalActive) {
                    drawContent()
                } else {
                    val radius = size.minDimension / 2f
                    drawRoundRect(
                        color = accentColor.copy(alpha = indicatorRestAlpha),
                        cornerRadius = CornerRadius(radius, radius)
                    )
                }
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { indicatorShape },
                effects = {
                    val progress = dampedDragAnimation.pressProgress
                    lens(
                        6f.dp.toPx() * progress,
                        10f.dp.toPx() * progress,
                        chromaticAberration = false
                    )
                },
                highlight = {
                    val progress = dampedDragAnimation.pressProgress
                    Highlight.Default.copy(alpha = 0.35f * progress)
                },
                shadow = null,
                innerShadow = null,
                layerBlock = {
                    scaleX = dampedDragAnimation.scaleX
                    scaleY = dampedDragAnimation.scaleY
                    val velocity = dampedDragAnimation.velocity / 10f
                    scaleX /= 1f - (velocity * 0.45f).fastCoerceIn(-0.10f, 0.10f)
                    scaleY *= 1f - (velocity * 0.18f).fastCoerceIn(-0.08f, 0.08f)
                },
                onDrawSurface = {
                    val progress = dampedDragAnimation.pressProgress
                    drawRect(accentColor.copy(alpha = indicatorRestAlpha + 0.08f * progress))
                }
            )

        // Single clipped offscreen capsule: shadow -> offscreen -> clip -> background -> border.
        Box(
            Modifier
                .fillMaxWidth()
                .height(DockBarHeight)
                .graphicsLayer {
                    translationX = panelOffset
                    val progress = dampedDragAnimation.pressProgress
                    val scale = 1f + (6f.dp.toPx() / size.width.coerceAtLeast(1f)) * progress
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = panelElevation,
                    shape = panelShape,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .clip(panelShape)
                .background(panelColor, panelShape)
                .glassHighlight(panelShape)
                .then(interactiveHighlight.modifier)
                .border(panelBorder, panelShape)
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Compact indicator drawn behind icons with 8dp vertical inset (matching Material 3).
            Box(
                Modifier
                    .graphicsLayer {
                        val offsetFromStart =
                            horizontalInsetPx + (dampedDragAnimation.value + 0.5f) * tabWidth - indicatorWidthPx / 2f
                        translationX = if (isLtr) offsetFromStart else -offsetFromStart
                    }
                    .then(indicatorMaterial)
                    .height(DockIndicatorHeight)
                    .width(indicatorWidth)
            )

            CompositionLocalProvider(
                LocalLiquidBottomTabSelectedColorFilter provides selectedIconFilter
            ) {
                Row(
                    Modifier
                        .selectableGroup()
                        .fillMaxWidth()
                        .height(DockBarHeight)
                        .padding(horizontal = DockHorizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }
    }
}
