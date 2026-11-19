package com.upcpocket.app.widget

import com.upcpocket.app.timetable.TimetableWidgetCourse
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.UUID
import org.junit.Test

class TimetableWidgetTickerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 5, 12, 30, 0, 0, zone)

    @Test
    fun `same boundary redraw does not reset its delay`() {
        val plan = TimetableTickSchedule()
        val id = UUID.randomUUID()
        assertTrue(plan.shouldSchedule(1000L))
        plan.scheduled(id, 1000L)
        assertFalse(plan.shouldSchedule(1000L))
        assertTrue(plan.shouldSchedule(2000L))
    }

    @Test
    fun `account restore redraw defers continuation to running boundary`() {
        val plan = TimetableTickSchedule()
        val id = UUID.randomUUID()
        plan.scheduled(id, 1000L)
        assertTrue(plan.begin(id))
        assertFalse(plan.shouldSchedule(2000L))
        assertTrue(plan.finish(id))
        assertTrue(plan.shouldSchedule(2000L))
        plan.scheduled(UUID.randomUUID(), 2000L)
        assertFalse(plan.finish(id))
    }

    @Test
    fun `removed or replaced boundary cannot append a stale successor`() {
        val plan = TimetableTickSchedule()
        val old = UUID.randomUUID()
        plan.scheduled(old, 1000L)
        assertTrue(plan.begin(old))
        plan.clear()
        assertFalse(plan.finish(old))
        plan.scheduled(UUID.randomUUID(), 2000L)
        assertFalse(plan.begin(old))
        assertFalse(plan.finish(old))
    }

    @Test
    fun `persisted boundary can resume in a fresh process and continue once`() {
        val plan = TimetableTickSchedule()
        val id = UUID.randomUUID()
        assertTrue(plan.begin(id))
        assertFalse(plan.shouldSchedule(2000L))
        assertTrue(plan.finish(id))
        assertTrue(plan.shouldSchedule(2000L))
        assertFalse(plan.finish(id))
    }

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
    fun `active course redraws at the next whole local minute`() {
        val snapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = now.toLocalDate(),
            courses = listOf(course(12 * 60, 13 * 60)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 5, 12, 31, 0, 0, zone),
            TimetableWidgetTicker.nextBoundary(snapshot, now),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 5, 12, 31, 0, 0, zone),
            TimetableWidgetTicker.nextBoundary(snapshot, now.withSecond(45)),
        )
    }

    @Test
    fun `class end inside the next minute still wins`() {
        val lateNow = ZonedDateTime.of(2026, 11, 5, 12, 29, 30, 0, zone)
        val snapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = lateNow.toLocalDate(),
            courses = listOf(course(12 * 60, 12 * 60 + 30)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 5, 12, 30, 0, 0, zone),
            TimetableWidgetTicker.nextBoundary(snapshot, lateNow),
        )
    }

    @Test
    fun `countdown only yields to class end and midnight`() {
        val active = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = now.toLocalDate(),
            courses = listOf(course(12 * 60, 13 * 60)),
        )
        val midClass = TimetableWidgetTicker.planRedraw(active, now.withSecond(45))
        assertEquals(
            ZonedDateTime.of(2026, 11, 5, 12, 31, 0, 0, zone),
            midClass.at,
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 5, 13, 0, 0, 0, zone).toInstant().toEpochMilli(),
            midClass.deferTodayRefreshUntilEpochMillis,
        )

        val lastMinute = TimetableWidgetTicker.planRedraw(
            active,
            ZonedDateTime.of(2026, 11, 5, 12, 59, 30, 0, zone),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 5, 13, 0, 0, 0, zone),
            lastMinute.at,
        )
        assertEquals(0L, lastMinute.deferTodayRefreshUntilEpochMillis)

        val untilMidnight = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = now.toLocalDate(),
            courses = listOf(course(23 * 60, 24 * 60)),
        )
        val midnight = TimetableWidgetTicker.planRedraw(
            untilMidnight,
            ZonedDateTime.of(2026, 11, 5, 23, 59, 0, 0, zone),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, zone),
            midnight.at,
        )
        assertEquals(0L, midnight.deferTodayRefreshUntilEpochMillis)
    }

    @Test
    fun `after the class ends the next minute redraw stops`() {
        val afterEnd = ZonedDateTime.of(2026, 11, 5, 13, 0, 0, 0, zone)
        val snapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = afterEnd.toLocalDate(),
            courses = listOf(course(12 * 60, 13 * 60)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, zone),
            TimetableWidgetTicker.nextBoundary(snapshot, afterEnd),
        )
    }

    @Test
    fun `course ending at 24_00 maps to midnight without an invalid LocalTime`() {
        val lateNow = ZonedDateTime.of(2026, 11, 5, 23, 59, 0, 0, zone)
        val snapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = lateNow.toLocalDate(),
            courses = listOf(course(23 * 60, 24 * 60)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, zone),
            TimetableWidgetTicker.nextBoundary(snapshot, lateNow),
        )
    }

    @Test
    fun `out of range synthetic course times are ignored and never crash`() {
        val snapshot = TimetableWidgetSnapshot(
            status = TimetableWidgetStatus.OK,
            date = now.toLocalDate(),
            courses = listOf(
                course(23 * 60 + 59, 25 * 60),
                course(25 * 60, 26 * 60),
            ),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, zone),
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
