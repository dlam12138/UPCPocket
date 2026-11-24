package com.upcpocket.app.ui.todo

import com.upcpocket.app.todo.PersonalSchedule
import com.upcpocket.app.todo.data.PersonalScheduleTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalSchedulePresentationTest {

    private val shanghai = PersonalScheduleTime.SHANGHAI
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun `same day schedule uses device local times`() {
        val schedule = schedule("09:00", "2026-11-24T10:30")
        assertEquals(
            "09:00–10:30",
            PersonalSchedulePresentation.timeLabel(
                schedule,
                LocalDate.parse("2026-11-24"),
                shanghai,
            ),
        )
    }

    @Test
    fun `cross midnight clips to 24 hours on the start day and starts at zero next day`() {
        val schedule = schedule("2026-11-24T23:00", "2026-11-25T00:30")

        assertEquals(
            "23:00–24:00",
            PersonalSchedulePresentation.timeLabel(
                schedule,
                LocalDate.parse("2026-11-24"),
                shanghai,
            ),
        )
        assertEquals(
            "00:00–00:30",
            PersonalSchedulePresentation.timeLabel(
                schedule,
                LocalDate.parse("2026-11-25"),
                shanghai,
            ),
        )
    }

    @Test
    fun `tokyo next day segment shows device time while editing keeps shanghai time`() {
        val schedule = schedule("2026-11-24T23:30", "2026-11-24T23:50")

        assertEquals(
            "00:30–00:50",
            PersonalSchedulePresentation.timeLabel(
                schedule,
                LocalDate.parse("2026-11-25"),
                tokyo,
            ),
        )
        assertEquals(
            "23:30–23:50",
            PersonalSchedulePresentation.timeLabel(
                schedule,
                LocalDate.parse("2026-11-24"),
                shanghai,
            ),
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 24, 23, 30),
            PersonalScheduleTime.shanghaiDateTime(schedule.startAt),
        )
    }

    @Test
    fun `multi day schedule shows only the selected day fragment`() {
        val schedule = schedule("2026-11-24T23:00", "2026-11-26T01:00")

        assertEquals(
            "00:00–24:00",
            PersonalSchedulePresentation.timeLabel(
                schedule,
                LocalDate.parse("2026-11-25"),
                shanghai,
            ),
        )
        assertEquals(
            "00:00–01:00",
            PersonalSchedulePresentation.timeLabel(
                schedule,
                LocalDate.parse("2026-11-26"),
                shanghai,
            ),
        )
    }

    private fun schedule(start: String, end: String): PersonalSchedule =
        PersonalSchedule(
            id = "schedule",
            title = "日程",
            startAt = millis(if ('T' in start) start else "2026-11-24T$start"),
            endAt = millis(end),
            location = null,
            note = null,
            revision = 1,
        )

    private fun millis(text: String): Long =
        requireNotNull(PersonalScheduleTime.resolve(LocalDateTime.parse(text)))
}
