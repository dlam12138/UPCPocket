package com.upcpocket.app.campusmap

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class TravelMode(val title: String, val metersPerSecond: Double) {
    WALK("步行", 1.35),
    BIKE("骑行", 3.6),
}

internal data class CampusRoute(
    val id: String,
    val mode: TravelMode,
    val points: List<GeoPoint>,
    val meters: Double,
    val seconds: Int,
    val target: CampusRouteTarget? = null,
    val startFromEntrance: Boolean = false,
) {
    private val bikeHandoff = "步行前往目的地".takeIf { mode == TravelMode.BIKE && target?.entrance != null }
    private val arrivalCue = when {
        bikeHandoff != null -> "到达骑行终点，请$bikeHandoff"
        target?.entrance == null -> "到达目的地附近"
        else -> "到达目的地"
    }
    val arrivalNotice: String get() = "已$arrivalCue"

    private val distances = DoubleArray(points.size).apply {
        for (index in 1 until size) this[index] = this[index - 1] + points[index - 1].distanceTo(points[index])
    }
    private val bearings = DoubleArray((points.size - 1).coerceAtLeast(0)) { index ->
        points[index].bearingTo(points[index + 1])
    }
    private val cues = buildList<RouteCue> {
        for (index in 1 until points.lastIndex) {
            // Read each turn over a short distance, since a junction can have several very short edges.
            var before = index - 1
            while (before > 0 && distances[index] - distances[before] < 8) before--
            var after = index + 1
            while (after < points.lastIndex && distances[after] - distances[index] < 8) after++
            val inBearing = points[before].bearingTo(points[index])
            val outBearing = points[index].bearingTo(points[after])
            val angle = (outBearing - inBearing + 540) % 360 - 180
            if (abs(angle) < 25) continue
            val gentle = abs(angle) < 35
            val previous = lastOrNull()
            if (previous != null && distances[index] - previous.at < 16) {
                if (!previous.gentle || gentle) continue
                // A small bend must not hide a nearby stronger turn. Reuse its maneuver index.
                removeAt(lastIndex)
            }
            val instruction = when {
                abs(angle) >= 155 -> "掉头"
                abs(angle) < 35 && angle < 0 -> "向左前方行进"
                abs(angle) < 35 -> "向右前方行进"
                angle < 0 -> "向左转"
                else -> "向右转"
            }
            add(RouteCue(distances[index], instruction, gentle = gentle))
        }
        add(RouteCue(meters, bikeHandoff ?: arrivalCue))
    }

    fun match(
        point: GeoPoint,
        previousMeters: Double,
        accuracy: Double,
        elapsedSeconds: Double = 1.0,
        bearing: Float? = null,
    ): RouteMatch? {
        val behind = max(18.0, accuracy)
        val plausibleTravel = elapsedSeconds.coerceIn(0.0, 12.0) * mode.metersPerSecond * 1.6
        val ahead = plausibleTravel + max(18.0, accuracy * 1.25)
        val offRouteThreshold = max(22.0, accuracy * 1.5)
        val movingBearing = bearing?.takeIf { it.isFinite() }?.let { ((it.toDouble() % 360) + 360) % 360 }
        var closest: RouteMatch? = null
        var lowestScore = Double.POSITIVE_INFINITY
        for (index in 0 until points.lastIndex) {
            if (distances[index + 1] < previousMeters - behind || distances[index] > previousMeters + ahead) continue
            val projection = point.projectOnto(points[index], points[index + 1])
            if (projection.distance > offRouteThreshold) continue
            val along = distances[index] + projection.fraction * (distances[index + 1] - distances[index])
            if (along !in (previousMeters - behind)..(previousMeters + ahead)) continue
            val progressPenalty = (along - previousMeters - plausibleTravel - 8.0).coerceAtLeast(0.0) * 0.25
            // Heading only breaks close spatial ties: it cannot add more than two metres of cost.
            val directionPenalty = movingBearing?.let { heading ->
                val difference = abs((bearings[index] - heading + 540) % 360 - 180)
                difference.coerceAtMost(90.0) / 90.0 * 2.0
            } ?: 0.0
            val score = projection.distance + progressPenalty + directionPenalty
            if (score < lowestScore) {
                lowestScore = score
                closest = RouteMatch(along, projection.distance)
            }
        }
        return closest
    }

    /** Locate accepted progress on the existing polyline without matching the raw fix again. */
    fun positionAt(alongMeters: Double): RoutePosition? {
        if (points.size < 2 || !alongMeters.isFinite()) return null
        val along = alongMeters.coerceIn(0.0, distances.last())
        val found = distances.binarySearch(along)
        val index = (if (found >= 0) found else -found - 2).coerceIn(0, points.lastIndex - 1)
        val length = distances[index + 1] - distances[index]
        return RoutePosition(index, if (length > 0.0) (along - distances[index]) / length else 1.0)
    }

    fun guidance(along: Double): CampusGuidance {
        val remaining = (meters - along).coerceAtLeast(0.0)
        val cue = cues.firstOrNull { it.at >= along - 3.0 } ?: cues.last()
        val gap = (cue.at - along).coerceAtLeast(0.0)
        return CampusGuidance(
            instruction = if (gap > 120) "沿当前道路直行" else cue.instruction,
            metersToInstruction = if (gap > 120) 0.0 else gap,
            remainingMeters = remaining,
            remainingSeconds = ceil(remaining / mode.metersPerSecond).toInt(),
        )
    }

    private data class RouteCue(
        val at: Double,
        val instruction: String,
        val gentle: Boolean = false,
    )
}

