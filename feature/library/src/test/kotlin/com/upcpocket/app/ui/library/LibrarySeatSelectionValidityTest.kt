package com.upcpocket.app.ui.library

import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibraryTimeOption
import java.time.LocalDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySeatSelectionValidityTest {

    private val start = LibraryTimeOption("480", "08:00")
    private val end = LibraryTimeOption("600", "10:00")
    private val shanghaiNoon = LocalDateTime.of(2026, 11, 7, 12, 0)
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
        filters = LibraryFilters(listOf("2026-11-07"), listOf(LibraryBuilding("1", "东馆"))),
        date = "2026-11-07",
        rooms = listOf(room),
        room = room,
        layout = LibrarySeatLayout(1, 1, listOf(layoutSeat)),
        seat = seat,
        startTimes = listOf(start ?: this.start),
        endTimes = if (start != null && end != null) listOf(end) else emptyList(),
        start = start,
        end = end,
        hiddenLoaded = true,
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

    @Test
    fun `now start needs Shanghai today and an end still ahead without a fake duration`() {
        val now = LibraryTimeOption("-1", "现在")
        val closing = LibraryTimeOption("1320", "22:00")
        val valid = state(seat("FREE")).copy(
            startTimes = listOf(LibraryTimeOption("480", "08:00"), now),
            endTimes = listOf(closing),
            start = now,
            end = closing,
        )

        assertTrue(valid.canConfirmReservation(shanghaiNoon))
        assertFalse(valid.canConfirmReservation())
        assertFalse(valid.copy(date = "2026-11-08").canConfirmReservation(shanghaiNoon))
        val reached = LibraryTimeOption("720", "12:00")
        assertFalse(
            valid.copy(end = reached, endTimes = listOf(reached)).canConfirmReservation(shanghaiNoon),
        )
        val passed = LibraryTimeOption("719", "11:59")
        assertFalse(
            valid.copy(end = passed, endTimes = listOf(passed)).canConfirmReservation(shanghaiNoon),
        )
        val badClock = LibraryTimeOption("1320", "晚上十点")
        assertFalse(
            valid.copy(end = badClock, endTimes = listOf(badClock))
                .canConfirmReservation(shanghaiNoon),
        )
        val sentinelEnd = LibraryTimeOption("-1", "22:00")
        assertFalse(
            valid.copy(end = sentinelEnd, endTimes = listOf(sentinelEnd))
                .canConfirmReservation(shanghaiNoon),
        )
        assertFalse(valid.copy(startTimes = emptyList()).canConfirmReservation(shanghaiNoon))
        val labelOnly = LibraryTimeOption("480", "现在")
        assertFalse(
            valid.copy(start = labelOnly, startTimes = listOf(labelOnly))
                .canConfirmReservation(shanghaiNoon),
        )
        assertNull(timeDurationMinutes(now, closing))
        assertNull(durationText(now, closing))
        assertFalse(nowStartExpired(now, closing, "2026-11-07", shanghaiNoon))
        assertTrue(nowStartExpired(now, reached, "2026-11-07", shanghaiNoon))
        assertTrue(nowStartExpired(now, passed, "2026-11-07", shanghaiNoon))
        assertTrue(nowStartExpired(now, closing, "2026-11-08", shanghaiNoon))
        assertFalse(nowStartExpired(start, closing, "2026-11-07", shanghaiNoon))
    }

    @Test
    fun `normal start requires an end later than start from server options`() {
        assertTrue(state(seat("FREE")).canConfirmReservation())
        assertTrue(
            state(seat("FREE")).canConfirmReservation(LocalDateTime.of(2026, 11, 8, 23, 0)),
        )
        assertFalse(state(seat("FREE"), end = start).canConfirmReservation())
        assertFalse(
            state(seat("FREE"), end = LibraryTimeOption("479", "07:59")).canConfirmReservation(),
        )
        assertFalse(state(seat("FREE")).copy(endTimes = emptyList()).canConfirmReservation())
        assertFalse(
            state(seat("FREE"))
                .copy(start = LibraryTimeOption("480", "07:30"))
                .canConfirmReservation(),
        )
        assertFalse(
            state(seat("FREE"))
                .copy(end = LibraryTimeOption("600", "11:00"))
                .canConfirmReservation(),
        )
        val sentinelEnd = LibraryTimeOption("-1", "10:00")
        assertFalse(
            state(seat("FREE"), end = sentinelEnd)
                .copy(endTimes = listOf(sentinelEnd))
                .canConfirmReservation(),
        )
        assertNull(timeDurationMinutes(start, sentinelEnd))
    }

    @Test
    fun `hidden preferences must be restored and selected seat must not be hidden`() {
        val valid = state(seat("FREE"))

        assertFalse(valid.copy(hiddenLoaded = false).canConfirmReservation())
        assertFalse(valid.copy(hiddenBusy = true).canConfirmReservation())
        assertFalse(valid.copy(hiddenError = "读取失败").canConfirmReservation())
        assertFalse(valid.copy(hiddenSeatIds = setOf("s1")).canConfirmReservation())
    }
}
