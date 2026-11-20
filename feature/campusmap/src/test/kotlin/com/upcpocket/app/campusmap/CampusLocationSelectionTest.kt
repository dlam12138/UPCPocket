package com.upcpocket.app.campusmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CampusLocationSelectionTest {
    @Test
    fun keepsBetterFixBrieflyThenShowsNewerFallback() {
        val selection = CampusFixSelection()
        val accurate = fix(accuracy = 5.0, time = 1_000L)
        val fallback = fix(accuracy = 55.0, time = 2_000L)

        selection.offer(accurate)
        assertEquals(accurate, selection.current(1_000L))
        selection.offer(fallback)
        assertEquals(accurate, selection.current(2_000L))
        assertEquals(fallback, selection.current(5_001L))
        assertNull(selection.current(17_001L))
    }

    @Test
    fun newerGoodFixAdvancesWithoutAllowingAnOlderCallbackToMoveItBack() {
        val selection = CampusFixSelection()
        val initial = fix(accuracy = 5.0, time = 1_000L)
        val next = fix(accuracy = 7.0, time = 2_000L)
        val late = fix(accuracy = 3.0, time = 1_500L)

        selection.offer(initial)
        assertEquals(initial, selection.current(1_000L))
        selection.offer(next)
        assertEquals(next, selection.current(2_000L))
        selection.offer(late)
        assertEquals(next, selection.current(2_500L))
    }

    private fun fix(accuracy: Double, time: Long) =
        CampusFix(GeoPoint(120.0, 36.0), accuracy, time, precise = true)
}
