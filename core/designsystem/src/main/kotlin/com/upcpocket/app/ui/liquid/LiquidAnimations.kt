/*
 * Adapted from Kyant0/AndroidLiquidGlass (Apache-2.0) catalog utilities for interactive
 * liquid damped-spring drag & AGSL RuntimeShader touch highlight effects inside UPC Pocket.
 */
package com.upcpocket.app.ui.liquid

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceIn
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Kyant0-faithful damped spring animation controller with VelocityTracker jelly stretch
 * and MutatorMutex concurrency safety.
 */
class LiquidDampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float = 1f,
    val pressedScale: Float = 1.24f,
    private val onDragStarted: LiquidDampedDragAnimation.(position: Offset) -> Unit = {},
    private val onDragStopped: LiquidDampedDragAnimation.() -> Unit = {},
    private val onDrag: LiquidDampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit = { _, _ -> },
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimatable = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimatable = Animatable(0f, 5f)
    private val pressProgressAnimatable = Animatable(0f, 0.001f)
    private val scaleXAnimatable = Animatable(initialScale, 0.001f)
    private val scaleYAnimatable = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimatable.value
    val targetValue: Float get() = valueAnimatable.targetValue
    val progress: Float
        get() {
            val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
            return ((value - valueRange.start) / span).coerceIn(0f, 1f)
        }
    val pressProgress: Float get() = pressProgressAnimatable.value
    val scaleX: Float get() = scaleXAnimatable.value
    val scaleY: Float get() = scaleYAnimatable.value
    val velocity: Float get() = velocityAnimatable.value

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
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimatable.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimatable.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimatable.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.01f)
                val threshold = span * 0.025f
                runCatching {
                    snapshotFlow { valueAnimatable.value }
                        .filter { abs(it - valueAnimatable.targetValue) < threshold }
                        .first()
                }
            }
            launch { velocityAnimatable.animateTo(0f, velocityAnimationSpec) }
            launch { pressProgressAnimatable.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimatable.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimatable.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch {
            valueAnimatable.animateTo(target, valueAnimationSpec) { updateVelocity() }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val target = value.coerceIn(valueRange)
                launch { valueAnimatable.animateTo(target, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimatable.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(System.currentTimeMillis(), Offset(value, 0f))
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
        val targetVelocity = velocityTracker.calculateVelocity().x / span
        animationScope.launch {
            velocityAnimatable.animateTo(targetVelocity, velocityAnimationSpec)
        }
    }
}

private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var drag: PointerInputChange? = null
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
 * Kyant0-faithful interactive specular glow with AGSL RuntimeShader on API 33+ and radial
 * fallback on API 31-32, plus finger offset tracking for jelly directional stretch.
 */
class LiquidInteractiveHighlight(
    private val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimatable = Animatable(0f, 0.001f)
    private val positionAnimatable = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimatable.value
    val offset: Offset get() = positionAnimatable.value - startPosition

    private val shader =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                RuntimeShader(
                    """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}""",
                )
            }.getOrNull()
        } else {
            null
        }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimatable.value
        if (progress > 0f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
                drawRect(
                    Color.White.copy(alpha = 0.08f * progress),
                    blendMode = BlendMode.Plus,
                )
                val glowPos = position(size, positionAnimatable.value)
                shader.apply {
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(alpha = 0.16f * progress).toArgb())
                    setFloatUniform("radius", (size.minDimension * 1.5f).coerceAtLeast(1f))
                    setFloatUniform(
                        "position",
                        glowPos.x.fastCoerceIn(0f, size.width),
                        glowPos.y.fastCoerceIn(0f, size.height),
                    )
                }
                drawRect(
                    ShaderBrush(shader),
                    blendMode = BlendMode.Plus,
                )
            } else {
                val glowPos = position(size, positionAnimatable.value)
                val clamped = Offset(
                    glowPos.x.fastCoerceIn(0f, size.width),
                    glowPos.y.fastCoerceIn(0f, size.height),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.24f * progress),
                            Color.White.copy(alpha = 0.08f * progress),
                            Color.Transparent,
                        ),
                        center = clamped,
                        radius = size.maxDimension.coerceAtLeast(1f),
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            startPosition = down.position
            animationScope.launch {
                launch { pressProgressAnimatable.animateTo(1f, pressProgressAnimationSpec) }
                launch { positionAnimatable.snapTo(startPosition) }
            }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    animationScope.launch {
                        launch { pressProgressAnimatable.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimatable.animateTo(startPosition, positionAnimationSpec) }
                    }
                    break
                }
                if (change.position != change.previousPosition) {
                    animationScope.launch { positionAnimatable.snapTo(change.position) }
                }
            }
        }
    }
}
