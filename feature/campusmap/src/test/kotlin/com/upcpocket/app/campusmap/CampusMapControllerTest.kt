package com.upcpocket.app.campusmap

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampusMapControllerTest {
    private val manualOrigin = GeoPoint(120.0000, 35.0000)
    private val liveOrigin = GeoPoint(120.0006, 35.0000)
    private val junction = GeoPoint(120.0013, 35.0000)
    private val destination = GeoPoint(120.0020, 35.0000)
    private val detour = GeoPoint(120.0013, 35.00036)

    @Test
    fun exactPlaceRequestSelectsTheRequestedCampusAndDoesNotFallBackToNamesOrOtherCampuses() = runTest {
        val tdw = CampusMapData(
            id = CampusId.TDW,
            name = "唐岛湾",
            bounds = MapBounds(119.999, 34.999, 120.003, 35.001),
            geometry = emptyList(),
            places = listOf(place("shared", "唐岛湾楼", manualOrigin), place("tdw-only", "唐岛湾地点", liveOrigin)),
            areas = emptyList(),
            networkPoints = emptyList(),
            networkRoads = emptyList(),
        )
        val exactPlace = place("shared", "古镇口楼", destination)
        val gzk = tdw.copy(
            id = CampusId.GZK,
            name = "古镇口",
            places = listOf(
                exactPlace,
                place("unmapped", "待核验", junction).copy(confidence = "unverified"),
                place("entrance", "入口", detour).copy(kind = "entrance"),
            ),
        )
        val controller = CampusMapController(this, CampusId.TDW, { mapOf(tdw.id to tdw, gzk.id to gzk) })
        val requests = CampusMapPlaceRequests()

        requests.offer(CampusId.GZK, exactPlace.id)
        controller.load(requests.take())
        assertEquals(CampusId.GZK, controller.state.value.campusId)
        assertEquals(exactPlace, controller.state.value.destination)
        assertNull(requests.take())

        for (invalidId in listOf(exactPlace.name, "tdw-only", "unmapped", "entrance")) {
            controller.selectCampus(CampusId.TDW)
            requests.offer(CampusId.GZK, invalidId)
            val request = requests.take()
            controller.load(request)
            assertEquals(CampusId.GZK, controller.state.value.campusId)
            assertNull(controller.state.value.destination)
            assertEquals(request, controller.state.value.unresolvedRequest)
        }
    }

    @Test
    fun oneStartUsesCurrentPositionAndThenFollowsAndReroutes() = runTest {
        val controller = manualPreview()
        controller.updatePosition(fix(liveOrigin))

        controller.startNavigation()
        runCurrent()

        assertEquals(NavigationStatus.NAVIGATING, controller.state.value.status)
        assertStartsAt(liveOrigin, controller.state.value)
        val initiallyRemaining = checkNotNull(controller.state.value.guidance).remainingMeters

        advanceTimeBy(10_000)
        val walkedTo = GeoPoint(120.00075, 35.0000)
        controller.updatePosition(fix(walkedTo))

        val walking = controller.state.value
        assertEquals(liveOrigin.distanceTo(walkedTo), checkNotNull(walking.routeProgressMeters), 0.1)
        assertTrue(checkNotNull(walking.guidance).remainingMeters < initiallyRemaining)

        advanceTimeBy(45_000)
        controller.updatePosition(fix(detour))
        advanceTimeBy(2_000)
        controller.updatePosition(fix(detour))
        advanceTimeBy(2_000)
        controller.updatePosition(fix(detour))
        runCurrent()

        assertEquals(NavigationStatus.NAVIGATING, controller.state.value.status)
        assertStartsAt(detour, controller.state.value)
    }

    @Test
    fun aSingleOffRouteSampleDoesNotShrinkTheWindowForTheNextGoodMatch() = runTest {
        val controller = manualPreview()
        controller.updatePosition(fix(liveOrigin))
        controller.startNavigation()
        runCurrent()

        advanceTimeBy(9_000)
        controller.updatePosition(fix(detour))
        assertEquals(NavigationStatus.WAITING_LOCATION, controller.state.value.status)

        advanceTimeBy(1_000)
        val returnedToRoute = liveOrigin.copy(lon = 120.00089)
        controller.updatePosition(fix(returnedToRoute))

        val resumed = controller.state.value
        assertEquals(NavigationStatus.NAVIGATING, resumed.status)
        assertEquals(liveOrigin.distanceTo(returnedToRoute), checkNotNull(resumed.routeProgressMeters), 0.1)
    }

    @Test
    fun oneStartWaitsForAUsableFixAndAutomaticallyFollowsIt() = runTest {
        val controller = manualPreview()

        controller.startNavigation()
        assertEquals(NavigationStatus.WAITING_LOCATION, controller.state.value.status)
        controller.updatePosition(fix(liveOrigin, accuracy = 65.0))
        runCurrent()
        assertEquals(NavigationStatus.WAITING_LOCATION, controller.state.value.status)

        advanceTimeBy(1_000)
        controller.updatePosition(fix(liveOrigin))
        runCurrent()

        assertEquals(NavigationStatus.NAVIGATING, controller.state.value.status)
        assertStartsAt(liveOrigin, controller.state.value)
    }

    private suspend fun TestScope.manualPreview(): CampusMapController {
        val startPlace = place("start", "起点", manualOrigin)
        val endPlace = place("end", "终点", destination)
        val campus = CampusMapData(
            id = CampusId.TDW,
            name = "唐岛湾",
            bounds = MapBounds(119.999, 34.999, 120.003, 35.001),
            geometry = emptyList(),
            places = listOf(startPlace, endPlace),
            areas = listOf(listOf(
                GeoPoint(119.999, 34.999),
                GeoPoint(120.003, 34.999),
                GeoPoint(120.003, 35.001),
                GeoPoint(119.999, 35.001),
            )),
            networkPoints = listOf(manualOrigin, junction, destination, detour),
            networkRoads = listOf(0 to 1, 1 to 2, 1 to 3).flatMap { (a, b) ->
                listOf(
                    CampusRoad(a, b, walk = true, bike = true),
                    CampusRoad(b, a, walk = true, bike = true),
                )
            },
        )
        return CampusMapController(
            scope = this,
            initialCampus = campus.id,
            loadMaps = { mapOf(campus.id to campus) },
            now = { testScheduler.currentTime + 1_000 },
            routingDispatcher = StandardTestDispatcher(testScheduler),
        ).also { controller ->
            controller.load(requestedPlace = null)
            controller.choosePlace(endPlace)
            controller.choosePlace(startPlace, asStart = true)
            controller.plan()
            runCurrent()

            assertEquals(NavigationStatus.READY, controller.state.value.status)
            assertStartsAt(manualOrigin, controller.state.value)
        }
    }

    private fun TestScope.fix(point: GeoPoint, accuracy: Double = 5.0) =
        CampusFix(point, accuracy, testScheduler.currentTime + 1_000, precise = true)

    private fun place(id: String, name: String, point: GeoPoint) =
        CampusPlace(id, name, "teaching", emptyList(), point, "osm", parentId = null, region = null)

    private fun assertStartsAt(expected: GeoPoint, state: CampusMapState) {
        val route = state.routes.single { it.id == state.selectedRouteId }
        assertEquals(0.0, expected.distanceTo(route.points.first()), 0.1)
    }
}
