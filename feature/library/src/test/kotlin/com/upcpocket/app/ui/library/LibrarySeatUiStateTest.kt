package com.upcpocket.app.ui.library

import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryFloor
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySeatUiStateTest {

    private val filters = LibraryFilters(
        dates = listOf("2026-11-04", "2026-11-05"),
        buildings = listOf(LibraryBuilding("1", "东馆")),
    )
    private val rooms = listOf(
        LibraryRoom("r1", "一层 A 区", 1, "1 楼", totalSeats = 40, freeSeats = 7),
        LibraryRoom("r2", "二层 B 区", 2, "2 楼", totalSeats = 20, freeSeats = 0),
    )

    @Test
    fun `floor filter keeps only the selected floor`() {
        val state = LibrarySeatViewModel.UiState(
            filters = filters,
            building = filters.buildings.first(),
            date = filters.dates.first(),
            rooms = rooms,
            floors = listOf(LibraryFloor(1, "1 楼"), LibraryFloor(2, "2 楼")),
            selectedFloor = 2,
        )

        assertEquals(listOf("r2"), state.visibleSeatRooms().map { it.roomId })
        assertEquals("余 7 / 共 40 座", rooms.first().remainingLabel)
    }

    @Test
    fun `closing seat detail keeps filters and selected floor`() {
        val seat = LibrarySeat(
            id = "s1",
            name = "A01",
            row = 1,
            col = 1,
            type = "seat",
            enabled = true,
            status = "FREE",
            localOnly = false,
        )
        val opened = LibrarySeatViewModel.UiState(
            filters = filters,
            building = filters.buildings.first(),
            date = "2026-11-05",
            rooms = rooms,
            floors = listOf(LibraryFloor(1, "1 楼")),
            selectedFloor = 1,
            room = rooms.first(),
            seat = seat,
            showTimeDialog = true,
            showConfirm = true,
        )

        val closed = opened.afterCloseRoom()

        assertNull(closed.room)
        assertNull(closed.seat)
        assertEquals("2026-11-05", closed.date)
        assertEquals("东馆", closed.building?.name)
        assertEquals(1, closed.selectedFloor)
        assertEquals(listOf("r1"), closed.visibleSeatRooms().map { it.roomId })
        assertEquals(false, closed.showTimeDialog)
        assertEquals(false, closed.showConfirm)
    }
}