internal data class RouteMatch(val alongMeters: Double, val offRoadMeters: Double)
internal data class RoutePosition(val segmentIndex: Int, val fraction: Double)

internal data class CampusGuidance(
    val instruction: String,
    val metersToInstruction: Double,
    val remainingMeters: Double,
    val remainingSeconds: Int,
)

internal enum class RouteFailure {
    OUTSIDE_CAMPUS, NO_START_ROAD, NO_END_ROAD, TOO_CLOSE, DISCONNECTED
}

internal data class RoutePlan(
    val routes: List<CampusRoute> = emptyList(),
    val failure: RouteFailure? = null,
)

internal class CampusRouter(private val campus: CampusMapData) {
    suspend fun plan(from: GeoPoint, to: GeoPoint, mode: TravelMode, startFromPlace: Boolean = false): RoutePlan =
        plan(from, CampusRouteTarget(to), mode, startFromPlace)

    suspend fun plan(from: GeoPoint, target: CampusRouteTarget, mode: TravelMode, startFromPlace: Boolean = false): RoutePlan =
        plan(from, listOf(target), mode, startFromPlace)

    suspend fun plan(
        from: GeoPoint,
        targets: List<CampusRouteTarget>,
        mode: TravelMode,
        startFromPlace: Boolean = false,
    ): RoutePlan {
        if (!campus.contains(from)) return RoutePlan(failure = RouteFailure.OUTSIDE_CAMPUS)
        if (targets.isEmpty()) return RoutePlan(failure = RouteFailure.NO_END_ROAD)
        val inCampus = targets.filter { target ->
            campus.contains(target.point) && target.entrance?.point?.let(campus::contains) != false
        }
        if (inCampus.isEmpty()) return RoutePlan(failure = RouteFailure.OUTSIDE_CAMPUS)
        val network = campus.networkPoints
        val roads = campus.networkRoads.filter { if (mode == TravelMode.WALK) it.walk else it.bike }
        val segments = roads.groupBy { streetKey(it.from, it.to) }
        val startLimit = if (startFromPlace) END_SEARCH_METERS else START_SEARCH_METERS
        val normalStart = snap(from, startLimit, network, segments)
        val entranceStart = if (normalStart == null && !startFromPlace) {
            campus.originEntrances(from).mapNotNull { entrance ->
                val access = entrance.accessPoint ?: return@mapNotNull null
                val entry = snap(access, ENTRANCE_SNAP_METERS, network, segments) {
                    !campus.isInsideBuilding(it)
                } ?: return@mapNotNull null
                entrance to entry
            }.minByOrNull { (entrance, _) -> from.distanceTo(checkNotNull(entrance.point)) }?.second
        } else null
        // The preview begins on an existing outdoor segment, never an inferred indoor connector.
        val start = normalStart ?: entranceStart
            ?: return RoutePlan(failure = RouteFailure.NO_START_ROAD)
        val destinations = inCampus.mapNotNull { target ->
            currentCoroutineContext().ensureActive()
            val door = target.entrance?.point
            if (door != null && campus.id == CampusId.TDW && campus.isInsideBuilding(door)) return@mapNotNull null
            // The bundled access point lies on a walking segment.
            val endLimit = if (door != null && mode == TravelMode.WALK) ENTRANCE_SNAP_METERS else END_SEARCH_METERS
            val end = snap(target.point, endLimit, network, segments) { point ->
                campus.id != CampusId.TDW || !campus.isInsideBuilding(point)
            } ?: return@mapNotNull null
            val approachMeters = when {
                door == null -> 0.0
                mode == TravelMode.WALK -> end.point.distanceTo(door)
                // Compare estimated time at cycling speed without adding an inferred walk to the route.
                else -> (end.distance + target.point.distanceTo(door)) *
                    (mode.metersPerSecond / TravelMode.WALK.metersPerSecond)
            }
            Destination(target, end, approachMeters)
        }
        if (destinations.isEmpty()) return RoutePlan(failure = RouteFailure.NO_END_ROAD)

        val positions = network + start.point + destinations.map { it.snap.point }
        val startIndex = network.size
        val firstEnd = startIndex + 1
        val selectionIndex = positions.size
        val connections = List(positions.size + 1) { mutableListOf<Arc>() }
        fun addArc(a: Int, b: Int, key: Long?) {
            connections[a].add(Arc(b, positions[a].distanceTo(positions[b]), key))
        }
        for (road in roads) addArc(road.from, road.to, streetKey(road.from, road.to))
        for (road in start.roads) {
            if (positions[road.to].distanceTo(start.point) >= 0.1) addArc(startIndex, road.to, start.key)
        }
        // Points at existing junctions can take any permitted adjoining edge. Mid-edge snaps
        // keep the direction of the original road, including one-way cycling.
        fun endpoint(snap: Snap): Int? {
            val road = snap.roads.first()
            return listOf(road.from, road.to).firstOrNull { positions[it].distanceTo(snap.point) < 0.1 }
        }
        endpoint(start)?.let { addArc(startIndex, it, null) }

        for ((index, destination) in destinations.withIndex()) {
            val end = destination.snap
            val endIndex = firstEnd + index
            for (road in end.roads) {
                if (positions[road.from].distanceTo(end.point) >= 0.1) addArc(road.from, endIndex, end.key)
            }
            endpoint(end)?.let { addArc(it, endIndex, null) }
            if (start.key == end.key && start.roads.any { road ->
                    val a = start.point.projectOnto(network[road.from], network[road.to]).fraction
                    val b = end.point.projectOnto(network[road.from], network[road.to]).fraction
                    b >= a
                }) {
                addArc(startIndex, endIndex, start.key)
            }
            // A shared terminal lets one shortest-path search compare all reachable stop points.
            connections[endIndex].add(Arc(selectionIndex, destination.approachMeters, null))
        }

        val selected = shortest(connections, startIndex, selectionIndex)
            ?: return RoutePlan(failure = RouteFailure.DISCONNECTED)
        val endIndex = selected.nodes[selected.nodes.lastIndex - 1]
        val destination = destinations[endIndex - firstEnd]
        val entrancePoint = destination.target.entrance?.point?.takeIf { mode == TravelMode.WALK }
        if (start.point.distanceTo(destination.snap.point) < 5 &&
            start.point.distanceTo(entrancePoint ?: destination.snap.point) < 5
        ) {
            return RoutePlan(failure = RouteFailure.TOO_CLOSE)
        }
        val fastest = selected.copy(
            nodes = selected.nodes.dropLast(1),
            meters = selected.meters - destination.approachMeters,
        )
        val candidates = mutableListOf(fastest)
        val signatures = mutableSetOf(fastest.nodes)
        // Removing selected road segments exposes genuine alternate graph paths. Keep only
        // meaningfully different paths, within a useful detour budget.
        val alternatives = sampling(fastest.streets.toList(), MAX_ALTERNATE_SEARCHES)
        val found = mutableListOf<NetworkPath>()
        for (street in alternatives) {
            currentCoroutineContext().ensureActive()
            val choice = shortest(connections, startIndex, endIndex, street) ?: continue
            if (choice.meters <= fastest.meters * MAX_DETOUR && signatures.add(choice.nodes)) found.add(choice)
        }
        for (choice in found.sortedBy { it.meters }) {
            if (candidates.none { overlap(it.streets, choice.streets) > MAX_OVERLAP }) {
                candidates.add(choice)
            }
            if (candidates.size == MAX_ROUTES) break
        }

        return RoutePlan(
            routes = candidates.mapIndexed { index, path ->
                val points = path.nodes.map { positions[it] }.fold(mutableListOf<GeoPoint>()) { result, point ->
                    if (result.lastOrNull()?.distanceTo(point)?.let { it >= 0.05 } != false) result.add(point)
                    result
                }
                if (entrancePoint != null) {
                    if (points.last().distanceTo(entrancePoint) < 0.05) points[points.lastIndex] = entrancePoint
                    else points.add(entrancePoint)
                }
                val meters = points.zipWithNext(GeoPoint::distanceTo).sum()
                CampusRoute(
                    id = "route-" + (index + 1),
                    mode = mode,
                    points = points,
                    meters = meters,
                    seconds = ceil(meters / mode.metersPerSecond).toInt(),
                    target = destination.target,
                    startFromEntrance = normalStart == null,
                )
            },
        )
    }

