/*
 * Adapted from Kyant0/AndroidLiquidGlass (Apache-2.0) catalog utilities for interactive
 * liquid damped-spring drag & touch highlight effects inside UPC Pocket.
 */
package com.upcpocket.app.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceIn
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Damped spring animation controller for Kyant-style liquid pill dragging and press scaling.
 */
class LiquidDampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float = 1f,
    val pressedScale: Float = 1.08f,
    private val onDragStarted: LiquidDampedDragAnimation.(position: Offset) -> Unit = {},
    private val onDragStopped: LiquidDampedDragAnimation.() -> Unit = {},
    private val onDrag: LiquidDampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit = { _, _ -> },
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.3f, 200f, 0.001f)
    private val scaleYAnimationSpec = spring(0.5f, 300f, 0.001f)

    private val valueAnimatable = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimatable = Animatable(0f, visibilityThreshold * 10f)
    private val pressProgressAnimatable = Animatable(0f, 0.001f)
    private val scaleXAnimatable = Animatable(initialScale, 0.001f)
    private val scaleYAnimatable = Animatable(1f, 0.001f)

    val value: Float get() = valueAnimatable.value
    val progress: Float
        get() = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
            .coerceIn(0f, 1f)
    val pressProgress: Float get() = pressProgressAnimatable.value
    val scaleX: Float get() = scaleXAnimatable.value
    val scaleY: Float get() = scaleYAnimatable.value

    private val velocity: Float get() = velocityAnimatable.value
    val angularVelocity: Float get() = (velocity / 2000f) * (180f / Math.PI.toFloat())

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        animationScope.launch {
            launch { pressProgressAnimatable.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimatable.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimatable.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            if (value != targetValue) {
                launch { valueAnimatable.animateTo(targetValue, valueAnimationSpec) }
            }
            launch { velocityAnimatable.animateTo(0f, velocityAnimationSpec) }
            launch { pressProgressAnimatable.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimatable.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimatable.animateTo(1f, scaleYAnimationSpec) }
        }
    }

    private var targetValue = initialValue

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        this.targetValue = targetValue
        animationScope.launch {
            launch { valueAnimatable.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        this.targetValue = targetValue
        press()
        animationScope.launch {
            try {
                valueAnimatable.animateTo(targetValue, valueAnimationSpec) { updateVelocity() }
            } finally {
                release()
            }
        }
    }

    private fun updateVelocity() {
        animationScope.launch {
            velocityAnimatable.animateTo(valueAnimatable.velocity, velocityAnimationSpec)
        }
    }
}

private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: androidx.compose.ui.input.pointer.PointerInputChange) -> Unit = {},
    onDragEnd: (change: androidx.compose.ui.input.pointer.PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var drag: androidx.compose.ui.input.pointer.PointerInputChange? = null
        var overSlop = Offset.Zero
        val touchSlop = viewConfiguration.touchSlop
        var total = Offset.Zero
        while (drag == null) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.position - change.previousPosition
            total += delta
            if (total.getDistance() > touchSlop) {
                drag = change
                overSlop = total
            }
        }
        if (drag != null) {
            onDragStart(down)
            onDrag(drag, overSlop)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == drag.id }
                if (change == null) {
                    onDragCancel()
                    break
                }
                if (!change.pressed) {
                    onDragEnd(change)
                    break
                }
                val delta = change.position - change.previousPosition
                if (delta != Offset.Zero) {
                    onDrag(change, delta)
                }
            }
        }
    }
}

/**
 * Interactive specular press glow overlay (radial illumination + subtle stretch) inspired by
 * Kyant0/AndroidLiquidGlass.
 */
class LiquidInteractiveHighlight(
    private val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressProgressAnimatable = Animatable(0f, 0.001f)
    private val positionAnimatable = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimatable.value

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimatable.animateTo(1f, spring(0.5f, 300f, 0.001f)) }
                    launch { positionAnimatable.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                animationScope.launch {
                    launch { pressProgressAnimatable.animateTo(0f, spring(0.5f, 300f, 0.001f)) }
                    launch { positionAnimatable.animateTo(startPosition, spring(0.5f, 300f, Offset.VisibilityThreshold)) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    launch { pressProgressAnimatable.animateTo(0f, spring(0.5f, 300f, 0.001f)) }
                    launch { positionAnimatable.animateTo(startPosition, spring(0.5f, 300f, Offset.VisibilityThreshold)) }
                }
            },
        ) { change, _ ->
            animationScope.launch { positionAnimatable.snapTo(change.position) }
        }
    }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimatable.value
        if (progress <= 0f) {
            drawContent()
            return@drawWithContent
        }
        drawContent()
        val glowCenter = position(size, positionAnimatable.value)
        val clamped = Offset(
            glowCenter.x.fastCoerceIn(0f, size.width),
            glowCenter.y.fastCoerceIn(0f, size.height),
        )
        val radius = size.maxDimension * 0.85f
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.24f * progress),
                    Color.White.copy(alpha = 0.08f * progress),
                    Color.Transparent,
                ),
                center = clamped,
                radius = radius.coerceAtLeast(1f),
            ),
            blendMode = BlendMode.Plus,
        )
    }

    companion object {
        fun computeInteractiveOffset(size: Size, pointer: Offset): Offset {
            val center = Offset(size.width / 2f, size.height / 2f)
            val offset = pointer - center
            val distance = offset.getDistance()
            if (distance <= 0.001f) return Offset.Zero
            val maxAxis = size.maxDimension.coerceAtLeast(1f)
            val norm = tanh((distance / maxAxis) * 1.8f)
            val angle = atan2(offset.y, offset.x)
            return Offset(cos(angle), sin(angle)) * (abs(norm) * 4f)
        }
    }
}
