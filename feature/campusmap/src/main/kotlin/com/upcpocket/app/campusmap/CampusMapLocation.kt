package com.upcpocket.app.campusmap

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class CampusLocationAccess { NONE, APPROXIMATE, PRECISE }

internal enum class CampusLocationStatus {
    OFF, WAITING, NO_FIX, ACCURATE, LOW_ACCURACY, APPROXIMATE, DISABLED, UNAVAILABLE, DENIED,
}

internal val campusLocationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

internal fun Context.campusLocationAccess(): CampusLocationAccess = when {
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED -> CampusLocationAccess.PRECISE
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED -> CampusLocationAccess.APPROXIMATE
    else -> CampusLocationAccess.NONE
}

internal tailrec fun Context.campusActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.campusActivity()
    else -> null
}

private const val DISPLAY_FIX_AGE_MS = 15_000L
private const val REQUEST_INTERVAL_MS = 1_000L
private const val FIRST_FIX_WAIT_MS = 10_000L
private const val PROVIDER_RETRY_MS = 5_000L
private const val ACCURACY_HOLD_MS = 4_000L
private const val ACCURACY_DROP_METERS = 8.0

internal fun CampusFix.usableForCampusNavigation(): Boolean =
    canNavigate(SystemClock.elapsedRealtime())

private data class LocationSample(
    val status: CampusLocationStatus,
    val fix: CampusFix? = null,
)

