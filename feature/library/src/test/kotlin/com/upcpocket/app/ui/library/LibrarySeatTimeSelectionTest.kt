package com.upcpocket.app.ui.library

import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibraryTimeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySeatTimeSelectionTest {

    private val start = LibraryTimeOption("480", "08:00")
    private val ends = listOf(
        LibraryTimeOption("510", "08:30"),
        LibraryTimeOption("540", "09:00"),
        LibraryTimeOption("570", "09:30"),
        LibraryTimeOption("600", "10:00"),
        LibraryTimeOption("720", "12:00"),
    )

    @Test
    fun `server end options keep 30 60 90 120 minute choices and longer durations`() {
        assertEquals(
            listOf(30, 60, 90, 120, 240),
            ends.map { timeDurationMinutes(start, it) },
        )
        assertEquals("30 分钟", durationText(start, ends[0]))
        assertEquals("1 小时", durationText(start, ends[1]))
        assertEquals("1 小时 30 分钟", durationText(start, ends[2]))
        assertEquals("2 小时", durationText(start, ends[3]))
        assertEquals("4 小时", durationText(start, ends[4]))
    }

    @Test
    fun `selecting a start surfaces all server ends without auto picking one hour`() {
        val state = LibrarySeatViewModel.UiState(
            filters = LibraryFilters(
                dates = listOf("2026-11-04"),
                buildings = listOf(LibraryBuilding("1", "东馆")),
            ),
            date = "2026-11-04",
            seat = previewSeat(),
            startTimes = listOf(start),
        )

        val withStarts = state.withStartOptions(start, ends)

        assertEquals(start, withStarts.start)
        assertNull(withStarts.end)
        assertEquals(ends, withStarts.endTimes)
        assertEquals(listOf("08:30", "09:00", "09:30", "10:00", "12:00"), withStarts.endTimes.map { it.value })
        assertEquals("4 小时", durationText(withStarts.start, withStarts.endTimes.last()))
    }

    @Test
    fun `changing seat or date clears the selected end and end options`() {
        val opened = LibrarySeatViewModel.UiState(
            date = "2026-11-04",
            seat = previewSeat(),
            start = start,
            end = ends[1],
            startTimes = listOf(start),
            endTimes = ends,
        )

        val changedSeat = opened.copy(seat = previewSeat().copy(id = "s2", name = "A02")).clearTimeSelection()
        val changedDate = opened.copy(date = "2026-11-05", room = null, seat = null).clearTimeSelection()

        assertNull(changedSeat.end)
        assertEquals(emptyList<LibraryTimeOption>(), changedSeat.endTimes)
        assertNull(changedDate.end)
        assertNull(changedDate.seat)
    }

    @Test
    fun `school maxHour is displayed only when returned and is not used to cap the option list`() {
        val room = LibraryRoom("r1", "视听鉴赏区", 1, "1 楼", totalSeats = 68, freeSeats = 25, maxHour = 4)
        val withoutMax = room.copy(maxHour = null)

        assertEquals("学校单次最长 4 小时", room.maxDurationLabel)
        assertNull(withoutMax.maxDurationLabel)
        assertEquals(5, ends.size)
    }

    private fun previewSeat() = LibrarySeat(
        id = "s1",
        name = "A01",
        row = 1,
        col = 1,
        type = "seat",
        enabled = true,
        status = "FREE",
        localOnly = false,
    )
}