    private fun snap(
        point: GeoPoint,
        limit: Double,
        positions: List<GeoPoint>,
        segments: Map<Long, List<CampusRoad>>,
        accepts: (GeoPoint) -> Boolean = { true },
    ): Snap? {
        var closest: Snap? = null
        for ((key, roads) in segments) {
            val road = roads.first()
            val projection = point.projectOnto(positions[road.from], positions[road.to])
            if (projection.distance > limit || projection.distance >= (closest?.distance ?: Double.POSITIVE_INFINITY) ||
                !accepts(projection.point)
            ) continue
            closest = Snap(key, projection.point, projection.distance, roads)
        }
        return closest
    }

    private suspend fun shortest(
        graph: List<List<Arc>>,
        start: Int,
        end: Int,
        banned: Long? = null,
    ): NetworkPath? {
        val costs = DoubleArray(graph.size) { Double.POSITIVE_INFINITY }
        val previous = IntArray(graph.size) { -1 }
        val previousArc = arrayOfNulls<Arc>(graph.size)
        val queue = PriorityQueue(compareBy<QueueNode> { it.cost }.thenBy { it.vertex })
        costs[start] = 0.0
        queue.add(QueueNode(start, 0.0))
        val context = currentCoroutineContext()
        while (queue.isNotEmpty()) {
            context.ensureActive()
            val (vertex, cost) = queue.remove()
            if (cost > costs[vertex]) continue
            if (vertex == end) break
            for (arc in graph[vertex]) {
                if (banned != null && arc.street == banned) continue
                val nextCost = cost + arc.meters
                if (nextCost >= costs[arc.to]) continue
                costs[arc.to] = nextCost
                previous[arc.to] = vertex
                previousArc[arc.to] = arc
                queue.add(QueueNode(arc.to, nextCost))
            }
        }
        if (!costs[end].isFinite()) return null
        val nodes = mutableListOf<Int>()
        val streets = mutableSetOf<Long>()
        var vertex = end
        while (vertex != -1) {
            nodes.add(vertex)
            previousArc[vertex]?.street?.let(streets::add)
            vertex = previous[vertex]
        }
        return NetworkPath(nodes.asReversed(), costs[end], streets)
    }

    private fun sampling(edges: List<Long>, count: Int): List<Long> {
        if (edges.size <= count) return edges
        return List(count) { edges[(it.toDouble() * edges.lastIndex / (count - 1)).roundToInt()] }
    }

    private fun overlap(a: Set<Long>, b: Set<Long>): Double {
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1.0 else 0.0
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
    }

    private fun streetKey(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

    private data class Snap(val key: Long, val point: GeoPoint, val distance: Double, val roads: List<CampusRoad>)
    private data class Destination(val target: CampusRouteTarget, val snap: Snap, val approachMeters: Double)
    private data class Arc(val to: Int, val meters: Double, val street: Long?)
    private data class QueueNode(val vertex: Int, val cost: Double)
    private data class NetworkPath(val nodes: List<Int>, val meters: Double, val streets: Set<Long>)

    companion object {
        private const val MAX_ROUTES = 3
        private const val MAX_ALTERNATE_SEARCHES = 28
        private const val MAX_DETOUR = 1.65
        private const val MAX_OVERLAP = 0.9
        private const val START_SEARCH_METERS = 50.0
        private const val END_SEARCH_METERS = 65.0
        private const val ENTRANCE_SNAP_METERS = 1.0
    }
}
