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
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private class LensTap {
    var index: Int? = null
}

/**
 * Kyant 1.0.6 bottom tabs with one optical panel and an interaction-only combined lens.
 *
 * [selectedIndex] is the host's controlled selection. Dragging reports [onTabSelected] once
 * when released; host-driven changes animate the pill without emitting another navigation.
 * During interaction, [content] is also recorded as tinted icons for refraction and must be
 * side-effect free. At rest, the visible selected tab supplies its own tint without a lens pass.
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
    val containerColor = Color.White.copy(alpha = if (isLightTheme) 0.15f else 0.05f)
    val accentFilter = remember(accentColor) { ColorFilter.tint(accentColor) }

    val tabsBackdrop = rememberLayerBackdrop()
    val lensBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    BoxWithConstraints(
        modifier.pointerInput(tabsCount, isLtr) {
            // The moving lens can cover another tab. Resolve taps in the fixed bar coordinates
            // before the lens sees the release, while leaving the original drag handler in charge.
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
                            val tabWidth = ((size.width - 8.dp.toPx()) / tabsCount).coerceAtLeast(1f)
                            val x = if (isLtr) down.position.x else size.width - down.position.x
                            lensTap.index = ((x - 4.dp.toPx()) / tabWidth).toInt().coerceIn(0, tabsCount - 1)
                        }
                        break
                    }
                }
            }
        },
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, constraints.maxWidth) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
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
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = lensTap.index
                        ?: targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    lensTap.index = null
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    currentOnTabSelected(targetIndex)
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
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
        val selectedIconFilter = remember(accentFilter, dampedDragAnimation) {
            { if (opticalActive) null else accentFilter }
        }

        val interactiveHighlight = remember(animationScope, dampedDragAnimation, isLtr, tabWidth) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabSelectedColorFilter provides selectedIconFilter
        ) {
            Row(
                Modifier
                    .selectableGroup()
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        },
                        shadow = null,
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(64f.dp)
                    .fillMaxWidth()
                    .padding(4f.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabInteractive provides false,
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .drawWithContent {
                        // Do not record icons when no optical pass consumes this source.
                        if (opticalActive) drawContent()
                    }
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .height(56f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = accentFilter),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        val lensMaterial = Modifier
            .drawWithContent {
                // Keep the optical nodes attached while the resting fill skips their drawing.
                if (opticalActive) {
                    drawContent()
                } else {
                    val radius = size.minDimension / 2f
                    drawRoundRect(
                        color = accentColor.copy(alpha = 0.12f),
                        cornerRadius = CornerRadius(radius, radius)
                    )
                }
            }
            .drawBackdrop(
                backdrop = lensBackdrop,
                shape = { Capsule() },
                effects = {
                    val progress = dampedDragAnimation.pressProgress
                    lens(
                        10f.dp.toPx() * progress,
                        14f.dp.toPx() * progress,
                        chromaticAberration = true
                    )
                },
                highlight = {
                    val progress = dampedDragAnimation.pressProgress
                    Highlight.Default.copy(alpha = progress)
                },
                shadow = null,
                innerShadow = null,
                layerBlock = {
                    scaleX = dampedDragAnimation.scaleX
                    scaleY = dampedDragAnimation.scaleY
                    val velocity = dampedDragAnimation.velocity / 10f
                    scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                    scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                },
                onDrawSurface = {
                    val progress = dampedDragAnimation.pressProgress
                    drawRect(accentColor.copy(alpha = 0.12f + 0.06f * progress))
                }
            )

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .then(lensMaterial)
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}
