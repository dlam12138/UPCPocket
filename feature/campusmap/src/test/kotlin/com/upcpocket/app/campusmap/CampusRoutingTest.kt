package com.upcpocket.app.campusmap

import kotlin.math.ceil
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusRoutingTest {
    private val start = GeoPoint(120.0000, 35.0000)
    private val junction = GeoPoint(120.0005, 35.0000)
    private val end = GeoPoint(120.0010, 35.0000)
    private val alternative = GeoPoint(120.0005, 35.0003)

    @Test
    fun fastestRoutePrecedesAnActualAlternateAndUsesSamePolylineForGuidance() = runTest {
        val data = campus(
            points = listOf(start, junction, end, alternative),
            roads = both(0, 1) + both(1, 2) + both(0, 3) + both(3, 2),
        )

        val routes = CampusRouter(data).plan(start, end, TravelMode.WALK).routes
        assertEquals(2, routes.size)
        assertTrue(routes.first().meters < routes.last().meters)
        assertNotEquals(routes.first().id, routes.last().id)
        assertTrue(alternative in routes.last().points)
        val selected = routes.last()
        val match = checkNotNull(selected.match(alternative, 46.0, 6.0))
        assertEquals(0.0, match.offRoadMeters, 0.01)
        assertTrue(selected.guidance(match.alongMeters).remainingMeters in 1.0..<selected.meters)
    }

    @Test
    fun bikeRespectsOneWayAndWalkKeepsItsOwnAllowedDirection() = runTest {
        val data = campus(
            points = listOf(start, end),
            roads = listOf(CampusRoad(0, 1, walk = true, bike = true), CampusRoad(1, 0, walk = true, bike = false)),
        )
        val router = CampusRouter(data)
        val middle = start.interpolate(end, 0.2)
        val late = start.interpolate(end, 0.8)

        val forward = router.plan(middle, late, TravelMode.BIKE).routes.single()
        assertNull(forward.walkFromIndex)
        val reverse = router.plan(late, middle, TravelMode.BIKE).routes.single()
        assertEquals(listOf(late, end, middle), reverse.points)
        assertEquals(1, reverse.walkFromIndex)
        assertTrue(router.plan(late, middle, TravelMode.WALK).routes.isNotEmpty())
    }

    @Test
    fun cyclingHandsOffToWalkBeyondTheCyclingSnapLimit() = runTest {
        val transfer = start.copy(lon = 120.0008)
        val destination = start.copy(lon = 120.0018)
        val data = campus(
            points = listOf(start, transfer, destination),
            roads = both(0, 1) + both(1, 2).map { it.copy(bike = false) },
        )
        val router = CampusRouter(data)
        val route = router.plan(start, destination, TravelMode.BIKE).routes.single()
        val cycling = start.distanceTo(transfer)
        val walking = transfer.distanceTo(destination)
        val expectedSeconds = ceil(cycling / 3.6 + walking / 1.35).toInt()

        assertTrue(walking > 65)
        assertEquals(listOf(start, transfer, destination), route.points)
        assertEquals(1, route.walkFromIndex)
        assertEquals(cycling, route.cyclingMeters, 0.01)
        assertEquals(walking, route.walkingMeters, 0.01)
        assertEquals(expectedSeconds, route.seconds)
        assertEquals(expectedSeconds, route.guidance(0.0).remainingSeconds)
        assertEquals("请下车步行", route.guidance(cycling - 1.0).instruction)
        assertEquals("到达目的地附近", route.guidance(cycling + 1.0).instruction)
        assertEquals(TravelMode.BIKE, route.modeAt(cycling - 1.0))
        assertEquals(TravelMode.WALK, route.modeAt(cycling))
        assertEquals(ceil((walking - 10) / 1.35).toInt(), route.guidance(cycling + 10).remainingSeconds)
        assertEquals("已到达目的地附近", route.arrivalNotice)
        assertEquals(RouteFailure.NO_END_ROAD, CampusRouter(data.copy(networkRoads = both(0, 1) +
            CampusRoad(2, 1, walk = true, bike = false))).plan(start, destination, TravelMode.BIKE).failure)
    }

    @Test
    fun startingAtTheBikeStopKeepsTheRemainingWalkingRoute() = runTest {
        val data = campus(
            points = listOf(start, junction, end),
            roads = both(0, 1) + both(1, 2).map { it.copy(bike = false) },
        )

        val route = CampusRouter(data).plan(junction, end, TravelMode.BIKE).routes.single()

        assertEquals(TravelMode.WALK, route.mode)
        assertEquals(listOf(junction, end), route.points)
        assertEquals(0.0, route.cyclingMeters, 0.0)
    }

    @Test
    fun doesNotInventAWalkableConnectionWhereRoadsCrossWithoutAVertex() = runTest {
        val bikeEnd = start.copy(lon = 120.002)
        val pathStart = end.copy(lat = 34.9996)
        val pathEnd = end.copy(lat = 35.0008)
        val data = campus(
            points = listOf(start, bikeEnd, pathStart, pathEnd),
            roads = both(0, 1) + both(2, 3).map { it.copy(bike = false) },
        )

        assertEquals(RouteFailure.NO_END_ROAD, CampusRouter(data).plan(start, pathEnd, TravelMode.BIKE).failure)
    }

    @Test
    fun dropsOutsideCampusAndDisconnectedDestinations() = runTest {
        val data = campus(
            points = listOf(start, junction, end, end.copy(lon = 120.0014)),
            roads = both(0, 1) + both(2, 3),
        )
        val router = CampusRouter(data)

        assertEquals(RouteFailure.DISCONNECTED, router.plan(start, end, TravelMode.WALK).failure)
        assertEquals(RouteFailure.OUTSIDE_CAMPUS, router.plan(start, GeoPoint(120.005, 35.0), TravelMode.BIKE).failure)
    }

    @Test
    fun buildingDefaultsToOutdoorEntranceAndCyclingContinuesOnFoot() = runTest {
        val building = CampusPlace(
            "teaching", "南教楼", "teaching", listOf("南教"), end.copy(lat = 35.00030), "osm", null, null,
        )
        val entrance = CampusPlace(
            "teaching-west", "南教楼西门", "entrance", emptyList(),
            end.copy(lat = 35.00010), "surveyed", building.id, null, end,
        )
        val unconnected = entrance.copy(id = "teaching-east", name = "南教楼东门", accessPoint = null)
        val indoors = entrance.copy(id = "teaching-indoor", point = building.point)
        val footprint = CampusGeometry("building", listOf(
            GeoPoint(120.0009, 35.0002), GeoPoint(120.0011, 35.0002),
            GeoPoint(120.0011, 35.0004), GeoPoint(120.0009, 35.0004),
        ))
        val data = campus(
            points = listOf(start, junction, end),
            roads = both(0, 1) + both(1, 2),
        ).copy(
            geometry = listOf(footprint),
            places = listOf(building, unconnected, indoors, entrance),
        )

        assertEquals(entrance, data.destinationEntrance(building))
        assertNull(data.routeTarget(unconnected))
        assertNull(data.routeTarget(indoors))
        assertEquals(data to building, resolveTimetablePlace("南教110", listOf(data)))
        val target = checkNotNull(data.routeTarget(building))
        val router = CampusRouter(data)
        assertEquals(
            RouteFailure.NO_END_ROAD,
            router.plan(start, CampusRouteTarget(end, indoors), TravelMode.WALK).failure,
        )
        val walking = router.plan(start, target, TravelMode.WALK).routes.first()
        val cycling = router.plan(start, target, TravelMode.BIKE).routes.first()

        assertEquals(entrance.point, walking.points.last())
        assertEquals(entrance.point, cycling.points.last())
        assertEquals(end, cycling.points[checkNotNull(cycling.walkFromIndex)])
        assertEquals(start.distanceTo(end) + end.distanceTo(checkNotNull(entrance.point)), walking.meters, 0.01)
        assertEquals(target, walking.target)
        assertEquals("已到达目的地", walking.arrivalNotice)
        assertEquals("请下车步行", cycling.guidance(cycling.cyclingMeters).instruction)
        assertEquals("到达目的地", cycling.guidance(cycling.meters).instruction)
        assertEquals("已到达目的地", cycling.arrivalNotice)
    }

    @Test
    fun walkingChoosesTheShortestReachableEntranceIncludingItsFinalConnector() = runTest {
        val building = CampusPlace("teaching", "教学楼", "teaching", emptyList(), end, "osm", null, null)
        val nearerRoad = start.interpolate(end, 0.4)
        val shorterWalkRoad = start.interpolate(end, 0.6)
        val longConnector = CampusPlace(
            "gate-1", "教学楼一号门", "entrance", emptyList(),
            nearerRoad.copy(lat = 35.00025), "surveyed", building.id, null, nearerRoad,
        )
        val shortConnector = longConnector.copy(
            id = "gate-2",
            point = shorterWalkRoad.copy(lat = 35.00002),
            accessPoint = shorterWalkRoad,
        )
        val unconnected = longConnector.copy(id = "gate-3", accessPoint = null)
        val data = campus(points = listOf(start, end), roads = both(0, 1))
            .copy(places = listOf(building, longConnector, unconnected, shortConnector))

        val targets = data.routeTargets(building)
        assertEquals(listOf(longConnector, shortConnector), targets.map { it.entrance })
        val routes = CampusRouter(data).plan(start, targets, TravelMode.WALK).routes
        assertEquals(shortConnector, routes.first().target?.entrance)
        assertTrue(routes.all { it.points.last() == shortConnector.point })
    }

    @Test
    fun walkingSkipsDisconnectedEntranceAndKeepsAlternativesAtTheReachableOne() = runTest {
        val building = CampusPlace("teaching", "教学楼", "teaching", emptyList(), end, "osm", null, null)
        val disconnected = start.copy(lat = 35.00020)
        val disconnectedEnd = disconnected.copy(lat = 35.00035)
        val nearerEntrance = CampusPlace(
            "gate-1", "教学楼一号门", "entrance", emptyList(),
            disconnected, "surveyed", building.id, null, disconnected,
        )
        val reachableEntrance = nearerEntrance.copy(id = "gate-2", point = end, accessPoint = end)
        val data = campus(
            points = listOf(start, junction, end, alternative, disconnected, disconnectedEnd),
            roads = both(0, 1) + both(1, 2) + both(0, 3) + both(3, 2) + both(4, 5),
        ).copy(places = listOf(building, nearerEntrance, reachableEntrance))

        val routes = CampusRouter(data).plan(start, data.routeTargets(building), TravelMode.WALK).routes
        assertEquals(2, routes.size)
        assertTrue(routes.all { it.target?.entrance == reachableEntrance && it.points.last() == end })
    }

    @Test
    fun walkingRejectsAnEntranceWithAnAccessPointOffTheWalkNetwork() = runTest {
        val data = campus(points = listOf(start, end), roads = both(0, 1))
        val invalid = CampusPlace(
            "teaching-east", "南教楼东门", "entrance", emptyList(),
            end.copy(lat = 35.00020), "surveyed", "teaching", null, end.copy(lat = 35.00010),
        )

        assertEquals(
            RouteFailure.NO_END_ROAD,
            CampusRouter(data).plan(start, checkNotNull(data.routeTarget(invalid)), TravelMode.WALK).failure,
        )
    }

    @Test
    fun tangdaowanDestinationSnapSkipsTheSegmentInsideABuilding() = runTest {
        val inside = junction.copy(lat = 35.00018)
        val firstCorner = GeoPoint(120.0004, 35.0001)
        val building = CampusGeometry("building", listOf(
            firstCorner,
            firstCorner.copy(lon = 120.0006),
            GeoPoint(120.0006, 35.0003),
            firstCorner.copy(lat = 35.0003),
        ))
        val data = campus(points = listOf(start, junction, inside), roads = both(0, 1) + both(1, 2))
            .copy(geometry = listOf(building))

        val route = CampusRouter(data).plan(start, inside, TravelMode.BIKE).routes.first()

        assertEquals(junction, route.points.last())
        assertFalse(data.isInsideBuilding(route.points.last()))
    }

    @Test
    fun indoorLiveOriginStartsAtTheNearestEligibleOutdoorEntranceForEachMode() = runTest {
        val data = indoorOriginCampus()
        val router = CampusRouter(data)
        for (mode in TravelMode.entries) {
            val route = router.plan(indoorOrigin, end, mode).routes.single()
            assertTrue(route.startFromEntrance)
            assertEquals(junction, route.points.first())
            assertEquals(junction.distanceTo(end), route.meters, 0.01)
            assertFalse(indoorOrigin in route.points)
            assertFalse(router.plan(junction, end, mode).routes.single().startFromEntrance)
            assertEquals(
                RouteFailure.NO_START_ROAD,
                router.plan(indoorOrigin, end, mode, startFromPlace = true).failure,
            )
        }
    }

    @Test
    fun indoorOriginDoesNotResnapAnEntranceToANearbyBikeRoad() = runTest {
        val data = indoorOriginCampus().let { original ->
            original.copy(
                networkPoints = original.networkPoints + listOf(
                    start.copy(lat = 34.9997), end.copy(lat = 34.9997),
                ),
                networkRoads = original.networkRoads.map { it.copy(bike = false) } + both(3, 4),
            )
        }
        val router = CampusRouter(data)

        assertTrue(router.plan(indoorOrigin, end, TravelMode.WALK).routes.single().startFromEntrance)
        assertEquals(RouteFailure.NO_START_ROAD, router.plan(indoorOrigin, end, TravelMode.BIKE).failure)
    }

    @Test
    fun indoorOriginRequiresUniqueTangdaowanBuildingAndRegisteredSurveyedExit() = runTest {
        val data = indoorOriginCampus()
        val host = data.places.first { !it.isEntrance }
        val extraHost = host.copy(id = "another-building")
        val extraDoor = data.places.last().copy(id = "another-door", parentId = extraHost.id)
        val unavailable = listOf(
            data.copy(id = CampusId.GZK),
            data.copy(places = listOf(host)),
            data.copy(places = data.places.map { if (it.isEntrance) it.copy(confidence = "osm") else it }),
            data.copy(geometry = data.geometry + data.geometry),
            data.copy(places = data.places + listOf(extraHost, extraDoor)),
        )
        for (sample in unavailable) {
            assertEquals(
                RouteFailure.NO_START_ROAD,
                CampusRouter(sample).plan(indoorOrigin, end, TravelMode.WALK).failure,
            )
        }
    }

    @Test
    fun documentedCourtsAndSharedBuildingServicesUseTheirAvailableMapAnchors() {
        val court = CampusPlace("court", "篮球场", "sport", emptyList(), end, "verified-area", null, null)
        val building = CampusPlace("dorm", "学生公寓", "residence", emptyList(), junction, "osm", null, null)
        val service = CampusPlace(
            "laundry", "洗衣房", "service", emptyList(), junction, "verified-building", building.id, null,
        )
        val uncertain = court.copy(id = "uncertain", confidence = "estimated")
        val data = campus(points = emptyList(), roads = emptyList())
            .copy(places = listOf(court, building, service, uncertain))

        assertEquals(end, data.routeTarget(court)?.point)
        assertEquals(end, data.routeTarget(court.copy(confidence = "verified"))?.point)
        assertEquals(junction, data.routeTarget(service)?.point)
        assertNull(data.routeTarget(uncertain))
    }

    @Test
    fun locationRequiresRecentPreciseFixForGuidance() {
        val good = CampusFix(point = start, accuracy = 8.0, elapsedRealtimeMillis = 20_000L, precise = true)
        assertTrue(good.canNavigate(21_000L))
        assertFalse(good.canNavigate(36_000L))
        assertFalse(good.copy(precise = false).canNavigate(21_000L))
        assertFalse(good.copy(accuracy = 85.0).canNavigate(21_000L))
    }

    @Test
    fun guidanceShowsAnImmediateTurn() {
        val corner = start.copy(lon = 120.00007)
        val finish = corner.copy(lat = 35.0006)
        val route = sampleRoute(listOf(start, corner, finish))

        assertEquals("向左转", route.guidance(0.0).instruction)
        assertTrue(route.guidance(0.0).metersToInstruction < 10.0)
    }

    @Test
    fun gpsDriftDoesNotJumpToTheReturnLegOfAParallelRoute() {
        val farCorner = start.copy(lon = 120.00033)
        val returnCorner = farCorner.copy(lat = 35.000072)
        val finish = start.copy(lat = returnCorner.lat)
        val route = sampleRoute(listOf(start, farCorner, returnCorner, finish))
        val noisyFix = start.copy(lon = 120.000022, lat = 35.000045)

        for (bearing in listOf(null, 270f)) {
            val match = checkNotNull(route.match(noisyFix, 0.0, 5.0, elapsedSeconds = 0.0, bearing = bearing))
            assertTrue(match.alongMeters < 10.0)
            assertTrue(route.guidance(match.alongMeters).remainingMeters > 50.0)
        }
    }

    @Test
    fun movingBearingOnlyBreaksACloseJunctionMatchTie() {
        val route = sampleRoute(listOf(start, junction, alternative))
        val cornerMeters = start.distanceTo(junction)
        val fix = junction.copy(lon = junction.lon - 0.00001, lat = junction.lat + 0.000008)
        val withoutHeading = checkNotNull(route.match(fix, cornerMeters - 1.0, 5.0))
        val headingNorth = checkNotNull(route.match(fix, cornerMeters - 1.0, 5.0, bearing = 0f))
        val fartherFix = junction.copy(lon = junction.lon - 0.00005, lat = junction.lat + 0.000008)
        val nearerRoad = checkNotNull(route.match(fartherFix, cornerMeters - 5.0, 5.0, bearing = 0f))

        assertTrue(withoutHeading.alongMeters < cornerMeters)
        assertTrue(headingNorth.alongMeters > cornerMeters)
        assertTrue(headingNorth.offRoadMeters < 1.0)
        assertTrue(nearerRoad.alongMeters < cornerMeters)
    }

    @Test
    fun gentleTurnShowsItsDirectionWhenApproaching() {
        val corner = start.copy(lon = 120.002)
        for ((latitude, instruction) in listOf(35.00022 to "向左前方行进", 34.99978 to "向右前方行进")) {
            val finish = corner.copy(lon = 120.0025, lat = latitude)
            val route = sampleRoute(listOf(start, corner, finish))
            val firstLeg = start.distanceTo(corner)

            assertEquals("沿当前道路直行", route.guidance(0.0).instruction)
            assertEquals(instruction, route.guidance(firstLeg - 60).instruction)
            assertEquals(instruction, route.guidance(firstLeg - 20).instruction)
            assertEquals("到达目的地附近", route.guidance(route.meters).instruction)
        }
    }

    @Test
    fun strongTurnReplacesAGentleBendTwelveMetersEarlier() {
        val gentleCorner = junction
        val strongCorner = gentleCorner.copy(lon = 120.000614, lat = 35.000054)
        val finish = strongCorner.copy(lon = 120.000394, lat = 35.000366)
        val route = sampleRoute(listOf(start, gentleCorner, strongCorner, finish))
        val guidance = route.guidance(0.0)

        assertTrue(gentleCorner.distanceTo(strongCorner) in 10.0..15.0)
        assertEquals("向左转", guidance.instruction)
        assertEquals(
            start.distanceTo(gentleCorner) + gentleCorner.distanceTo(strongCorner),
            guidance.metersToInstruction,
            0.01,
        )
    }

    @Test
    fun acceptedProgressLocatesTheRemainingPolylineAfterATurn() {
        val route = sampleRoute(listOf(start, junction, alternative))
        val firstLeg = start.distanceTo(junction)
        val fix = junction.interpolate(alternative, 0.5)
        val match = checkNotNull(route.match(fix, firstLeg, 5.0, elapsedSeconds = 5.0))
        val position = checkNotNull(route.positionAt(match.alongMeters))

        assertEquals(1, position.segmentIndex)
        assertEquals(0.5, position.fraction, 0.001)
        assertEquals(RoutePosition(0, 0.0), route.positionAt(0.0))
        assertEquals(RoutePosition(1, 1.0), route.positionAt(route.meters))
    }

    private fun sampleRoute(points: List<GeoPoint>): CampusRoute {
        val length = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        return CampusRoute("test", TravelMode.WALK, points, length, 60)
    }

    private val indoorOrigin = GeoPoint(120.0004, 35.00075)

    private fun indoorOriginCampus(): CampusMapData {
        val building = CampusPlace("teaching", "教学楼", "teaching", emptyList(), indoorOrigin, "osm", null, null)
        val farDoor = CampusPlace(
            "teaching-west", "教学楼西门", "entrance", emptyList(),
            start.copy(lat = 35.00010), "surveyed", building.id, null, start,
        )
        val nearDoor = farDoor.copy(
            id = "teaching-south", name = "教学楼南门",
            point = junction.copy(lat = 35.00015), accessPoint = junction,
        )
        return campus(points = listOf(start, junction, end), roads = both(0, 1) + both(1, 2)).copy(
            geometry = listOf(CampusGeometry("building", listOf(
                GeoPoint(120.0001, 35.0002), GeoPoint(120.0008, 35.0002),
                GeoPoint(120.0008, 35.0009), GeoPoint(120.0001, 35.0009),
            ))),
            places = listOf(building, farDoor, nearDoor),
        )
    }

    private fun campus(points: List<GeoPoint>, roads: List<CampusRoad>) = CampusMapData(
        id = CampusId.TDW,
        name = "唐岛湾",
        bounds = MapBounds(west = 119.999, south = 34.999, east = 120.002, north = 35.001),
        geometry = emptyList(),
        places = emptyList(),
        areas = listOf(
            listOf(GeoPoint(119.999, 34.999), GeoPoint(120.002, 34.999),
                GeoPoint(120.002, 35.001), GeoPoint(119.999, 35.001)),
        ),
        networkPoints = points,
        networkRoads = roads,
    )

    private fun both(a: Int, b: Int) =
        listOf(CampusRoad(a, b, walk = true, bike = true), CampusRoad(b, a, walk = true, bike = true))
}