@Composable
internal fun ObserveCampusLocation(
    enabled: Boolean,
    onLocation: (CampusFix?) -> Unit,
    onStatus: (CampusLocationStatus) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val deliverLocation by rememberUpdatedState(onLocation)
    val deliverStatus by rememberUpdatedState(onStatus)
    LaunchedEffect(context, owner, enabled) {
        if (!enabled) {
            deliverLocation(null)
            deliverStatus(CampusLocationStatus.OFF)
            return@LaunchedEffect
        }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                context.foregroundLocations().distinctUntilChanged().collect { sample ->
                    deliverStatus(sample.status)
                    deliverLocation(sample.fix)
                }
            } finally {
                deliverLocation(null)
                deliverStatus(CampusLocationStatus.OFF)
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun Context.foregroundLocations() = callbackFlow {
    val manager = getSystemService(LocationManager::class.java)
    if (manager == null) {
        trySend(LocationSample(CampusLocationStatus.UNAVAILABLE))
        awaitClose {}
        return@callbackFlow
    }
    val selection = CampusFixSelection()
    val callbackExecutor = ContextCompat.getMainExecutor(this@foregroundLocations)
    var requested = emptyList<String>()
    var registered = emptyList<String>()
    var lastAccess = CampusLocationAccess.NONE
    var acquisitionStartedAt = SystemClock.elapsedRealtime()
    var retryAt = 0L
    val listener = LocationListener { location ->
        val access = campusLocationAccess()
        val fix = location.toCampusFix(access) ?: return@LocationListener
        selection.offer(fix)
        selection.current(SystemClock.elapsedRealtime())?.let { selected ->
            trySend(LocationSample(selected.locationStatus(), selected))
        }
    }
    val monitor = launch {
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            val access = campusLocationAccess()
            val locationEnabled = manager.isLocationEnabled
            val wanted = if (locationEnabled && access != CampusLocationAccess.NONE) {
                val providers = manager.getProviders(true)
                val permitted = if (access == CampusLocationAccess.PRECISE) {
                    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
                } else {
                    listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
                }
                permitted.filter { it in providers }
            } else {
                emptyList()
            }
            if (wanted != requested || access != lastAccess) {
                manager.removeUpdates(listener)
                selection.clear()
                requested = wanted
                registered = emptyList()
                lastAccess = access
                retryAt = now
            }
            if (registered.size < requested.size && now >= retryAt) {
                val request = LocationRequest.Builder(REQUEST_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(REQUEST_INTERVAL_MS)
                    .setQuality(
                        if (access == CampusLocationAccess.PRECISE) LocationRequest.QUALITY_HIGH_ACCURACY
                        else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY,
                    )
                    .build()
                // Retry failed providers without interrupting the ones already delivering fixes.
                val added = requested.filterNot { it in registered }.filter { provider ->
                    try {
                        manager.requestLocationUpdates(provider, request, callbackExecutor, listener)
                        true
                    } catch (_: SecurityException) {
                        false
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                }
                if (registered.isEmpty() && added.isNotEmpty()) acquisitionStartedAt = now
                registered += added
                retryAt = now + PROVIDER_RETRY_MS
            }
            val fix = selection.current(SystemClock.elapsedRealtime())
            val status = when {
                access == CampusLocationAccess.NONE -> CampusLocationStatus.DENIED
                !locationEnabled -> CampusLocationStatus.DISABLED
                registered.isEmpty() -> CampusLocationStatus.UNAVAILABLE
                fix == null && now - acquisitionStartedAt >= FIRST_FIX_WAIT_MS -> CampusLocationStatus.NO_FIX
                fix == null -> CampusLocationStatus.WAITING
                else -> fix.locationStatus()
            }
            trySend(LocationSample(status, fix))
            delay(REQUEST_INTERVAL_MS)
        }
    }
    awaitClose {
        monitor.cancel()
        manager.removeUpdates(listener)
    }
}

internal class CampusFixSelection {
    private var newest: CampusFix? = null
    private var displayed: CampusFix? = null

    fun offer(fix: CampusFix) {
        val previous = newest
        if (previous == null || fix.elapsedRealtimeMillis > previous.elapsedRealtimeMillis ||
            fix.elapsedRealtimeMillis == previous.elapsedRealtimeMillis && fix.accuracy < previous.accuracy
        ) {
            newest = fix
        }
    }

    fun current(now: Long): CampusFix? {
        val candidate = newest?.takeIf { now - it.elapsedRealtimeMillis in 0L..DISPLAY_FIX_AGE_MS }
        val previous = displayed
        // Hold a recent, usable fix briefly when another provider reports much poorer accuracy.
        // Keep the newer report so the UI can show degraded accuracy when the hold expires.
        if (candidate != null && previous != null && previous.canNavigate(now) &&
            now - previous.elapsedRealtimeMillis <= ACCURACY_HOLD_MS &&
            candidate.accuracy - previous.accuracy >= ACCURACY_DROP_METERS
        ) {
            return previous
        }
        return candidate.also { displayed = it }
    }

    fun clear() {
        newest = null
        displayed = null
    }
}

private fun Location.toCampusFix(access: CampusLocationAccess): CampusFix? {
    if (access == CampusLocationAccess.NONE || !hasAccuracy() ||
        !accuracy.isFinite() || accuracy <= 0f || latitude !in -90.0..90.0 || longitude !in -180.0..180.0
    ) return null
    val timestamp = elapsedRealtimeNanos / 1_000_000L
    if (SystemClock.elapsedRealtime() - timestamp !in 0L..DISPLAY_FIX_AGE_MS) return null
    return CampusFix(
        point = GeoPoint(longitude, latitude),
        accuracy = accuracy.toDouble(),
        elapsedRealtimeMillis = timestamp,
        precise = access == CampusLocationAccess.PRECISE,
        bearing = bearing.takeIf {
            hasBearing() && it.isFinite() && hasSpeed() && speed.isFinite() && speed >= 1f &&
                hasBearingAccuracy() && bearingAccuracyDegrees in 0f..35f
        },
    )
}

private fun CampusFix.locationStatus(): CampusLocationStatus = when {
    !precise -> CampusLocationStatus.APPROXIMATE
    !usableForCampusNavigation() -> CampusLocationStatus.LOW_ACCURACY
    else -> CampusLocationStatus.ACCURATE
}
