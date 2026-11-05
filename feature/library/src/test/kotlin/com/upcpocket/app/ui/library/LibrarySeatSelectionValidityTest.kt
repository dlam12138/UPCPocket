package com.upcpocket.app.ui.library

import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibraryTimeOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySeatSelectionValidityTest {

    private val start = LibraryTimeOption("480", "08:00")
    private val end = LibraryTimeOption("600", "10:00")
    private val room = LibraryRoom("r1", "视听鉴赏区", 1, "1 楼", totalSeats = 10, freeSeats = 5)

    private fun seat(status: String, selectable: Boolean = true) = LibrarySeat(
        id = "s1",
        name = "A01",
        row = 1,
        col = 1,
        type = "seat",
        enabled = selectable,
        status = status,
        localOnly = false,
    )

    private fun state(
        seat: LibrarySeat,
        layoutSeat: LibrarySeat = seat,
        start: LibraryTimeOption? = this.start,
        end: LibraryTimeOption? = this.end,
    ) = LibrarySeatViewModel.UiState(
        filters = LibraryFilters(listOf("2026-11-04"), listOf(LibraryBuilding("1", "东馆"))),
        date = "2026-11-04",
        rooms = listOf(room),
        room = room,
        layout = LibrarySeatLayout(1, 1, listOf(layoutSeat)),
        seat = seat,
        startTimes = listOf(start ?: this.start),
        endTimes = if (start != null && end != null) listOf(end) else emptyList(),
        start = start,
        end = end,
    )

    @Test
    fun `available seat with server end can confirm`() {
        assertTrue(state(seat("FREE")).canConfirmReservation())
    }

    @Test
    fun `used or disabled seat cannot confirm even if stale state says selected`() {
        assertFalse(state(seat("IN_USE", selectable = false)).canConfirmReservation())
        assertFalse(
            state(
                seat = seat("FREE"),
                layoutSeat = seat("IN_USE", selectable = false),
            ).canConfirmReservation(),
        )
    }

    @Test
    fun `end must belong to the current server options and be longer than start`() {
        val wrongEnd = LibraryTimeOption("999", "23:00")
        assertFalse(
            state(seat("FREE"), end = wrongEnd).copy(endTimes = listOf(end)).canConfirmReservation(),
        )
        assertFalse(state(seat("FREE"), end = start).canConfirmReservation())
    }
}
