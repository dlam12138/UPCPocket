package com.upcpocket.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SeatMapViewportTest {
    @Test
    fun `overview zoom and reset keep a tap mapped to the same source seat`() {
        val bounds = SeatMapRect(left = 0f, top = 0f, right = 12f, bottom = 10f)
        val viewportSize = SeatMapSize(width = 300f, height = 240f)
        val seatByPosition = mapOf(
            SeatMapGridPosition(row = 4, col = 7) to "seat-7-4",
        )
        val seatCenter = SeatMapPoint(x = 7.5f, y = 4.5f)
        val overview = SeatMapViewportTransform.overview(
            contentBounds = bounds,
            viewportSize = viewportSize,
            paddingPx = 12f,
        )
        assertNotNull(overview)

        fun idAtSeatCenter(transform: SeatMapViewportTransform): String? {
            val displayPoint = transform.contentToDisplay(seatCenter)
            val sourceCell = transform.displayToGridPosition(displayPoint) ?: return null
            return seatByPosition[sourceCell]
        }

        assertEquals("seat-7-4", idAtSeatCenter(overview!!))

        val focus = SeatMapPoint(x = viewportSize.width / 2f, y = viewportSize.height / 2f)
        val sourceUnderFocus = overview.displayToContent(focus)
        val zoomed = overview.zoomAt(focus, factor = 2f)
        val sourceUnderZoomedFocus = zoomed.displayToContent(focus)

        assertEquals(sourceUnderFocus.x, sourceUnderZoomedFocus.x, 0.0001f)
        assertEquals(sourceUnderFocus.y, sourceUnderZoomedFocus.y, 0.0001f)
        assertEquals("seat-7-4", idAtSeatCenter(zoomed))
        val panned = zoomed.panBy(SeatMapPoint(x = -18f, y = 9f))
        assertEquals("seat-7-4", idAtSeatCenter(panned))

        val reset = panned.reset()
        assertNotNull(reset)
        assertEquals("seat-7-4", idAtSeatCenter(reset!!))
        assertNull(reset.displayToGridPosition(SeatMapPoint(x = -1f, y = -1f)))
    }

    @Test
    fun `rotated seat bounds include the visible corners`() {
        val seat = SeatMapRect(left = 4f, top = 7f, right = 8f, bottom = 9f)

        val bounds = requireNotNull(seat.rotatedBounds(90f))

        assertEquals(5f, bounds.left, 0.0001f)
        assertEquals(6f, bounds.top, 0.0001f)
        assertEquals(7f, bounds.right, 0.0001f)
        assertEquals(10f, bounds.bottom, 0.0001f)
    }
}
