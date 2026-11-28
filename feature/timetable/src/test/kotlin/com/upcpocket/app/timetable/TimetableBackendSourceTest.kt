package com.upcpocket.app.timetable

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimetableBackendSourceTest {

    private val term = TimetableTerm(
        year = "2026-2027",
        term = "1",
        startDay = LocalDate.parse("2026-09-07"),
        totalWeeks = 18,
        label = "2026-2027 第一学期",
    )

    @Test
    fun backendIndexMapsCurrentSelectionAndDates() {
        val start = term.startDay.plusWeeks(7)
        val index = TimetableBackendIndex(
            terms = listOf(
                TimetableBackendTerm(
                    year = term.year,
                    term = term.term,
                    label = term.label,
                    startDay = term.startDay,
                    totalWeeks = term.totalWeeks,
                ),
            ),
            currentYear = term.year,
            currentTerm = term.term,
            currentWeek = 8,
            weekDates = (0..6).map { start.plusDays(it.toLong()) },
        ).toDomainIndex()

        assertEquals(term, index.terms.single())
        assertEquals(8, index.currentWeek)
        assertEquals(start, index.currentWeekdays?.first())
    }

    @Test
    fun incompleteBackendWeekIsRejectedBeforeItCanBeCached() {
        val start = term.startDay.plusWeeks(7)
        val failure = try {
            TimetableBackendWeek(
                year = term.year,
                term = term.term,
                week = 8,
                weekDates = (0..6).map { start.plusDays(it.toLong()) },
                meetings = emptyList(),
                complete = false,
                fetchedAtEpochMillis = 1_700_000_000_000L,
            ).toDomainWeek(term, requestedWeek = 8)
            error("incomplete response should be rejected")
        } catch (failure: TimetableBackendFailure) {
            failure
        }

        assertEquals(
            TimetableBackendFailureCode.UPSTREAM_PROTOCOL_ERROR,
            failure.code,
        )
    }

    @Test
    fun completeBackendWeekMapsCourseFields() {
        val dates = (0..6).map { term.startDay.plusWeeks(7).plusDays(it.toLong()) }
        val week = TimetableBackendWeek(
            year = term.year,
            term = term.term,
            week = 8,
            weekDates = dates,
            meetings = listOf(
                TimetableBackendMeeting(
                    courseName = "示例课程",
                    date = dates[1],
                    dayOfWeek = 2,
                    startPeriod = 6,
                    endPeriod = 7,
                    rawPeriod = "6,7",
                    teacher = "教师甲",
                    location = "教学楼 A-101",
                    weeksLabel = "8",
                    courseCode = "C101",
                    classGroup = "一班",
                ),
            ),
            complete = true,
            fetchedAtEpochMillis = 1_700_000_000_000L,
        ).toDomainWeek(term, requestedWeek = 8)

        val meeting = week.meetings.single()
        assertEquals(listOf(6, 7), meeting.sourcePeriods)
        assertEquals("教师甲", meeting.teacher)
        assertEquals("教学楼 A-101", meeting.location)
        assertEquals("C101", meeting.courseId)
        assertEquals("一班", meeting.classGroup)
        assertNull(meeting.weekType)
    }
}
