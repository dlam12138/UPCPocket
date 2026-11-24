package com.upcpocket.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalHasCustomBackground
import com.upcpocket.app.ui.theme.LocalUiStyle
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 归一化持久化模块顺序：去重、过滤无效项并补齐未记录的默认模块，确保不丢模块。
 */
fun <T> normalizeModuleOrder(
    stored: List<T>,
    defaults: List<T>,
): List<T> {
    if (defaults.isEmpty()) return emptyList()
    val allowed = defaults.toSet()
    val seen = LinkedHashSet<T>(defaults.size)
    for (item in stored) {
        if (item in allowed) {
            seen.add(item)
        }
    }
    for (item in defaults) {
        seen.add(item)
    }
    return seen.toList()
}

/**
 * 将重排后的可见模块子集写回完整顺序中原本属于这些可见模块的槽位，
 * 保留当前隐藏或未激活模块的相对位置。
 */
fun <T> mergeReorderedSubset(
    fullOrder: List<T>,
    reorderedVisible: List<T>,
): List<T> {
    if (reorderedVisible.isEmpty()) return fullOrder
    val visibleSet = reorderedVisible.toSet()
    var nextIndex = 0
    return fullOrder.map { item ->
        if ((item in visibleSet) && (nextIndex < reorderedVisible.size)) {
            reorderedVisible[nextIndex++]
        } else {
            item
        }
    }
}

internal fun computeItemTops(
    heights: List<Float>,
    spacingPx: Float,
): List<Float> {
    val result = ArrayList<Float>(heights.size)
    var y = 0f
    for (index in heights.indices) {
        result.add(y)
        y += heights[index] + spacingPx
    }
    return result
}

internal fun findHitItemIndex(
    y: Float,
    tops: List<Float>,
    heights: List<Float>,
): Int {
    for (index in tops.indices) {
        val top = tops[index]
        val bottom = top + heights[index]
        if ((y >= top) && (y <= bottom)) return index
    }
    return -1
}

/**
 * 计算拖动时的目标换位下标。
 *
 * 使用 `min(neighborHeight * 0.5f, draggedHeight * 0.6f)` 作为越界阈值，使高度差异
 * 悬殊的两个模块（如短日程卡与长周课表）只需拖过较短一方约一半高度即可自然换位；
 * 配合 [allowSwapUp] / [allowSwapDown] 迟滞门控，避免换位后立即反向震荡。
 */
internal fun findSwapTargetIndex(
    draggedIndex: Int,
    draggedTop: Float,
    tops: List<Float>,
    heights: List<Float>,
    allowSwapUp: Boolean = true,
    allowSwapDown: Boolean = true,
): Int {
    if (draggedIndex !in tops.indices) return draggedIndex
    val draggedHeight = heights[draggedIndex]
    if (allowSwapUp && draggedIndex > 0) {
        val prevBottom = tops[draggedIndex - 1] + heights[draggedIndex - 1]
        val threshold = minOf(heights[draggedIndex - 1] * 0.5f, draggedHeight * 0.6f)
        if (draggedTop < prevBottom - threshold) {
            return draggedIndex - 1
        }
    }
    if (allowSwapDown && draggedIndex < tops.lastIndex) {
        val nextTop = tops[draggedIndex + 1]
        val threshold = minOf(heights[draggedIndex + 1] * 0.5f, draggedHeight * 0.6f)
        if (draggedTop + draggedHeight > nextTop + threshold) {
            return draggedIndex + 1
        }
    }
    return draggedIndex
}

private class ReorderLayoutTracker {
    val heights: MutableMap<Any, Int> = mutableMapOf()
    val shiftTokens: MutableMap<Any, Int> = mutableMapOf()
    var containerTopInParent: Float = 0f
    var liftGeneration: Int = 0
    var settleGeneration: Int = 0
}

internal data class ReorderDragVisualSpec(
    val isGlass: Boolean,
    val layerAlpha: Float,
    val maxShadowElevationDp: Dp,
    val m3SurfaceColor: Color,
    val m3CardColor: Color,
    val m3TrayColor: Color,
    val m3StateOverlayColor: Color,
    val m3BorderColor: Color,
    val m3ShadowColor: Color,
    val glassCarrierColor: Color,
    val glassTintColor: Color,
    val glassSheenTopColor: Color,
    val glassSheenMidColor: Color,
    val glassRimTopColor: Color,
    val glassRimMidColor: Color,
    val glassRimBottomColor: Color,
)

