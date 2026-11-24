package com.upcpocket.app.timetable

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 个人日程的上海日窗口与片段投影。
 *
 * 重点覆盖半开窗口、跨日裁剪、重叠排序和时区提示；不依赖设备时区或当前时间。
 */
class TimetablePersonalAgendaProjectionTest {

    private val date: LocalDate = LocalDate.parse("2026-11-24")
    private val nextDate: LocalDate = date.plusDays(1)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(TIMETABLE_ZONE).toInstant().toEpochMilli()

    private fun entry(
        id: String,
        title: String,
        startAt: Long,
        endAt: Long,
    ): TimetableAgendaEntry = TimetableAgendaEntry(
        id = id,
        title = title,
        startAt = startAt,
        endAt = endAt,
    )

    @Test
    fun endingAtShanghaiMidnightBelongsToPreviousDayOnly() {
        val schedule = entry("a", "跨零点", at(date, 23), at(nextDate, 0))

        assertTrue(timetableAgendaCoversDate(schedule, date))
        assertFalse(timetableAgendaCoversDate(schedule, nextDate))

        val segments = projectTimetableAgendaDay(listOf(schedule), date)
        assertEquals(1, segments.size)
        assertEquals(23 * 60, segments.single().startMinute)
        assertEquals(24 * 60, segments.single().endMinute)
        // 恰好零点结束不延续到次日：不标“跨日”，也不进入次日列表。
        assertFalse(segments.single().continuesAfter)
        assertEquals("23:00–24:00", timetableAgendaSegmentTimeLabel(segments.single()))
        assertTrue(projectTimetableAgendaDay(listOf(schedule), nextDate).isEmpty())
    }

    @Test
    fun startingAtShanghaiMidnightBelongsToNewDay() {
        val schedule = entry("b", "凌晨开始", at(nextDate, 0), at(nextDate, 1))

        assertFalse(timetableAgendaCoversDate(schedule, date))
        assertTrue(timetableAgendaCoversDate(schedule, nextDate))
        val segment = projectTimetableAgendaDay(listOf(schedule), nextDate).single()
        assertEquals(0, segment.startMinute)
        assertEquals(60, segment.endMinute)
        // 恰好零点开始属于新一天，不标“接前日”。
        assertFalse(segment.continuesBefore)
        assertEquals("00:00–01:00", timetableAgendaSegmentTimeLabel(segment))
    }

    @Test
    fun overlappingEntriesAreBothKeptAndSortedByTime() {
        val overlappingEarlierEnd = entry("b", "重叠早结束", at(date, 9), at(date, 10))
        val overlappingLaterEnd = entry("c", "重叠晚结束", at(date, 9), at(date, 11))
        val crossDay = entry("a", "跨日事项", at(date, 22), at(nextDate, 2))

        val segments = projectTimetableAgendaDay(
            listOf(crossDay, overlappingEarlierEnd, overlappingLaterEnd),
            date,
        )

        assertEquals(listOf("b", "c", "a"), segments.map { it.entry.id })
        assertEquals(9 * 60, segments[0].startMinute)
        assertEquals(10 * 60, segments[0].endMinute)
        assertEquals(9 * 60, segments[1].startMinute)
        assertEquals(11 * 60, segments[1].endMinute)
        assertEquals(22 * 60, segments[2].startMinute)
        assertEquals(24 * 60, segments[2].endMinute)
        assertTrue(segments[2].continuesAfter)
    }

    @Test
    fun fullDaySpanIsClippedAndMarkedForEachIntersectedDay() {
        val schedule = entry(
            "c",
            "整段",
            at(date.minusDays(1), 12),
            at(nextDate, 12),
        )

        val today = projectTimetableAgendaDay(listOf(schedule), date).single()
        assertEquals(0, today.startMinute)
        assertEquals(24 * 60, today.endMinute)
        assertTrue(today.continuesBefore)
        assertTrue(today.continuesAfter)
        assertEquals("全天（跨日）", timetableAgendaSegmentTimeLabel(today))

        val tomorrow = projectTimetableAgendaDay(listOf(schedule), nextDate).single()
        assertEquals(0, tomorrow.startMinute)
        assertEquals(12 * 60, tomorrow.endMinute)
        assertEquals("00:00–12:00（接前日）", timetableAgendaSegmentTimeLabel(tomorrow))
    }

    @Test
    fun emptyOrReversedScheduleNeverCoversADay() {
        val zeroLength = entry("z", "零长度", at(date, 9), at(date, 9))
        val reversed = entry("r", "反向", at(date, 10), at(date, 9))

        assertFalse(timetableAgendaCoversDate(zeroLength, date))
        assertFalse(timetableAgendaCoversDate(reversed, date))
        assertTrue(projectTimetableAgendaDay(listOf(zeroLength, reversed), date).isEmpty())
    }

    @Test
    fun weekWindowUsesRealDateRangeRegardlessOfListOrder() {
        val dates = listOf(
            date.plusDays(4),
            date,
            date.plusDays(2),
            date.minusDays(1),
        )
        val window = timetableAgendaWeekWindow(dates)
        requireNotNull(window)
        assertEquals(at(date.minusDays(1), 0), window.fromInclusive)
        assertEquals(at(date.plusDays(5), 0), window.untilExclusive)
        assertNull(timetableAgendaWeekWindow(emptyList()))
    }

    @Test
    fun shanghaiNoteOnlyWhenDeviceZoneDiffers() {
        assertFalse(timetableAgendaNeedsShanghaiNote(ZoneId.of("Asia/Shanghai")))
        assertTrue(timetableAgendaNeedsShanghaiNote(ZoneId.of("UTC")))
        assertTrue(timetableAgendaNeedsShanghaiNote(ZoneId.of("America/New_York")))
    }

    @Test
    fun currentMinuteOnlyAppliesToTheActualShanghaiDate() {
        val now = at(date, 10, 30)

        assertEquals(10 * 60 + 30, agendaMinuteForDate(now, date))
        // 昨天或明天同一时段不能被标成「进行中」或「已结束」。
        assertNull(agendaMinuteForDate(now, date.plusDays(1)))
        assertNull(agendaMinuteForDate(now, date.minusDays(1)))
    }
}
