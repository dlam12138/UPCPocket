package com.upcpocket.app.widget

import com.upcpocket.app.timetable.TimetableWidgetCourse
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TimetableWidgetTickerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 5, 12, 30, 0, 0, zone)

    private fun course(start: Int, end: Int) = TimetableWidgetCourse(
        name = "合成课程",
        location = "合成地点",
        timeLabel = "时间",
        startMinute = start,
        endMinute = end,
        periodLabel = "第1–2节",
        colorSlot = 0,
    )

    @Test
    fun `current course end is the next local boundary`() {
        val snapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = now.toLocalDate(),
            courses = listOf(course(12 * 60, 13 * 60)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 5, 13, 0, 0, 0, zone),
            TimetableWidgetTicker.nextBoundary(snapshot, now),
        )
    }

    @Test
    fun `next course start wins over a later class end`() {
        val snapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = now.toLocalDate(),
            courses = listOf(course(14 * 60, 15 * 60)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 5, 14, 0, 0, 0, zone),
            TimetableWidgetTicker.nextBoundary(snapshot, now),
        )
    }

    @Test
    fun `stale date and missing snapshot fall back to local midnight`() {
        val stale = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = now.toLocalDate().minusDays(1),
            courses = listOf(course(12 * 60, 13 * 60)),
        )
        val midnight = ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, zone)
        assertEquals(midnight, TimetableWidgetTicker.nextBoundary(stale, now))
        assertEquals(midnight, TimetableWidgetTicker.nextBoundary(null, now))
    }

    @Test
    fun `all classes ended still schedules midnight redraw`() {
        val snapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = now.toLocalDate(),
            courses = listOf(course(9 * 60, 10 * 60)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, zone),
            TimetableWidgetTicker.nextBoundary(snapshot, now),
        )
    }
}
