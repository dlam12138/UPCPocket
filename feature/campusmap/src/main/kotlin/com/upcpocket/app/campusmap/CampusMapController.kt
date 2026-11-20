package com.upcpocket.app.campusmap

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class NavigationStatus {
    IDLE, PLANNING, READY, NAVIGATING, WAITING_LOCATION, REROUTING, ARRIVED, NO_ROUTE,
}

internal data class CampusFix(
    val point: GeoPoint,
    val accuracy: Double,
    val elapsedRealtimeMillis: Long,
    val precise: Boolean,
    val bearing: Float? = null,
) {
    fun canNavigate(now: Long): Boolean =
        precise && accuracy > 0 && accuracy <= 30.0 && now - elapsedRealtimeMillis in 0..15_000L
}

internal data class CampusMapState(
    val campus: CampusMapData? = null,
    val campusId: CampusId,
    val loadFailed: Boolean = false,
    val destination: CampusPlace? = null,
    val manualStart: CampusPlace? = null,
    val fix: CampusFix? = null,
    val mode: TravelMode = TravelMode.WALK,
    val routes: List<CampusRoute> = emptyList(),
    val selectedRouteId: String? = null,
    val status: NavigationStatus = NavigationStatus.IDLE,
    val notice: String? = null,
    val guidance: CampusGuidance? = null,
    val unresolvedPlace: String? = null,
)

