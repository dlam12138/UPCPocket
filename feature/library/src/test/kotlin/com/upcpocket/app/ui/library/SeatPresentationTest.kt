package com.upcpocket.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatPresentationTest {
    private data class SeatLabel(val id: String, val name: String)
    private data class ReturnedGridElement(
        val row: Int,
        val col: Int,
        val isSeat: Boolean,
        val hidden: Boolean = false,
        val enabled: Boolean = true,
    )

    @Test
    fun `seat names sort numeric values first and retain original labels`() {
        val seats = listOf(
            SeatLabel("ten", "10"),
            SeatLabel("two", "2"),
            SeatLabel("two-padded", "02"),
            SeatLabel("one", "1"),
            SeatLabel("one-padded", "01"),
            SeatLabel("one-padded-more", "001"),
            SeatLabel("hall-z", "北区"),
            SeatLabel("hall-a", "北区"),
            SeatLabel("letter-lower", "a"),
            SeatLabel("letter-upper", "A"),
        )

        val sorted = sortSeatPresentations(seats, SeatLabel::name, SeatLabel::id)

        assertEquals(
            listOf(
                "one", "one-padded", "one-padded-more",
                "two", "two-padded", "ten",
                "letter-upper", "letter-lower", "hall-a", "hall-z",
            ),
            sorted.map(SeatLabel::id),
        )
        assertEquals(listOf("1", "01", "001"), sorted.take(3).map(SeatLabel::name))
    }

    @Test
    fun `grid bounds trim outer blanks and preserve internal channels and every supplied element`() {
        // Include a non-seat layout element and hidden/disabled seats at the
        // outer edges. Bounds are derived from the whole returned layout.
        val allLayoutElements = listOf(
            ReturnedGridElement(row = 2, col = 3, isSeat = false),
            ReturnedGridElement(row = 2, col = 4, isSeat = true),
            ReturnedGridElement(row = 7, col = 9, isSeat = true, hidden = true),
            ReturnedGridElement(row = 1, col = 12, isSeat = true, enabled = false),
        )

        val bounds = seatMapBoundsForGrid(
            rowCount = 10,
            columnCount = 14,
            allLayoutElements = allLayoutElements,
            rowOf = ReturnedGridElement::row,
            columnOf = ReturnedGridElement::col,
        )

        assertNotNull(bounds)
        assertEquals(3f, bounds!!.left, 0f)
        assertEquals(1f, bounds.top, 0f)
        assertEquals(13f, bounds.right, 0f)
        assertEquals(8f, bounds.bottom, 0f)
        assertTrue(bounds.contains(SeatMapPoint(x = 6.5f, y = 4.5f)))
    }

    @Test
    fun `rectangle bounds include all returned map elements`() {
        val bounds = seatMapBoundsFromRects(
            listOf(
                SeatMapRect(left = 10f, top = 20f, right = 14f, bottom = 25f),
                SeatMapRect(left = 40f, top = 35f, right = 48f, bottom = 43f),
            ),
        )

        assertEquals(10f, bounds!!.left, 0f)
        assertEquals(20f, bounds.top, 0f)
        assertEquals(48f, bounds.right, 0f)
        assertEquals(43f, bounds.bottom, 0f)
    }
}
