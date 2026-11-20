package com.upcpocket.app.campusmap

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.upcpocket.app.plugin.ui.LocalPluginPageActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

private const val SENSOR_INTERVAL_MICROS = 100_000
private const val SENSOR_INTERVAL_NANOS = SENSOR_INTERVAL_MICROS * 1_000L
private const val HEADING_STALE_NANOS = 2_000_000_000L
private val maximumHeadingError = Math.toRadians(35.0).toFloat()

private fun normalizeHeading(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

private fun shortestTurn(previous: Float, heading: Float): Float =
    normalizeHeading(heading - previous + 180f) - 180f

internal fun trueCampusHeading(magneticDegrees: Float, declinationDegrees: Float): Float =
    normalizeHeading(magneticDegrees + declinationDegrees)

internal fun smoothCampusHeading(previous: Float?, heading: Float, fraction: Float = 0.35f): Float =
    if (previous == null) normalizeHeading(heading)
    else normalizeHeading(previous + shortestTurn(previous, heading) * fraction.coerceIn(0f, 1f))

@Composable
internal fun rememberCampusHeading(bounds: MapBounds): State<Float?> {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = LocalPluginPageActive.current
    return produceState<Float?>(null, view, lifecycle, bounds, active) {
        if (!active) {
            value = null
            return@produceState
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val manager = view.context.getSystemService(SensorManager::class.java) ?: return@repeatOnLifecycle
            manager.observeHeading(view, bounds) { value = it }
        }
    }
}

private suspend fun SensorManager.observeHeading(view: View, bounds: MapBounds, publish: (Float?) -> Unit) {
    // Campus coordinates suffice for local magnetic declination. No device location is read here.
    val declination = GeomagneticField(
        ((bounds.south + bounds.north) / 2).toFloat(),
        ((bounds.west + bounds.east) / 2).toFloat(),
        0f,
        System.currentTimeMillis(),
    ).declination
    val deviceMatrix = FloatArray(9)
    val screenMatrix = FloatArray(9)
    val orientation = FloatArray(3)
    var screenRotation: Int? = null
    var lastRead = 0L
    var lastValidReading = 0L
    var previous: Float? = null

    fun clearHeading() {
        previous = null
        lastValidReading = 0L
        publish(null)
    }

    val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) clearHeading()
        }

        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastRead < SENSOR_INTERVAL_NANOS) return
            lastRead = now

            val values = event.values
            val rotation = view.display?.rotation
            val headingError = values.getOrNull(4)
            if (rotation == null || event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ||
                now - event.timestamp !in 0L..HEADING_STALE_NANOS || values.size < 3 ||
                (0 until minOf(values.size, 4)).any { !values[it].isFinite() } ||
                headingError?.let { !it.isFinite() || it > maximumHeadingError } == true
            ) {
                clearHeading()
                return
            }
            if (screenRotation != rotation) {
                screenRotation = rotation
                previous = null
            }
            SensorManager.getRotationMatrixFromVector(deviceMatrix, values)
            if (!remapToCampusScreen(rotation, deviceMatrix, screenMatrix)) {
                clearHeading()
                return
            }

            // When the top edge points nearly straight up, its horizontal bearing is unstable.
            val east = screenMatrix[1]
            val north = screenMatrix[4]
            if (east * east + north * north < 0.04f) {
                clearHeading()
                return
            }
            val magnetic = Math.toDegrees(SensorManager.getOrientation(screenMatrix, orientation)[0].toDouble()).toFloat()
            val heading = trueCampusHeading(magnetic, declination)
            if (!heading.isFinite()) {
                clearHeading()
                return
            }

            lastValidReading = now
            val before = previous
            val smoothed = smoothCampusHeading(before, heading)
            if (before == null || abs(shortestTurn(before, smoothed)) >= 0.75f) {
                previous = smoothed
                publish(smoothed)
            }
        }
    }

    val sensorTypes = intArrayOf(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    val registered = sensorTypes.any { type ->
        val sensor = getDefaultSensor(type) ?: return@any false
        try {
            registerListener(listener, sensor, SENSOR_INTERVAL_MICROS)
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
    if (!registered) return

    try {
        val context = currentCoroutineContext()
        while (context.isActive) {
            delay(1_000)
            if (previous != null &&
                (SystemClock.elapsedRealtimeNanos() - lastValidReading > HEADING_STALE_NANOS ||
                    view.display?.rotation != screenRotation)
            ) {
                clearHeading()
            }
        }
    } finally {
        unregisterListener(listener)
        clearHeading()
    }
}

private fun remapToCampusScreen(rotation: Int, input: FloatArray, output: FloatArray): Boolean = when (rotation) {
    Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_X, SensorManager.AXIS_Y, output)
    Surface.ROTATION_90 ->
        SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, output)
    Surface.ROTATION_180 ->
        SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, output)
    Surface.ROTATION_270 ->
        SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, output)
    else -> false
}

@Composable
internal fun CampusCompassIndicator(heading: State<Float?>) {
    val live by remember(heading) { derivedStateOf { heading.value != null } }
    val northColor = MaterialTheme.colorScheme.error
    val staticColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                contentDescription = if (live) "指南针指向真北" else "地图上方为北，设备朝向暂不可用"
            }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).graphicsLayer().drawWithCache {
                val midX = size.width / 2
                val midY = size.height / 2
                val north = Path().apply {
                    moveTo(midX, 0f)
                    lineTo(midX + size.width * 0.24f, midY)
                    lineTo(midX - size.width * 0.24f, midY)
                    close()
                }
                val south = Path().apply {
                    moveTo(midX, size.height)
                    lineTo(midX + size.width * 0.24f, midY)
                    lineTo(midX - size.width * 0.24f, midY)
                    close()
                }
                onDrawBehind {
                    val azimuth = heading.value
                    if (azimuth == null) {
                        drawPath(north, staticColor)
                    } else {
                        rotate(-azimuth) {
                            drawPath(south, staticColor)
                            drawPath(north, northColor)
                        }
                    }
                }
            },
        )
        Text("北", style = MaterialTheme.typography.labelMedium)
    }
}