internal class CampusMapController(
    context: Context,
    private val scope: CoroutineScope,
    initialCampus: CampusId,
) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(CampusMapState(campusId = initialCampus))
    val state = mutableState.asStateFlow()

    private var maps: Map<CampusId, CampusMapData> = emptyMap()
    private var routingJob: Job? = null
    private var routingGeneration = 0L
    private var loadGeneration = 0L
    private var plannedFrom: GeoPoint? = null
    private var following: CampusRoute? = null
    private var startPending = false
    private var progressMeters = 0.0
    private var lastAcceptedFix = 0L
    private var offRouteCount = 0
    private var firstOffRouteAt = 0L
    private var lastRerouteAt: Long? = null
    private var arrivalFixes = 0

    suspend fun load(requestedPlace: String?) {
        val generation = ++loadGeneration
        mutableState.value = mutableState.value.copy(loadFailed = false)
        val data = try {
            CampusMapDataSource.load(appContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == loadGeneration) mutableState.value = mutableState.value.copy(loadFailed = true)
            return
        }
        if (generation != loadGeneration) return
        maps = data
        val current = mutableState.value
        val requested = requestedPlace?.trim()?.takeIf { it.isNotEmpty() }
        val match = requested?.let { resolveTimetablePlace(it, data.values) }
        val campus = match?.first ?: data.getValue(current.campusId)
        mutableState.value = current.copy(
            campusId = campus.id,
            campus = campus,
            destination = match?.second,
            unresolvedPlace = requested.takeIf { match == null },
        )
    }

    fun selectCampus(id: CampusId) {
        val current = mutableState.value
        if (id == current.campusId || maps[id] == null) return
        resetNavigation()
        mutableState.value = CampusMapState(campus = maps.getValue(id), campusId = id, fix = current.fix, mode = current.mode)
    }

    fun choosePlace(place: CampusPlace, asStart: Boolean = false) {
        val current = mutableState.value
        val site = current.campus?.places?.firstOrNull { it.id == place.id && !it.isEntrance } ?: return
        resetNavigation()
        mutableState.value = current.withoutRoute().copy(
            destination = if (asStart) current.destination else site,
            manualStart = if (asStart) site else current.manualStart,
            unresolvedPlace = null,
        )
    }

    fun useCurrentStart() {
        val current = mutableState.value
        resetNavigation()
        mutableState.value = current.withoutRoute().copy(manualStart = null)
    }

    fun setMode(mode: TravelMode) {
        val current = mutableState.value
        if (mode == current.mode || following != null) return
        val shouldReplan = current.status in setOf(NavigationStatus.READY, NavigationStatus.PLANNING, NavigationStatus.NO_ROUTE)
        resetNavigation()
        mutableState.value = current.withoutRoute().copy(mode = mode)
        if (shouldReplan) plan()
    }

    fun plan() {
        val current = mutableState.value
        val campus = current.campus ?: return
        val destination = current.destination ?: return reject("请先选择目的地")
        val targets = campus.routeTargets(destination)
        if (targets.isEmpty()) return reject("目的地位置待核，请选择地图上已有位置的地点")
        val manual = current.manualStart
        val from = if (manual == null) {
            val fix = current.fix
            if (fix?.canNavigate(SystemClock.elapsedRealtime()) != true) {
                return reject("请开启精确定位并等待定位稳定，或手动选择校内起点")
            }
            fix.point
        } else {
            campus.routeTarget(manual)?.point ?: return reject("此起点暂无可用道路，请选择其他起点")
        }
        resetNavigation()
        requestRoutes(from, targets, continueNavigation = false, startFromPlace = manual != null)
    }

    fun selectRoute(id: String) {
        val current = mutableState.value
        if (current.status != NavigationStatus.READY || current.routes.none { it.id == id }) return
        mutableState.value = current.copy(selectedRouteId = id)
    }

    fun startNavigation() {
        val current = mutableState.value
        if (current.status != NavigationStatus.READY && !startPending) return
        val route = current.routes.firstOrNull { it.id == current.selectedRouteId } ?: return
        val fix = current.fix?.takeIf { it.canNavigate(SystemClock.elapsedRealtime()) }
        if (fix == null) {
            startPending = true
            mutableState.value = current.copy(status = NavigationStatus.WAITING_LOCATION, notice = "等待可信的精确定位")
            return
        }
        val campus = current.campus ?: return
        startPending = false
        if (!campus.contains(fix.point)) {
            mutableState.value = current.copy(status = NavigationStatus.READY, notice = "进入当前校区后可开始导航")
            return
        }
        val distanceToStart = fix.point.distanceTo(route.points.first())
        if (distanceToStart > startTolerance(fix)) {
            val planAlreadyUsesThisPosition = current.manualStart == null &&
                plannedFrom?.distanceTo(fix.point)?.let { it <= max(12.0, fix.accuracy) } == true
            if (planAlreadyUsesThisPosition) {
                mutableState.value = current.copy(
                    status = NavigationStatus.READY,
                    notice = approachNotice(distanceToStart),
                )
                return
            }
            val targets = current.destination?.let(campus::routeTargets).orEmpty()
            if (targets.isEmpty()) return
            mutableState.value = current.copy(manualStart = null)
            requestRoutes(
                from = fix.point,
                targets = targets,
                continueNavigation = false,
                successNotice = "已从当前位置重新规划，请确认路线",
            )
            return
        }
        startFollowing(route)
        updatePosition(fix)
    }

    fun stopNavigation() {
        val current = mutableState.value
        resetNavigation()
        mutableState.value = current.copy(
            status = if (current.routes.isEmpty()) NavigationStatus.IDLE else NavigationStatus.READY,
            guidance = null,
            notice = null,
        )
    }

    fun retry() {
        if (following != null) {
            val fix = mutableState.value.fix?.takeIf { it.canNavigate(SystemClock.elapsedRealtime()) }
            if (fix != null) recalculate(fix)
        } else {
            plan()
        }
    }

    fun updatePosition(fix: CampusFix?) {
        val current = mutableState.value
        val now = SystemClock.elapsedRealtime()
        val fresh = fix?.takeIf { now - it.elapsedRealtimeMillis in 0..POSITION_DISPLAY_AGE_MILLIS }
        mutableState.value = current.copy(fix = fresh)
        val usable = fresh?.takeIf { it.canNavigate(now) }
        if (startPending && usable != null) {
            startNavigation()
            return
        }
        val route = following ?: return
        if (current.status == NavigationStatus.REROUTING) return
        if (usable == null || current.campus?.contains(usable.point) != true) {
            clearOffRoute()
            arrivalFixes = 0
            mutableState.value = mutableState.value.copy(
                status = NavigationStatus.WAITING_LOCATION,
                notice = if (usable == null) "定位暂不可用，导航已暂停" else "已离开当前校区，导航已暂停",
                guidance = null,
            )
            return
        }
        if (usable.elapsedRealtimeMillis <= lastAcceptedFix) return
        val elapsed = if (lastAcceptedFix == 0L) 0.0 else (usable.elapsedRealtimeMillis - lastAcceptedFix) / 1000.0
        lastAcceptedFix = usable.elapsedRealtimeMillis

        val match = route.match(usable.point, progressMeters, usable.accuracy, elapsed)
        val offRouteThreshold = max(22.0, usable.accuracy * 1.5)
        if (match == null || match.offRoadMeters > offRouteThreshold) {
            noteOffRoute(usable)
            return
        }
        clearOffRoute()
        progressMeters = max(progressMeters, match.alongMeters)
        val guidance = route.guidance(progressMeters)
        val nearEnd = guidance.remainingMeters < 14 && usable.point.distanceTo(route.points.last()) < 18
        arrivalFixes = if (nearEnd) arrivalFixes + 1 else 0
        if (arrivalFixes >= 2) {
            resetNavigation()
            mutableState.value = mutableState.value.copy(
                status = NavigationStatus.ARRIVED,
                guidance = null,
                notice = route.arrivalNotice,
            )
        } else {
            mutableState.value = mutableState.value.copy(
                status = NavigationStatus.NAVIGATING,
                guidance = guidance,
                notice = null,
            )
        }
    }

    private fun noteOffRoute(fix: CampusFix) {
        arrivalFixes = 0
        if (offRouteCount == 0) firstOffRouteAt = fix.elapsedRealtimeMillis
        offRouteCount++
        mutableState.value = mutableState.value.copy(
            status = NavigationStatus.WAITING_LOCATION,
            notice = "正在确认你是否偏离路线",
            guidance = null,
        )
        val sinceLastReroute = lastRerouteAt?.let { fix.elapsedRealtimeMillis - it } ?: Long.MAX_VALUE
        if (offRouteCount >= 3 && fix.elapsedRealtimeMillis - firstOffRouteAt >= 4_000 && sinceLastReroute >= 15_000) {
            recalculate(fix)
        }
    }

    private fun recalculate(fix: CampusFix) {
        val current = mutableState.value
        if (current.status == NavigationStatus.REROUTING) return
        val campus = current.campus ?: return
        if (!campus.contains(fix.point)) return
        val targets = current.destination?.let(campus::routeTargets).orEmpty()
        if (targets.isEmpty()) return
        lastRerouteAt = fix.elapsedRealtimeMillis
        clearOffRoute()
        requestRoutes(fix.point, targets, continueNavigation = true, preferredTarget = following?.target)
    }

    private fun requestRoutes(
        from: GeoPoint,
        targets: List<CampusRouteTarget>,
        continueNavigation: Boolean,
        successNotice: String? = null,
        startFromPlace: Boolean = false,
        preferredTarget: CampusRouteTarget? = null,
    ) {
        val current = mutableState.value
        val campus = current.campus ?: return
        val generation = ++routingGeneration
        routingJob?.cancel()
        mutableState.value = current.copy(
            routes = if (continueNavigation) current.routes else emptyList(),
            selectedRouteId = if (continueNavigation) current.selectedRouteId else null,
            status = if (continueNavigation) NavigationStatus.REROUTING else NavigationStatus.PLANNING,
            guidance = null,
            notice = if (continueNavigation) "正在重新规划路线" else null,
        )
        routingJob = scope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    val router = CampusRouter(campus)
                    if (preferredTarget == null || targets.size == 1) {
                        router.plan(from, targets, current.mode, startFromPlace)
                    } else {
                        val preferred = router.plan(from, preferredTarget, current.mode, startFromPlace)
                        if (preferred.failure == RouteFailure.DISCONNECTED || preferred.failure == RouteFailure.NO_END_ROAD) {
                            router.plan(from, targets.filterNot { it == preferredTarget }, current.mode, startFromPlace)
                        } else {
                            preferred
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (routingGeneration != generation) return@launch
            val latest = mutableState.value
            if (result == null || result.routes.isEmpty()) {
                mutableState.value = latest.copy(
                    status = NavigationStatus.NO_ROUTE,
                    notice = result?.failure?.let(::failureNotice) ?: "路线计算失败，请重试",
                    guidance = null,
                )
                return@launch
            }
            val best = result.routes.first()
            plannedFrom = from
            val usesEntrance = best.target?.entrance != null
            val destinationName = latest.destination?.name ?: "目的地"
            val previewNote = when {
                usesEntrance && current.mode == TravelMode.BIKE ->
                    "骑行至附近道路，再步行前往$destinationName"
                usesEntrance -> "步行前往$destinationName"
                latest.destination?.confidence == "verified-area" -> "路线到标注区域附近"
                latest.destination?.confidence == "verified-building" -> "路线到所在楼栋附近"
                latest.destination?.confidence == "reference" -> "路线终点在参考图标注的楼栋附近，实际地点待核"
                startFromPlace -> "按起终点附近已标注道路规划，楼门请以现场为准"
                else -> "路线到达就近道路，楼门请以现场为准"
            }
            mutableState.value = latest.copy(
                status = NavigationStatus.READY,
                routes = result.routes,
                selectedRouteId = best.id,
                notice = if (successNotice == null) previewNote
                else if (!usesEntrance) successNotice else "$successNotice。$previewNote",
            )
            if (continueNavigation) {
                val fix = latest.fix?.takeIf { it.canNavigate(SystemClock.elapsedRealtime()) }
                val distance = fix?.point?.distanceTo(best.points.first())
                if (fix != null && distance != null && distance > startTolerance(fix)) {
                    following = null
                    mutableState.value = mutableState.value.copy(notice = approachNotice(distance))
                    return@launch
                }
                startFollowing(best)
                updatePosition(fix)
            }
        }
    }

    private fun startFollowing(route: CampusRoute) {
        following = route
        progressMeters = 0.0
        lastAcceptedFix = 0
        arrivalFixes = 0
        clearOffRoute()
        mutableState.value = mutableState.value.copy(
            status = NavigationStatus.NAVIGATING,
            guidance = route.guidance(0.0),
            notice = null,
        )
    }

    private fun resetNavigation() {
        routingGeneration++
        routingJob?.cancel()
        routingJob = null
        plannedFrom = null
        startPending = false
        following = null
        progressMeters = 0.0
        lastAcceptedFix = 0
        arrivalFixes = 0
        lastRerouteAt = null
        clearOffRoute()
    }

    private fun clearOffRoute() {
        offRouteCount = 0
        firstOffRouteAt = 0
    }

    private fun reject(message: String) {
        resetNavigation()
        mutableState.value = mutableState.value.withoutRoute().copy(status = NavigationStatus.NO_ROUTE, notice = message)
    }

    private fun startTolerance(fix: CampusFix): Double = max(22.0, fix.accuracy * 1.3)

    private fun approachNotice(distance: Double): String =
        "距道路起点约 ${distance.roundToInt()} 米，请先前往地图上的绿色起点"

    private fun failureNotice(failure: RouteFailure): String = when (failure) {
        RouteFailure.OUTSIDE_CAMPUS -> "起终点需位于所选校区的校内范围"
        RouteFailure.NO_START_ROAD -> "起点附近缺少可用道路，请换一个起点"
        RouteFailure.NO_END_ROAD -> "目的地附近缺少当前方式可用的道路"
        RouteFailure.TOO_CLOSE -> "起终点在同一处道路附近"
        RouteFailure.DISCONNECTED -> "当前方式未找到连通的校内路线，可切换步行或骑行"
    }

    private fun CampusMapState.withoutRoute() = copy(
        status = NavigationStatus.IDLE, routes = emptyList(), selectedRouteId = null, notice = null, guidance = null,
    )

    companion object {
        private const val POSITION_DISPLAY_AGE_MILLIS = 30_000L
    }
}
