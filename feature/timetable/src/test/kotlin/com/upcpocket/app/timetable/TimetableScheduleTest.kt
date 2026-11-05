package com.upcpocket.app.timetable

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableScheduleTest {

    private val base = TimetableMeeting(
        courseName = "示例课程",
        weekday = 1,
        startPeriod = 1,
        endPeriod = 1,
        periods = listOf(1),
        sourcePeriods = listOf(1),
        weeks = listOf(1),
        weekLabel = "1",
        teacher = null,
        location = null,
        sectionsSource = "lessArr",
        date = LocalDate.parse("2026-09-07"),
    )

    @Test
    fun `official twelve periods have exact start and end`() {
        assertEquals("08:00–08:45", UpcTimetableSchedule.period(1)?.label)
        assertEquals("09:55–10:40", UpcTimetableSchedule.period(3)?.label)
        assertEquals("19:00–19:45", UpcTimetableSchedule.period(10)?.label)
        assertEquals("20:40–21:25", UpcTimetableSchedule.period(12)?.label)
        assertNull(UpcTimetableSchedule.period(13))
        assertNull(UpcTimetableSchedule.period(14))
    }

    @Test
    fun `response course time wins over period lookup`() {
        val meeting = base.copy(courseTime = "08:05~09:30")
        assertEquals("08:05–09:30", UpcTimetableSchedule.range(meeting)?.label)
    }

    @Test
    fun `missing course time falls back to official period range`() {
        val meeting = base.copy(
            startPeriod = 1,
            endPeriod = 2,
            periods = listOf(1, 2),
            sourcePeriods = listOf(1, 2),
        )
        assertEquals("08:00–09:35", UpcTimetableSchedule.range(meeting)?.label)
    }

    @Test
    fun `periods thirteen and fourteen stay unconfirmed instead of extrapolating`() {
        val meeting = base.copy(
            startPeriod = 13,
            endPeriod = 14,
            periods = listOf(13, 14),
            sourcePeriods = listOf(13, 14),
        )
        assertNull(UpcTimetableSchedule.range(meeting))
        assertNull(UpcTimetableSchedule.label(meeting))
    }

    @Test
    fun `sunday first source week keeps real continuous dates`() {
        val dates = listOf(
            "2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28",
            "2026-10-29", "2026-10-30", "2026-10-31",
        ).map(LocalDate::parse)
        val view = buildTimetableWeekView(dates, listOf(base.copy(date = dates[1])))
        assertEquals(dates, view.dates)
        assertEquals(dates.toSet(), view.knownDates)
        assertTrue(view.unknownDates.isEmpty())
        assertEquals(listOf(dates[1]), view.meetings.map { it.date })
    }

    @Test
    fun `monday first source never rotates columns or relabels a course`() {
        val mondayWeek = listOf(
            "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10",
            "2026-09-11", "2026-09-12", "2026-09-13",
        ).map(LocalDate::parse)
        val sundayMeeting = base.copy(date = mondayWeek.last(), weekday = 7)
        val view = buildTimetableWeekView(mondayWeek, listOf(sundayMeeting))
        // 视窗从 9/6 周日开始；源周未覆盖 9/6，必须标为未知，不能把 9/13 的课挪到首列。
        assertEquals(LocalDate.parse("2026-09-06"), view.dates.first())
        assertEquals(LocalDate.parse("2026-09-12"), view.dates.last())
        assertEquals(setOf(LocalDate.parse("2026-09-06")), view.unknownDates)
        assertTrue(view.meetings.isEmpty())
    }

    @Test
    fun `semester boundary and cross month dates remain continuous`() {
        val boundary = (0..6).map { LocalDate.parse("2026-09-06").plusDays(it.toLong()) }
        assertEquals(boundary, buildTimetableWeekView(boundary, emptyList()).dates)
        val crossMonth = (0..6).map { LocalDate.parse("2026-10-25").plusDays(it.toLong()) }
        val view = buildTimetableWeekView(crossMonth, emptyList())
        assertEquals(LocalDate.parse("2026-10-25"), view.dates.first())
        assertEquals(LocalDate.parse("2026-10-31"), view.dates.last())
    }
}