internal fun resolveReorderDragVisualSpec(
    uiStyle: UiStyle,
    scheme: ColorScheme,
): ReorderDragVisualSpec {
    val isGlass = uiStyle == UiStyle.LIQUID_GLASS
    val dark = scheme.surface.luminance() < 0.5f
    return ReorderDragVisualSpec(
        isGlass = isGlass,
        layerAlpha = 1f,
        maxShadowElevationDp = if (isGlass) 0.dp else 8.dp,
        m3SurfaceColor = scheme.surface,
        m3CardColor = scheme.surfaceContainerLow,
        m3TrayColor = scheme.surfaceContainer,
        m3StateOverlayColor = scheme.primary.copy(alpha = 0.05f),
        m3BorderColor = lerp(scheme.outlineVariant, scheme.primary, 0.78f).copy(alpha = 0.90f),
        m3ShadowColor = scheme.scrim,
        glassCarrierColor = scheme.surfaceContainerLow.copy(alpha = if (dark) 0.34f else 0.44f),
        glassTintColor = scheme.primary.copy(alpha = if (dark) 0.10f else 0.07f),
        glassSheenTopColor = Color.White.copy(alpha = if (dark) 0.16f else 0.42f),
        glassSheenMidColor = Color.White.copy(alpha = if (dark) 0.06f else 0.16f),
        glassRimTopColor = Color.White.copy(alpha = if (dark) 0.72f else 0.98f),
        glassRimMidColor = Color.White.copy(alpha = if (dark) 0.24f else 0.48f),
        glassRimBottomColor = scheme.primary.copy(alpha = if (dark) 0.54f else 0.42f),
    )
}

private data class ExpandedRoundedRectShape(
    val horizontalExpandPx: Float,
    val verticalExpandPx: Float,
    val cornerRadiusPx: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rounded(
        RoundRect(
            left = -horizontalExpandPx,
            top = -verticalExpandPx,
            right = size.width + horizontalExpandPx,
            bottom = size.height + verticalExpandPx,
            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
        ),
    )
}

