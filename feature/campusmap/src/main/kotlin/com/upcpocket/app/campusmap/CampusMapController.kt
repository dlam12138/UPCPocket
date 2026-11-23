package com.upcpocket.app.campusmap

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

internal enum class NavigationStatus {
    IDLE, PLANNING, READY, NAVIGATING, WAITING_LOCATION, REROUTING, ARRIVED, NO_ROUTE, NAVIGATION_NO_ROUTE,
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
    val routeProgressMeters: Double? = null,
    val unresolvedRequest: CampusMapPlaceRequest? = null,
)

internal class CampusMapController(
    private val scope: CoroutineScope,
    initialCampus: CampusId,
    private val loadMaps: suspend () -> Map<CampusId, CampusMapData>,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val routingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutableState = MutableStateFlow(CampusMapState(campusId = initialCampus))
    val state = mutableState.asStateFlow()

    private var maps: Map<CampusId, CampusMapData> = emptyMap()
    private var routingJob: Job? = null
    private var routingGeneration = 0L
    private var loadGeneration = 0L
    private var plannedFrom: GeoPoint? = null
    private var following: CampusRoute? = null
    private var startPending = false
    private var lastProcessedFix = 0L
    private var lastMatchedFix = 0L
    private var offRouteCount = 0
    private var firstOffRouteAt = 0L
    private var lastRerouteAt: Long? = null
    private var arrivalFixes = 0

    suspend fun load(requestedPlace: CampusMapPlaceRequest?) {
        val generation = ++loadGeneration
        mutableState.value = mutableState.value.copy(loadFailed = false)
        val data = try {
            loadMaps()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation == loadGeneration) mutableState.value = mutableState.value.copy(loadFailed = true)
            return
        }
        if (generation != loadGeneration) return
        maps = data
        val current = mutableState.value
        val timetableMatch = (requestedPlace as? CampusMapPlaceRequest.TimetableText)
            ?.let { resolveTimetablePlace(it.name, data.values) }
        val exact = requestedPlace as? CampusMapPlaceRequest.Exact
        val campus = timetableMatch?.first ?: data.getValue(exact?.campus ?: current.campusId)
        val destination = timetableMatch?.second ?: exact?.let { request ->
            campus.places.firstOrNull { place ->
                place.id == request.placeId && place.point != null && place.hasMappedPosition && !place.isEntrance
            }
        }
        mutableState.value = current.copy(
            campusId = campus.id,
            campus = campus,
            destination = destination,
            unresolvedRequest = requestedPlace.takeIf { destination == null },
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
            unresolvedRequest = null,
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
            if (fix?.canNavigate(now()) != true) {
                return reject("请开启精确定位并等待定位稳定，或手动选择校内起点")
            }
            fix.point
        } else {
            campus.routeTarget(manual)?.point ?: return reject("此起点暂无可用道路，请选择其他起点")
        }
        resetNavigation()
        requestRoutes(from, targets, forNavigation = false, startFromPlace = manual != null)
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
        val fix = current.fix?.takeIf { it.canNavigate(now()) }
        if (fix == null) {
            startPending = true
            mutableState.value = current.copy(status = NavigationStatus.WAITING_LOCATION, notice = "正在等待精确定位")
            return
        }
        val campus = current.campus ?: return
        startPending = false
        if (!campus.contains(fix.point)) {
            mutableState.value = current.copy(status = NavigationStatus.READY, notice = "进入当前校区后可开始导航")
            return
        }
        val usesCurrentPosition = current.manualStart == null &&
            plannedFrom?.distanceTo(fix.point)?.let { it <= 12.0 } == true
        if (!usesCurrentPosition) {
            recalculate(fix, preferredTarget = route.target)
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
            routeProgressMeters = null,
            notice = null,
        )
    }

    fun retry() {
        val fix = mutableState.value.fix?.takeIf { it.canNavigate(now()) } ?: return
        recalculate(fix)
    }

    fun updatePosition(fix: CampusFix?) {
        val current = mutableState.value
        val timestamp = now()
        val fresh = fix?.takeIf { timestamp - it.elapsedRealtimeMillis in 0..POSITION_DISPLAY_AGE_MILLIS }
        mutableState.value = current.copy(fix = fresh)
        val usable = fresh?.takeIf { it.canNavigate(timestamp) }
        if (startPending && usable != null) {
            startNavigation()
            return
        }
        if (current.status == NavigationStatus.REROUTING) return
        val route = following
        if (route == null) {
            if (current.status == NavigationStatus.NAVIGATION_NO_ROUTE && usable != null &&
                lastRerouteAt?.let { usable.elapsedRealtimeMillis - it >= 4_000L } == true &&
                plannedFrom?.distanceTo(usable.point)?.let { it >= max(15.0, usable.accuracy) } == true
            ) {
                recalculate(usable)
            }
            return
        }
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
        if (usable.elapsedRealtimeMillis <= lastProcessedFix) return
        val elapsed = if (lastMatchedFix == 0L) 0.0 else (usable.elapsedRealtimeMillis - lastMatchedFix) / 1000.0
        lastProcessedFix = usable.elapsedRealtimeMillis

        if (route.startFromEntrance && current.routeProgressMeters == null && current.campus.isInsideBuilding(usable.point)) {
            clearOffRoute()
            mutableState.value = mutableState.value.copy(
                status = NavigationStatus.NAVIGATING,
                notice = INDOOR_DEPARTURE_NOTICE,
                guidance = null,
            )
            return
        }

        val previousProgress = current.routeProgressMeters ?: 0.0
        val match = route.match(usable.point, previousProgress, usable.accuracy, elapsed, bearing = usable.bearing)
        if (match == null) {
            noteOffRoute(usable)
            return
        }
        lastMatchedFix = usable.elapsedRealtimeMillis
        clearOffRoute()
        val progressMeters = max(previousProgress, match.alongMeters)
        val guidance = route.guidance(progressMeters)
        val nearEnd = guidance.remainingMeters < 14 && usable.point.distanceTo(route.points.last()) < 18
        arrivalFixes = if (nearEnd) arrivalFixes + 1 else 0
        if (arrivalFixes >= 2) {
            resetNavigation()
            mutableState.value = mutableState.value.copy(
                status = NavigationStatus.ARRIVED,
                guidance = null,
                routeProgressMeters = route.meters,
                notice = route.arrivalNotice,
            )
        } else {
            mutableState.value = mutableState.value.copy(
                status = NavigationStatus.NAVIGATING,
                guidance = guidance,
                routeProgressMeters = progressMeters,
                notice = null,
            )
        }
    }

    private fun noteOffRoute(fix: CampusFix) {
        arrivalFixes = 0
        if (offRouteCount == 0) firstOffRouteAt = fix.elapsedRealtimeMillis
        offRouteCount++
        val current = mutableState.value
        if (current.status != NavigationStatus.NAVIGATION_NO_ROUTE) {
            val joining = current.routeProgressMeters == null
            mutableState.value = current.copy(
                status = if (joining) NavigationStatus.NAVIGATING else NavigationStatus.WAITING_LOCATION,
                notice = if (joining) "请沿可通行道路出发，路线将随定位更新" else "正在确认你是否偏离路线",
                guidance = null,
            )
        }
        val sinceLastReroute = lastRerouteAt?.let { fix.elapsedRealtimeMillis - it } ?: Long.MAX_VALUE
        if (offRouteCount >= 3 && fix.elapsedRealtimeMillis - firstOffRouteAt >= 4_000 && sinceLastReroute >= 15_000) {
            recalculate(fix)
        }
    }

    private fun recalculate(fix: CampusFix, preferredTarget: CampusRouteTarget? = following?.target) {
        val current = mutableState.value
        if (current.status == NavigationStatus.REROUTING) return
        val campus = current.campus ?: return
        if (!campus.contains(fix.point)) return
        val targets = current.destination?.let(campus::routeTargets).orEmpty()
        if (targets.isEmpty()) return
        lastRerouteAt = fix.elapsedRealtimeMillis
        clearOffRoute()
        mutableState.value = current.copy(manualStart = null)
        requestRoutes(fix.point, targets, forNavigation = true, preferredTarget = preferredTarget)
    }

    private fun requestRoutes(
        from: GeoPoint,
        targets: List<CampusRouteTarget>,
        forNavigation: Boolean,
        startFromPlace: Boolean = false,
        preferredTarget: CampusRouteTarget? = null,
    ) {
        val current = mutableState.value
        val campus = current.campus ?: return
        val generation = ++routingGeneration
        routingJob?.cancel()
        plannedFrom = from
        val retainRoute = forNavigation && following != null
        mutableState.value = current.copy(
            routes = if (retainRoute) current.routes else emptyList(),
            selectedRouteId = if (retainRoute) current.selectedRouteId else null,
            routeProgressMeters = current.routeProgressMeters.takeIf { retainRoute },
            status = if (forNavigation) NavigationStatus.REROUTING else NavigationStatus.PLANNING,
            guidance = null,
            notice = when {
                retainRoute -> "正在从当前位置重新规划路线"
                forNavigation -> "正在从当前位置规划路线"
                else -> null
            },
        )
        routingJob = scope.launch {
            val result = try {
                withContext(routingDispatcher) {
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
                    status = if (forNavigation) NavigationStatus.NAVIGATION_NO_ROUTE else NavigationStatus.NO_ROUTE,
                    notice = result?.failure?.let { failureNotice(it, current.mode, startFromPlace) }
                        ?: "路线计算失败，请重试",
                    guidance = null,
                )
                return@launch
            }
            val best = result.routes.first()
            if (forNavigation) {
                if (!retainRoute) lastRerouteAt = null
                startFollowing(best, result.routes)
                updatePosition(latest.fix)
                return@launch
            }
            val usesEntrance = best.target?.entrance != null
            val destinationName = latest.destination?.name ?: "目的地"
            val destinationNote = when {
                usesEntrance && current.mode == TravelMode.BIKE ->
                    "骑行至附近道路，再步行前往$destinationName"
                usesEntrance -> "步行前往$destinationName"
                latest.destination?.confidence == "verified-area" -> "路线到标注区域附近"
                latest.destination?.confidence == "verified-building" -> "路线到所在楼栋附近"
                latest.destination?.confidence == "reference" -> "路线终点在参考图标注的楼栋附近，实际地点待核"
                startFromPlace -> "按起终点附近已标注道路规划，楼门请以现场为准"
                else -> "路线到达就近道路，楼门请以现场为准"
            }
            val previewNote = if (best.startFromEntrance) {
                "路线从楼外道路出发，开始后将跟随当前位置。$destinationNote"
            } else {
                destinationNote
            }
            mutableState.value = latest.copy(
                status = NavigationStatus.READY,
                routes = result.routes,
                selectedRouteId = best.id,
                notice = previewNote,
            )
        }
    }

    private fun startFollowing(route: CampusRoute, routes: List<CampusRoute> = mutableState.value.routes) {
        following = route
        lastProcessedFix = 0L
        lastMatchedFix = 0L
        arrivalFixes = 0
        clearOffRoute()
        mutableState.value = mutableState.value.copy(
            status = NavigationStatus.NAVIGATING,
            routes = routes,
            selectedRouteId = route.id,
            guidance = null,
            routeProgressMeters = null,
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
        lastProcessedFix = 0L
        lastMatchedFix = 0L
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

    private fun failureNotice(failure: RouteFailure, mode: TravelMode, startFromPlace: Boolean): String = when (failure) {
        RouteFailure.OUTSIDE_CAMPUS -> "起终点需位于所选校区的校内范围"
        RouteFailure.NO_START_ROAD -> when {
            startFromPlace -> "所选起点附近暂无可用道路，请换一个起点"
            mode == TravelMode.BIKE -> "附近未找到可骑行道路，请到室外道路上重试或切换步行"
            else -> "附近未找到步行道路，请到室外道路上重试，或手动选择起点"
        }
        RouteFailure.NO_END_ROAD -> "目的地附近缺少当前方式可用的道路"
        RouteFailure.TOO_CLOSE -> "起终点在同一处道路附近"
        RouteFailure.DISCONNECTED -> "当前方式未找到连通的校内路线，可切换步行或骑行"
    }

    private fun CampusMapState.withoutRoute() = copy(
        status = NavigationStatus.IDLE, routes = emptyList(), selectedRouteId = null, notice = null, guidance = null,
        routeProgressMeters = null,
    )

    companion object {
        private const val POSITION_DISPLAY_AGE_MILLIS = 30_000L
        private const val INDOOR_DEPARTURE_NOTICE = "请从楼门走到室外，路线将随定位更新"
    }
}
