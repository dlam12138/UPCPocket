package com.upcpocket.app.designsystem

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRenderSupportTest {

    @Test
    fun `query time is absolute local time and includes year across years`() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
                .toInstant()
                .toEpochMilli()

        assertEquals(
            "10-29 17:20",
            WidgetRenderSupport.queryTime(
                fetchedAt = at(2026, 10, 29, 17, 20),
                now = at(2026, 10, 29, 20, 0),
                zone = zone,
            ),
        )
        assertEquals(
            "2025-12-31 23:59",
            WidgetRenderSupport.queryTime(
                fetchedAt = at(2025, 12, 31, 23, 59),
                now = at(2026, 1, 1, 0, 5),
                zone = zone,
            ),
        )
    }
}