private fun Modifier.reorderDragVisuals(
    spec: ReorderDragVisualSpec,
    isGrouped: Boolean,
    hasCustomBackground: Boolean,
    progressProvider: () -> Float,
    groupPadXPx: Float,
    groupPadYPx: Float,
    cardCornerRadiusPx: Float,
    groupCornerRadiusPx: Float,
    strokeWidthPx: Float,
): Modifier = drawWithCache {
    val left = if (isGrouped) -groupPadXPx else 0f
    val top = if (isGrouped) -groupPadYPx else 0f
    val width = size.width - left * 2f
    val height = size.height - top * 2f
    val topLeft = Offset(left, top)
    val rectSize = Size(width.coerceAtLeast(0f), height.coerceAtLeast(0f))
    val radiusPx = if (isGrouped) groupCornerRadiusPx else cardCornerRadiusPx
    val cornerRadius = CornerRadius(radiusPx, radiusPx)

    val halfStroke = strokeWidthPx * 0.5f
    val strokeTopLeft = Offset(left + halfStroke, top + halfStroke)
    val strokeSize = Size(
        (width - strokeWidthPx).coerceAtLeast(0f),
        (height - strokeWidthPx).coerceAtLeast(0f),
    )
    val strokeRadiusPx = (radiusPx - halfStroke).coerceAtLeast(0f)
    val strokeCorner = CornerRadius(strokeRadiusPx, strokeRadiusPx)
    val borderStroke = Stroke(width = strokeWidthPx)

    val sheenBrush = if (spec.isGlass) {
        Brush.linearGradient(
            0f to spec.glassSheenTopColor,
            0.32f to spec.glassSheenMidColor,
            1f to Color.Transparent,
            start = Offset(left + rectSize.width * 0.38f, top),
            end = Offset(left + rectSize.width * 0.58f, top + rectSize.height.coerceAtLeast(1f)),
        )
    } else {
        null
    }
    val rimBrush = if (spec.isGlass) {
        Brush.verticalGradient(
            0f to spec.glassRimTopColor,
            0.50f to spec.glassRimMidColor,
            1f to spec.glassRimBottomColor,
            startY = top,
            endY = (top + rectSize.height).coerceAtLeast(top + 1f),
        )
    } else {
        null
    }

    onDrawWithContent {
        val p = progressProvider().coerceIn(0f, 1f)
        if (p <= 0f || rectSize.width <= 0f || rectSize.height <= 0f) {
            drawContent()
            return@onDrawWithContent
        }

        if (!spec.isGlass) {
            // Material 3：使用不透明实色抬升容器 + 圆角轮廓阴影 + 纯色描边，遮挡底层内容，不产生玻璃透底感。
            if (isGrouped) {
                val fill = if (hasCustomBackground) {
                    spec.m3TrayColor.copy(alpha = p)
                } else {
                    lerp(spec.m3SurfaceColor, spec.m3TrayColor, p)
                }
                drawRoundRect(
                    color = fill,
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = cornerRadius,
                )
            } else if (hasCustomBackground) {
                drawRoundRect(
                    color = spec.m3CardColor.copy(alpha = p),
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = cornerRadius,
                )
            }

            drawContent()

            if (!isGrouped) {
                drawRoundRect(
                    color = spec.m3StateOverlayColor.copy(alpha = spec.m3StateOverlayColor.alpha * p),
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = cornerRadius,
                )
            }
            if (strokeSize.width > 0f && strokeSize.height > 0f) {
                drawRoundRect(
                    color = spec.m3BorderColor.copy(alpha = spec.m3BorderColor.alpha * p),
                    topLeft = strokeTopLeft,
                    size = strokeSize,
                    cornerRadius = strokeCorner,
                    style = borderStroke,
                )
            }
        } else {
            // 轻量玻璃：保持零硬件阴影，采用半透明玻璃底衬、斜向顶部镜面柔光与方向性高光边缘。
            val carrierAlphaScale = if (isGrouped) 1f else 0.50f
            drawRoundRect(
                color = spec.glassCarrierColor.copy(
                    alpha = spec.glassCarrierColor.alpha * carrierAlphaScale * p,
                ),
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = cornerRadius,
            )
            drawRoundRect(
                color = spec.glassTintColor.copy(alpha = spec.glassTintColor.alpha * p),
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = cornerRadius,
            )
            if (isGrouped && sheenBrush != null) {
                drawRoundRect(
                    brush = sheenBrush,
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = cornerRadius,
                    alpha = p,
                )
            }

            drawContent()

            if (!isGrouped && sheenBrush != null) {
                drawRoundRect(
                    brush = sheenBrush,
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = cornerRadius,
                    alpha = 0.28f * p,
                )
            }
            if (rimBrush != null && strokeSize.width > 0f && strokeSize.height > 0f) {
                drawRoundRect(
                    brush = rimBrush,
                    topLeft = strokeTopLeft,
                    size = strokeSize,
                    cornerRadius = strokeCorner,
                    alpha = p,
                    style = borderStroke,
                )
            }
        }
    }
}

/**
 * 支持长按拖动排序的垂直模块容器，适用于数量有界（通常 2~10 项）的页面区块。
 *
 * - 空闲态不附加额外 `graphicsLayer` / `drawWithCache` / 常驻动画协程，避免页面切换与滚动合成掉帧；
 * - 普通点击、上下滚动和外层 HorizontalPager 横滑在长按触发前不被拦截且按下瞬间零列表分配；
 * - 长按激活后在 [PointerEventPass.Initial] 消费拖动与抬起事件，避免松手误触发子项点击；
 * - 拖动视觉随 [LocalUiStyle] 自动区分 Material 3（实色不透明抬升容器、圆角硬件阴影、纯色激活描边）与
 *   轻量玻璃（零硬件阴影、半透明玻璃托盘、顶部镜面柔光与方向性高光边缘）；
 * - 拖动位移、让位动画与抬起缩放全部延后到 Draw 阶段（`graphicsLayer` / `drawWithCache`）；
 * - 提供无障碍「上移 / 下移」自定义操作，松手时回调 [onReorderFinished] 持久化。
 */
