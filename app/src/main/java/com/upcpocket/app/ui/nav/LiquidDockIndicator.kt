package com.upcpocket.app.ui.nav

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.collectLatest

private class LiquidDockMotion {
    var tail by mutableFloatStateOf(0f)
    var drift by mutableFloatStateOf(0f)
}

private val dockRebound = spring(dampingRatio = 0.47f, stiffness = 285f, visibilityThreshold = 0.008f)

@Composable
private fun rememberLiquidDockMotion(pager: PagerState): LiquidDockMotion {
    val motion = remember(pager) { LiquidDockMotion() }
    LaunchedEffect(pager) {
        var approach = 0f
        var distance = 0f
        snapshotFlow { pager.isScrollInProgress }.collectLatest { isScrolling ->
            if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
                motion.tail = 0f
                motion.drift = 0f
                return@collectLatest
            }
            if (isScrolling) {
                var previousPosition = pager.dockPosition()
                var previousFrame = 0L
                approach = 0f
                distance = 0f
                while (pager.isScrollInProgress) {
                    withFrameNanos { time ->
                        val position = pager.dockPosition()
                        val delta = position - previousPosition
                        val seconds = if (previousFrame == 0L) {
                            1f / 60f
                        } else {
                            ((time - previousFrame) / 1_000_000_000f).coerceIn(0.008f, 0.064f)
                        }
                        val response = (seconds * 25f).coerceAtMost(1f)
                        val targetTail = (delta / seconds * 0.22f).coerceIn(-1f, 1f)
                        motion.tail += (targetTail - motion.tail) * response
                        motion.drift *= 1f - response
                        if (abs(delta) >= 0.008f) approach = delta.sign
                        distance += abs(delta)
                        previousPosition = position
                        previousFrame = time
                    }
                }
            } else {
                if (distance >= 0.12f && approach != 0f) {
                    // 页面到位后仍给指示器一点同向速度。下一次滚动会从当前形状接续。
                    animate(
                        initialValue = motion.drift,
                        targetValue = 0f,
                        initialVelocity = approach * 27f,
                        animationSpec = dockRebound,
                    ) { drift, velocity ->
                        motion.drift = drift
                        motion.tail += ((velocity / 58f).coerceIn(-0.42f, 0.42f) - motion.tail) * 0.38f
                    }
                }
                motion.tail = 0f
                motion.drift = 0f
            }
        }
    }
    return motion
}

private fun PagerState.dockPosition(): Float = currentPage + currentPageOffsetFraction

/** 在图标下面绘制同一块玻璃；拖尾与主体共用一条轮廓。 */
@Composable
internal fun Modifier.liquidDockIndicator(
    pager: PagerState,
    tabCount: Int,
    fill: Color,
    rim: BorderStroke,
): Modifier {
    val motion = rememberLiquidDockMotion(pager)
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val topTint = lerp(fill, Color.White, if (dark) 0.19f else 0.51f)
    val bottomTint = lerp(fill, scheme.primary, if (dark) 0.30f else 0.25f)

    return drawWithCache {
        val slotWidth = size.width / tabCount
        val edgeMargin = 8.dp.toPx().coerceAtMost(slotWidth * 0.16f)
        val halfWidth = (minOf(56.dp.toPx(), slotWidth - edgeMargin * 2f)) / 2f
        val restingHalfHeight = minOf(20.dp.toPx(), size.height / 2f)
        val centerY = size.height / 2f
        val tailLimit = minOf(26.dp.toPx(), slotWidth * 0.36f)
        val driftLimit = minOf(8.dp.toPx(), slotWidth * 0.11f)
        val layoutSign = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
        val path = Path()
        val paint = Brush.verticalGradient(
            0f to topTint,
            0.46f to fill,
            1f to bottomTint,
            startY = centerY - restingHalfHeight,
            endY = centerY + restingHalfHeight,
        )
        val stroke = Stroke(rim.width.toPx())

        onDrawBehind {
            if (halfWidth <= 0f || restingHalfHeight <= 0f) return@onDrawBehind

            val page = pager.dockPosition().coerceIn(0f, (tabCount - 1).toFloat())
            val slotCenter = slotWidth * (page + 0.5f)
            val restingCenter = if (layoutSign > 0f) slotCenter else size.width - slotCenter
            val drift = layoutSign * motion.drift.coerceIn(-1f, 1f) * driftLimit
            val centerX = (restingCenter + drift).coerceIn(halfWidth + edgeMargin, size.width - halfWidth - edgeMargin)
            val tail = motion.tail * layoutSign
            val direction = if (tail >= 0f) 1f else -1f
            val trailingRoom = (if (tail >= 0f) centerX else size.width - centerX) - halfWidth - edgeMargin
            val tailLength = (abs(tail) * tailLimit).coerceAtMost(trailingRoom.coerceAtLeast(0f))
            val strength = tailLength / tailLimit
            val halfHeight = restingHalfHeight * (1f - strength * 0.10f)

            path.setWaterDrop(centerX, centerY, halfWidth, halfHeight, direction, tailLength, strength)
            drawPath(path, paint)
            drawPath(path, rim.brush, style = stroke)
        }
    }
}

private fun Path.setWaterDrop(
    centerX: Float,
    centerY: Float,
    halfWidth: Float,
    halfHeight: Float,
    direction: Float,
    tailLength: Float,
    strength: Float,
) {
    val radius = minOf(halfWidth, halfHeight)
    val top = centerY - halfHeight
    val bottom = centerY + halfHeight
    val nose = centerX + halfWidth * direction
    val shoulder = nose - radius * direction
    val rearShoulder = centerX - (halfWidth - radius) * direction
    val tip = centerX - (halfWidth + tailLength) * direction
    // 圆头用贝塞尔圆弧。尾端越长，端点的上下控制柄越短，轮廓就越尖。
    val roundX = radius * 0.5523f * direction
    val roundY = halfHeight * 0.5523f
    val taperX = (radius * 0.5523f + tailLength * 0.38f) * direction
    val taperY = roundY * (1f - strength * 0.83f)

    rewind()
    moveTo(shoulder, top)
    cubicTo(shoulder + roundX, top, nose, centerY - roundY, nose, centerY)
    cubicTo(nose, centerY + roundY, shoulder + roundX, bottom, shoulder, bottom)
    lineTo(rearShoulder, bottom)
    cubicTo(rearShoulder - taperX, bottom, tip, centerY + taperY, tip, centerY)
    cubicTo(tip, centerY - taperY, rearShoulder - taperX, top, rearShoulder, top)
    close()
}
