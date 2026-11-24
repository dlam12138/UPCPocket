package com.upcpocket.app.todo.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalScheduleTimeTest {

    @Test
    fun `shanghai gap and overlap local times are rejected`() {
        // 1986-05-04 02:30 上海夏令时跳过；01:30 在 09-14 回拨时出现两次。
        assertNull(PersonalScheduleTime.resolve(LocalDateTime.of(1986, 5, 4, 2, 30)))
        assertNull(PersonalScheduleTime.resolve(LocalDateTime.of(1986, 9, 14, 1, 30)))

        val afterGap = requireNotNull(
            PersonalScheduleTime.resolve(LocalDateTime.of(1986, 5, 4, 3, 30)),
        )
        assertEquals(
            LocalDateTime.of(1986, 5, 4, 3, 30),
            PersonalScheduleTime.shanghaiDateTime(afterGap),
        )
        assertNotNull(PersonalScheduleTime.resolve(LocalDateTime.of(1986, 9, 14, 2, 30)))
    }

    @Test
    fun `device day window uses local midnight boundaries`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val window = PersonalScheduleTime.dayWindow(LocalDate.of(2026, 11, 24), tokyo)

        assertEquals(
            requireNotNull(PersonalScheduleTime.resolve(LocalDateTime.of(2026, 11, 23, 23, 0))),
            window.fromInclusive,
        )
        assertEquals(
            requireNotNull(PersonalScheduleTime.resolve(LocalDateTime.of(2026, 11, 24, 23, 0))),
            window.untilExclusive,
        )
    }
}
