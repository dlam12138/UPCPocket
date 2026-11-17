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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Kyant0-faithful damped spring animation controller with 1:1 zero-lag active finger tracking,
 * pointer-clock VelocityTracker jelly stretch, and MutatorMutex concurrency safety.
 */
class LiquidDampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float = 1f,
    val pressedScale: Float = 1.24f,
    private val horizontalOnly: Boolean = false,
    private val slopFraction: Float = 0.25f,
    private val onDragStarted: LiquidDampedDragAnimation.(position: Offset) -> Unit = {},
    private val onDragStopped: LiquidDampedDragAnimation.() -> Unit = {},
    private val onDrag: LiquidDampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit = { _, _ -> },
) {
    private val valueAnimationSpec = spring(0.78f, 1400f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.65f, 650f, visibilityThreshold * 10f)
    private val pressInAnimationSpec = spring(0.88f, 2600f, 0.001f)
    private val pressOutAnimationSpec = spring(0.75f, 1400f, 0.001f)
    private val scaleXAnimationSpec = spring(0.62f, 550f, 0.001f)
    private val scaleYAnimationSpec = spring(0.68f, 550f, 0.001f)

    private val valueAnimatable = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimatable = Animatable(0f, 0.5f)
    private val pressProgressAnimatable = Animatable(0f, 0.001f)
    private val scaleXAnimatable = Animatable(initialScale, 0.001f)
    private val scaleYAnimatable = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()
    private var lastPointerUptimeMs: Long = 0L
    var isDragging: Boolean = false
        private set

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

    val modifier: Modifier = Modifier.pointerInput(this, horizontalOnly, slopFraction) {
        inspectDragGestures(
            horizontalOnly = horizontalOnly,
            slopFraction = slopFraction,
            onDragStart = { down ->
                isDragging = true
                lastPointerUptimeMs = down.uptimeMillis
                onDragStarted(down.position)
                press(down.uptimeMillis)
            },
            onDragEnd = {
                isDragging = false
                onDragStopped()
                release()
            },
            onDragCancel = {
                isDragging = false
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            lastPointerUptimeMs = change.uptimeMillis
            onDrag(size, dragAmount)
        }
    }

    fun press(uptimeMillis: Long = 0L) {
        velocityTracker.resetTracking()
        if (uptimeMillis > 0L) {
            velocityTracker.addPosition(uptimeMillis, Offset(valueAnimatable.targetValue, 0f))
        }
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch { pressProgressAnimatable.animateTo(1f, pressInAnimationSpec) }
            launch { scaleXAnimatable.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimatable.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch { velocityAnimatable.animateTo(0f, velocityAnimationSpec) }
            launch { pressProgressAnimatable.animateTo(0f, pressOutAnimationSpec) }
            launch { scaleXAnimatable.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimatable.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    /**
     * 1:1 实时跟手拖动：直接贴合手指位移而不叠加弹簧延迟，避免滑动粘滞感。
     */
    fun dragTo(value: Float) {
        val target = value.coerceIn(valueRange)
        recordVelocity(target, lastPointerUptimeMs)
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimatable.snapTo(target)
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        recordVelocity(target, lastPointerUptimeMs)
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimatable.animateTo(target, valueAnimationSpec)
        }
    }

    fun animateToValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutatorMutex.mutate {
                press()
                launch { valueAnimatable.animateTo(target, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimatable.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun recordVelocity(target: Float, uptimeMillis: Long) {
        if (uptimeMillis <= 0L) return
        velocityTracker.addPosition(uptimeMillis, Offset(target, 0f))
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
        val raw = velocityTracker.calculateVelocity().x
        if (!raw.isFinite()) return
        val targetVelocity = (raw / span).fastCoerceIn(-18f, 18f)
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            velocityAnimatable.snapTo(targetVelocity)
        }
    }
}

private suspend fun PointerInputScope.inspectDragGestures(
    horizontalOnly: Boolean = false,
    slopFraction: Float = 0.25f,
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var drag: PointerInputChange? = null
        var overSlop = Offset.Zero
        val fullSlop = viewConfiguration.touchSlop
        val engageSlop = (fullSlop * slopFraction).coerceAtLeast(2f)
        var total = Offset.Zero
        while (drag == null) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.position - change.previousPosition
            total += delta
            if (horizontalOnly) {
                val absX = abs(total.x)
                val absY = abs(total.y)
                if (absY > fullSlop && absY > absX * 1.35f) {
                    // 让垂直列表顺畅接管滚动，不拦截上下滑动
                    break
                }
                if (absX > engageSlop && absX >= absY * 0.75f) {
                    drag = change
                    overSlop = total
                    change.consume()
                }
            } else if (total.getDistance() > engageSlop) {
                drag = change
                overSlop = total
                change.consume()
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
                    change.consume()
                    onDragEnd(change)
                    break
                }
                val delta = change.position - change.previousPosition
                if (delta != Offset.Zero) {
                    change.consume()
                    onDrag(change, delta)
                }
            }
        }
    }
}

/**
 * Kyant0-faithful interactive specular glow with AGSL RuntimeShader on API 33+ and radial
 * fallback on API 31-32, plus zero-lag live finger position tracking for crisp button response.
 */
class LiquidInteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val cancelOnConsumed: Boolean = true,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressInAnimationSpec = spring(0.88f, 3200f, 0.001f)
    private val pressOutAnimationSpec = spring(0.62f, 950f, 0.001f)
    private val positionReturnAnimationSpec = spring(0.62f, 950f, Offset.VisibilityThreshold)

    private val pressProgressAnimatable = Animatable(0f, 0.001f)
    private val positionAnimatable = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    private var trackingLive by mutableStateOf(false)
    private var livePosition by mutableStateOf(Offset.Zero)

    val pressProgress: Float get() = pressProgressAnimatable.value
    val offset: Offset
        get() = (if (trackingLive) livePosition else positionAnimatable.value) - startPosition

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
    float intensity = smoothstep(radius, radius * 0.45, dist);
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
            val currentPos = if (trackingLive) livePosition else positionAnimatable.value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
                drawRect(
                    Color.White.copy(alpha = 0.12f * progress),
                    blendMode = BlendMode.Plus,
                )
                val glowPos = position(size, currentPos)
                shader.apply {
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(alpha = 0.22f * progress).toArgb())
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
                val glowPos = position(size, currentPos)
                val clamped = Offset(
                    glowPos.x.fastCoerceIn(0f, size.width),
                    glowPos.y.fastCoerceIn(0f, size.height),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.28f * progress),
                            Color.White.copy(alpha = 0.10f * progress),
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
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            startPosition = down.position
            livePosition = down.position
            trackingLive = true
            animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                positionAnimatable.snapTo(down.position)
                pressProgressAnimatable.animateTo(1f, pressInAnimationSpec)
            }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || (cancelOnConsumed && change.isConsumed)) {
                    val releaseFrom = livePosition
                    trackingLive = false
                    animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        launch { pressProgressAnimatable.animateTo(0f, pressOutAnimationSpec) }
                        launch {
                            positionAnimatable.snapTo(releaseFrom)
                            positionAnimatable.animateTo(startPosition, positionReturnAnimationSpec)
                        }
                    }
                    break
                }
                if (change.position != livePosition) {
                    livePosition = change.position
                }
            }
        }
    }
}
