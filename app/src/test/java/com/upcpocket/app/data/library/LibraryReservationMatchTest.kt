package com.upcpocket.app.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预约成功核对的口径：日期 + 完整起止时间 + 座位身份。
 *
 * 只比日期/时间会把“同一时段本来就有另一个座位”的旧记录当成新预约成功；
 * 这里把那条错误路径钉成失败。
 */
class LibraryReservationMatchTest {
    private val reservation = LibraryReservation(
        id = "new-1",
        date = "2026-10-28",
        begin = "08:00",
        end = "22:00",
        location = "3F-A101",
        status = "RESERVE",
    )

    @Test
    fun `日期时段座位都一致才匹配`() {
        assertTrue(
            LibraryReservationMatch.matches(reservation, "2026-10-28", "08:00", "22:00", "A101"),
        )
        assertTrue(
            LibraryReservationMatch.matches(reservation, "2026-10-28", "8:00", "22:00:00", "a101"),
        )
    }

    @Test
    fun `同一时段换座位不算新预约`() {
        assertFalse(
            LibraryReservationMatch.matches(reservation, "2026-10-28", "08:00", "22:00", "A102"),
        )
    }

    @Test
    fun `结束时间或日期不同不算匹配`() {
        assertFalse(
            LibraryReservationMatch.matches(reservation, "2026-10-28", "08:00", "20:00", "A101"),
        )
        assertFalse(
            LibraryReservationMatch.matches(reservation, "2026-10-29", "08:00", "22:00", "A101"),
        )
    }

    @Test
    fun `时间解析失败按不匹配处理`() {
        assertFalse(LibraryReservationMatch.matches(reservation, "2026-10-28", "unknown", "22:00", "A101"))
        assertFalse(LibraryReservationMatch.matches(reservation, "2026-10-28", "08:00", "22:00", ""))
    }
}
