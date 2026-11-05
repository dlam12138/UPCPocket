package com.upcpocket.app.ui.library

import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoom
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChaoxingSeatUiStateTest {

    @Test
    fun `closing seat map keeps campus and date selection`() {
        val room = ChaoxingSeatRoom(
            id = 12814,
            name = "求是图书馆-二楼-阶梯学习区",
            floor = "二楼",
            capacity = 30,
            usedCount = 8,
            startMinute = 8 * 60,
            endMinute = 22 * 60,
        )
        val opened = ChaoxingSeatUiState(
            kind = ChaoxingSeatKind.SEATENGINE,
            selectedDate = "2026-11-05",
            rooms = listOf(room),
            detail = ChaoxingSeatRoomDetail(
                roomId = 12814,
                name = room.name,
                capacity = 30,
                startMinute = room.startMinute,
                endMinute = room.endMinute,
                timeUnit = 30,
                places = emptyList(),
            ),
            selectedStart = 9 * 60,
            usedSeats = setOf("001", "002"),
        )

        val closed = opened.afterCloseRoom()

        assertNull(closed.detail)
        assertNull(closed.selectedStart)
        assertEquals(emptySet<String>(), closed.usedSeats)
        assertFalse(closed.usedSeatsLoaded)
        org.junit.Assert.assertNull(closed.selectedEnd)
        assertEquals(ChaoxingSeatKind.SEATENGINE, closed.kind)
        assertEquals("2026-11-05", closed.selectedDate)
    }
}