@Composable
fun <T> ReorderableModuleColumn(
    items: List<T>,
    key: (T) -> Any,
    onReorderFinished: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    spacing: Dp = 14.dp,
    enabled: Boolean = true,
    itemLabel: (T) -> String = { "" },
    itemTestTag: ((T) -> String)? = null,
    itemGrouped: (T) -> Boolean = { false },
    itemContent: @Composable (item: T, isDragging: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val uiStyle = LocalUiStyle.current
    val hasCustomBackground = LocalHasCustomBackground.current
    val colorScheme = MaterialTheme.colorScheme
    val visualSpec = remember(uiStyle, colorScheme) {
        resolveReorderDragVisualSpec(uiStyle, colorScheme)
    }

    val spacingPx = with(density) { spacing.toPx() }
    val edgeThresholdPx = with(density) { 72.dp.toPx() }
    val maxAutoScrollPerFramePx = with(density) { 14.dp.toPx() }
    val maxShadowElevationPx = with(density) { visualSpec.maxShadowElevationDp.toPx() }
    val cardCornerRadiusPx = with(density) { 20.dp.toPx() }
    val groupCornerRadiusPx = with(density) { 24.dp.toPx() }
    val groupPadXPx = with(density) { 8.dp.toPx() }
    val groupPadYPx = with(density) { 6.dp.toPx() }
    val borderStrokePx = with(density) { 1.5.dp.toPx() }
    val singleCardShape = remember(cardCornerRadiusPx) {
        ExpandedRoundedRectShape(0f, 0f, cardCornerRadiusPx)
    }
    val groupedTrayShape = remember(groupPadXPx, groupPadYPx, groupCornerRadiusPx) {
        ExpandedRoundedRectShape(groupPadXPx, groupPadYPx, groupCornerRadiusPx)
    }

    val layoutTracker = remember { ReorderLayoutTracker() }
    val shiftAnimations = remember { mutableMapOf<Any, Animatable<Float, AnimationVector1D>>() }
    val dragProgress = remember { Animatable(0f) }

    var localOrder by remember { mutableStateOf<List<T>?>(null) }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var liftKey by remember { mutableStateOf<Any?>(null) }
    var settlingKey by remember { mutableStateOf<Any?>(null) }
    var activeShiftKeys by remember { mutableStateOf<Set<Any>>(emptySet()) }
    val dragOffsetY = remember { mutableFloatStateOf(0f) }
    val settlingOffsetY = remember { Animatable(0f) }

    val displayedItems = localOrder ?: items
    val canDrag = enabled && displayedItems.size > 1

    val latestItems by rememberUpdatedState(items)
    val latestDisplayedItems by rememberUpdatedState(displayedItems)
    val latestKey by rememberUpdatedState(key)
    val latestOnReorderFinished by rememberUpdatedState(onReorderFinished)
    val latestScrollState by rememberUpdatedState(scrollState)

    // 外部顺序与本地提交一致，或模块集合发生变化（例如插件启停/槽位显隐）时，回归外部真相源。
    LaunchedEffect(items, draggingKey) {
        if (draggingKey != null) return@LaunchedEffect
        val pending = localOrder ?: return@LaunchedEffect
        if (items == pending || items.mapTo(HashSet(), key) != pending.mapTo(HashSet(), key)) {
            localOrder = null
        }
    }

    fun currentHeightsFor(list: List<T>): List<Float> =
        list.map { item -> (layoutTracker.heights[latestKey(item)] ?: 0).toFloat() }

    fun launchShiftAnimation(itemKey: Any, delta: Float) {
        val anim = shiftAnimations.getOrPut(itemKey) { Animatable(0f) }
        val token = (layoutTracker.shiftTokens[itemKey] ?: 0) + 1
        layoutTracker.shiftTokens[itemKey] = token
        activeShiftKeys += itemKey
        scope.launch {
            try {
                anim.snapTo(delta)
                anim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                )
            } finally {
                if (layoutTracker.shiftTokens[itemKey] == token) {
                    activeShiftKeys -= itemKey
                }
            }
        }
    }

    fun moveItemByAccessibility(fromIndex: Int, toIndex: Int) {
        val current = latestDisplayedItems
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) {
            return
        }
        val oldHeights = currentHeightsFor(current)
        val oldTops = computeItemTops(oldHeights, spacingPx)
        val updated = current.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
        val newHeights = currentHeightsFor(updated)
        val newTops = computeItemTops(newHeights, spacingPx)
        updated.forEachIndexed { newIndex, item ->
            val oldIndex = current.indexOfFirst { latestKey(it) == latestKey(item) }
            if (oldIndex >= 0 && oldIndex != newIndex) {
                val delta = oldTops[oldIndex] - newTops[newIndex]
                if (abs(delta) > 0.5f) {
                    launchShiftAnimation(latestKey(item), delta)
                }
            }
        }
        localOrder = updated
        latestOnReorderFinished(updated)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onPlaced { coords ->
                layoutTracker.containerTopInParent = coords.positionInParent().y
            }
            .then(
                if (!canDrag) {
                    Modifier
                } else {
                    Modifier.pointerInput(spacingPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val pointerId = down.id
                            val downPosition = down.position
                            val touchSlopSq = viewConfiguration.touchSlop * viewConfiguration.touchSlop

                            // 按下瞬间零列表分配；仅在持续静止达到长按阈值后才解析命中模块。
                            val canceledBeforeLongPress = withTimeoutOrNull(
                                viewConfiguration.longPressTimeoutMillis,
                            ) {
                                while (true) {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                    var pressedCount = 0
                                    var activeChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
                                    for (i in event.changes.indices) {
                                        val c = event.changes[i]
                                        if (c.pressed) pressedCount++
                                        if (c.id == pointerId) activeChange = c
                                    }
                                    val change = activeChange
                                    if (change == null || !change.pressed || pressedCount > 1) {
                                        return@withTimeoutOrNull true
                                    }
                                    val dx = change.position.x - downPosition.x
                                    val dy = change.position.y - downPosition.y
                                    if (dx * dx + dy * dy > touchSlopSq) {
                                        return@withTimeoutOrNull true
                                    }
                                }
                            }
                            if (canceledBeforeLongPress == true) return@awaitEachGesture

                            val initialList = latestDisplayedItems
                            if (initialList.size <= 1) return@awaitEachGesture

                            val initialHeights = currentHeightsFor(initialList)
                            val initialTops = computeItemTops(initialHeights, spacingPx)
                            val hitIndex = findHitItemIndex(
                                y = downPosition.y,
                                tops = initialTops,
                                heights = initialHeights,
                            )
                            if (hitIndex !in initialList.indices) return@awaitEachGesture

                            val activeItem = initialList[hitIndex]
                            val activeKey = latestKey(activeItem)
                            val grabOffsetInItem = downPosition.y - initialTops[hitIndex]
                            val reverseHysteresisPx = with(density) { 32.dp.toPx() }
                            var pointerYInParent = downPosition.y
                            var workingList = initialList
                            var reordered = false
                            var lastSwapDirection = 0
                            var lastSwapPointerY = downPosition.y

                            // 若上次松手回弹未结束，直接接管；切换到新模块时从 0 重新抬升。
                            val sameLiftedItem = liftKey == activeKey
                            layoutTracker.settleGeneration++
                            layoutTracker.liftGeneration++
                            settlingKey = null
                            draggingKey = activeKey
                            liftKey = activeKey
                            localOrder = workingList
                            dragOffsetY.floatValue = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                if (!sameLiftedItem) {
                                    dragProgress.snapTo(0f)
                                }
                                dragProgress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = 160,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                            }

                            fun updatePositionAndSwap(currentPointerY: Float) {
                                val desiredTop = currentPointerY - grabOffsetInItem
                                var currentIndex = workingList.indexOfFirst {
                                    latestKey(it) == activeKey
                                }
                                if (currentIndex !in workingList.indices) return

                                var heights = currentHeightsFor(workingList)
                                var tops = computeItemTops(heights, spacingPx)

                                var steps = 0
                                while (steps < workingList.size) {
                                    val allowUp = lastSwapDirection != 1 ||
                                        currentPointerY < lastSwapPointerY - reverseHysteresisPx
                                    val allowDown = lastSwapDirection != -1 ||
                                        currentPointerY > lastSwapPointerY + reverseHysteresisPx
                                    val targetIndex = findSwapTargetIndex(
                                        draggedIndex = currentIndex,
                                        draggedTop = desiredTop,
                                        tops = tops,
                                        heights = heights,
                                        allowSwapUp = allowUp,
                                        allowSwapDown = allowDown,
                                    )
                                    if (targetIndex == currentIndex) break

                                    val oldTops = tops
                                    val previousList = workingList
                                    val nextList = workingList.toMutableList().apply {
                                        val moved = removeAt(currentIndex)
                                        add(targetIndex, moved)
                                    }
                                    val nextHeights = currentHeightsFor(nextList)
                                    val nextTops = computeItemTops(nextHeights, spacingPx)

                                    // 为让位的邻居模块播放平滑补间位移，避免生硬瞬移。
                                    nextList.forEachIndexed { newIdx, item ->
                                        val itemKey = latestKey(item)
                                        if (itemKey != activeKey) {
                                            val oldIdx = previousList.indexOfFirst {
                                                latestKey(it) == itemKey
                                            }
                                            if (oldIdx >= 0 && oldIdx != newIdx) {
                                                val delta = oldTops[oldIdx] - nextTops[newIdx]
                                                if (abs(delta) > 0.5f) {
                                                    launchShiftAnimation(itemKey, delta)
                                                }
                                            }
                                        }
                                    }

                                    lastSwapDirection = if (targetIndex > currentIndex) 1 else -1
                                    lastSwapPointerY = currentPointerY
                                    workingList = nextList
                                    localOrder = workingList
                                    heights = nextHeights
                                    tops = nextTops
                                    currentIndex = targetIndex
                                    reordered = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    steps++
                                }

                                dragOffsetY.floatValue = desiredTop - tops[currentIndex]
                            }

                            var autoScrollJob: Job? = null
                            val activeScrollState = latestScrollState
                            if (activeScrollState != null) {
                                autoScrollJob = scope.launch {
                                    while (isActive && draggingKey == activeKey) {
                                        withFrameMillis { }
                                        if (draggingKey != activeKey) break
                                        val viewport = activeScrollState.viewportSize
                                        if (viewport <= 0 || activeScrollState.maxValue <= 0) {
                                            continue
                                        }
                                        val pointerInViewport =
                                            layoutTracker.containerTopInParent +
                                                pointerYInParent - activeScrollState.value
                                        val step = when {
                                            pointerInViewport < edgeThresholdPx &&
                                                activeScrollState.value > 0 -> {
                                                val ratio = ((edgeThresholdPx - pointerInViewport) /
                                                    edgeThresholdPx).coerceIn(0f, 1f)
                                                -maxAutoScrollPerFramePx * ratio
                                            }
                                            pointerInViewport > viewport - edgeThresholdPx &&
                                                activeScrollState.value < activeScrollState.maxValue -> {
                                                val ratio = ((pointerInViewport -
                                                    (viewport - edgeThresholdPx)) /
                                                    edgeThresholdPx).coerceIn(0f, 1f)
                                                maxAutoScrollPerFramePx * ratio
                                            }
                                            else -> 0f
                                        }
                                        if (step != 0f) {
                                            val consumed = activeScrollState.scrollBy(step)
                                            if (consumed != 0f) {
                                                pointerYInParent += consumed
                                                updatePositionAndSwap(pointerYInParent)
                                            }
                                        }
                                    }
                                }
                            }

                            try {
                                while (true) {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                    if (change == null) break
                                    // 在 Initial 阶段消费事件，阻断子组件误触点击和外层滚动抢手势。
                                    change.consume()
                                    pointerYInParent = change.position.y
                                    updatePositionAndSwap(pointerYInParent)
                                    if (!change.pressed ||
                                        event.type == PointerEventType.Release ||
                                        event.changes.all { !it.pressed }
                                    ) {
                                        break
                                    }
                                }
                            } finally {
                                autoScrollJob?.cancel()
                                val finalOffset = dragOffsetY.floatValue
                                draggingKey = null
                                dragOffsetY.floatValue = 0f

                                val liftToken = ++layoutTracker.liftGeneration
                                scope.launch {
                                    try {
                                        dragProgress.animateTo(
                                            targetValue = 0f,
                                            animationSpec = tween(
                                                durationMillis = 180,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        )
                                    } finally {
                                        if (layoutTracker.liftGeneration == liftToken &&
                                            draggingKey != activeKey
                                        ) {
                                            liftKey = null
                                        }
                                    }
                                }

                                if (abs(finalOffset) > 0.5f) {
                                    val settleToken = ++layoutTracker.settleGeneration
                                    settlingKey = activeKey
                                    scope.launch {
                                        try {
                                            settlingOffsetY.snapTo(finalOffset)
                                            settlingOffsetY.animateTo(
                                                targetValue = 0f,
                                                animationSpec = tween(
                                                    durationMillis = 180,
                                                    easing = FastOutSlowInEasing,
                                                ),
                                            )
                                        } finally {
                                            if (layoutTracker.settleGeneration == settleToken &&
                                                settlingKey == activeKey
                                            ) {
                                                settlingKey = null
                                            }
                                        }
                                    }
                                }

                                if (reordered && workingList != latestItems) {
                                    localOrder = workingList
                                    latestOnReorderFinished(workingList)
                                } else {
                                    localOrder = null
                                }
                            }
                        }
                    }
                },
            ),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        displayedItems.forEachIndexed { index, item ->
            val itemKey = key(item)
            key(itemKey) {
                val isDragging = draggingKey == itemKey
                val isLifted = liftKey == itemKey
                val isSettling = settlingKey == itemKey
                val isShifting = itemKey in activeShiftKeys
                val needsGraphicsLayer = isDragging || isLifted || isSettling || isShifting

                val accessibilityActions = remember(
                    canDrag,
                    index,
                    displayedItems.size,
                    itemLabel(item),
                ) {
                    if (!canDrag) {
                        emptyList()
                    } else {
                        val name = itemLabel(item)
                        buildList {
                            if (index > 0) {
                                add(
                                    CustomAccessibilityAction(
                                        label = if (name.isNotBlank()) "上移$name" else "上移模块",
                                    ) {
                                        moveItemByAccessibility(index, index - 1)
                                        true
                                    },
                                )
                            }
                            if (index < displayedItems.lastIndex) {
                                add(
                                    CustomAccessibilityAction(
                                        label = if (name.isNotBlank()) "下移$name" else "下移模块",
                                    ) {
                                        moveItemByAccessibility(index, index + 1)
                                        true
                                    },
                                )
                            }
                        }
                    }
                }

                val tag = itemTestTag?.invoke(item)
                val baseModifier = Modifier
                    .fillMaxWidth()
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                    .then(
                        if (accessibilityActions.isNotEmpty()) {
                            Modifier.semantics { customActions = accessibilityActions }
                        } else {
                            Modifier
                        },
                    )
                    .onSizeChanged { size ->
                        layoutTracker.heights[itemKey] = size.height
                    }

                val motionModifier = if (!needsGraphicsLayer) {
                    baseModifier
                } else {
                    val shiftAnim = if (isShifting) shiftAnimations[itemKey] else null
                    val isElevated = isDragging || isLifted || isSettling
                    val isGrouped = itemGrouped(item)
                    val activeShape = if (isGrouped) groupedTrayShape else singleCardShape
                    baseModifier
                        .then(
                            when {
                                isDragging -> Modifier.zIndex(2f)
                                isSettling || isLifted -> Modifier.zIndex(1f)
                                else -> Modifier
                            },
                        )
                        .graphicsLayer {
                            translationY = when {
                                isDragging -> dragOffsetY.floatValue
                                isSettling -> settlingOffsetY.value
                                shiftAnim != null -> shiftAnim.value
                                else -> 0f
                            }
                            if (isElevated) {
                                val p = dragProgress.value
                                val s = 1f + 0.02f * p
                                scaleX = s
                                scaleY = s
                                alpha = visualSpec.layerAlpha
                                if (!visualSpec.isGlass) {
                                    shape = activeShape
                                    shadowElevation = maxShadowElevationPx * p
                                    ambientShadowColor = visualSpec.m3ShadowColor
                                    spotShadowColor = visualSpec.m3ShadowColor
                                } else {
                                    shadowElevation = 0f
                                }
                            }
                        }
                        .then(
                            if (isElevated) {
                                Modifier.reorderDragVisuals(
                                    spec = visualSpec,
                                    isGrouped = isGrouped,
                                    hasCustomBackground = hasCustomBackground,
                                    progressProvider = { dragProgress.value },
                                    groupPadXPx = groupPadXPx,
                                    groupPadYPx = groupPadYPx,
                                    cardCornerRadiusPx = cardCornerRadiusPx,
                                    groupCornerRadiusPx = groupCornerRadiusPx,
                                    strokeWidthPx = borderStrokePx,
                                )
                            } else {
                                Modifier
                            },
                        )
                }

                Box(modifier = motionModifier) {
                    itemContent(item, isDragging)
                }
            }
        }
    }
}
