package com.upcpocket.app.campusmap

import org.junit.Assert.assertEquals
import org.junit.Test

class CampusCompassTest {
    @Test
    fun smoothingTakesTheShortTurnAcrossNorth() {
        assertEquals(0f, smoothCampusHeading(350f, 10f, 0.5f), 0.001f)
        assertEquals(0f, smoothCampusHeading(10f, 350f, 0.5f), 0.001f)
        assertEquals(350f, smoothCampusHeading(null, -10f), 0.001f)
    }

    @Test
    fun trueHeadingAppliesMagneticDeclinationAndWrapsToACompassBearing() {
        assertEquals(5f, trueCampusHeading(359f, 6f), 0.001f)
        assertEquals(354f, trueCampusHeading(2f, -8f), 0.001f)
    }
}
