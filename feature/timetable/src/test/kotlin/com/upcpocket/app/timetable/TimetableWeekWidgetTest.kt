package com.upcpocket.app.timetable

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本周课表模型、尺寸分层与行内省略的纯 JVM 约束。 */
class TimetableWeekWidgetTest {

    @Test
    fun `week starts on sunday and saturday stays in the current week`() {
        assertEquals(
            LocalDate.parse("2026-10-25"),
            TimetableWeekWidgetSnapshot.weekStartOf(LocalDate.parse("2026-10-25")),
        )
        assertEquals(
            LocalDate.parse("2026-10-25"),
            TimetableWeekWidgetSnapshot.weekStartOf(LocalDate.parse("2026-10-31")),
        )
        assertEquals(
            LocalDate.parse("2026-11-01"),
            TimetableWeekWidgetSnapshot.weekStartOf(LocalDate.parse("2026-11-01")),
        )
    }

    @Test
    fun `snapshot rejects non sunday start and non continuous days`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimetableWeekWidgetSnapshot(
                status = TimetableWeekWidgetStatus.OK,
                weekStart = LocalDate.parse("2026-10-26"),
                today = LocalDate.parse("2026-10-27"),
            )
        }
        val weekStart = LocalDate.parse("2026-10-25")
        assertThrows(IllegalArgumentException::class.java) {
            TimetableWeekWidgetSnapshot(
                status = TimetableWeekWidgetStatus.OK,
                weekStart = weekStart,
                today = weekStart,
                days = (0..5).map {
                    TimetableWeekWidgetDay(
                        weekStart.plusDays(it.toLong()),
                        TimetableWeekWidgetDayState.EMPTY,
                    )
                },
            )
        }
    }

    @Test
    fun `course lines report the real hidden count outside the text`() {
        val courses = listOf(
            TimetableWeekWidgetCourse("高等数学", 1, 2),
            TimetableWeekWidgetCourse("大学物理", 3, 4),
            TimetableWeekWidgetCourse("化学原理", 5, 6),
        )

        val lines = weekCourseLines(
            courses = courses,
            maxCourses = 2,
            maxChars = 30,
            compactPeriod = true,
        )

        assertEquals(1, lines.hiddenCount)
        // 省略数由独立视图显示，课程行本身只保留节次与课名。
        assertEquals(
            listOf("1–2节 高等数学", "3–4节 大学物理"),
            lines.lines,
        )
        assertFalse(lines.truncated)
    }

    @Test
    fun `line fitting keeps the real periods when the course name overflows`() {
        val courses = listOf(
            TimetableWeekWidgetCourse("非常非常长的合成课程名称", 1, 2),
            TimetableWeekWidgetCourse("另一门课程", 3, 4),
        )

        val lines = weekCourseLines(
            courses = courses,
            maxCourses = 1,
            maxChars = 10,
            compactPeriod = true,
        )

        assertTrue(lines.truncated)
        assertEquals(1, lines.hiddenCount)
        val line = lines.lines.single()
        assertTrue(line.startsWith("1–2节"))
        assertTrue(line.length <= 10)
    }

    @Test
    fun `layout tiers adapt to the actual 4x4 height and font scale`() {
        val fourByFour = TimetableWeekWidgetLayout.forSize(250, 250, 1f)
        assertEquals(1, fourByFour.maxCoursesPerDay)
        assertTrue(fourByFour.showTitle)
        assertTrue(fourByFour.showRange)
        assertTrue(fourByFour.showFooter)
        assertFalse(fourByFour.compact)
        assertFalse(fourByFour.dense)
        assertFalse(fourByFour.compactDayLabel)

        val medium = TimetableWeekWidgetLayout.forSize(320, 320, 1f)
        assertEquals(2, medium.maxCoursesPerDay)

        val tall = TimetableWeekWidgetLayout.forSize(320, 420, 1f)
        assertEquals(3, tall.maxCoursesPerDay)

        val narrow = TimetableWeekWidgetLayout.forSize(200, 250, 1f)
        assertTrue(narrow.compact)
        assertTrue(narrow.compactPeriod)
        assertEquals(12, narrow.maxCourseChars)
        assertFalse(narrow.compactDayLabel)

        val largeFont = TimetableWeekWidgetLayout.forSize(250, 180, 1.4f)
        assertTrue(largeFont.dense)
        assertFalse(largeFont.showTitle)
        assertFalse(largeFont.showFooter)
        assertEquals(1, largeFont.maxCoursesPerDay)
        assertFalse(largeFont.compactDayLabel)

        // 合法最小尺寸 + 大字号：整行只保留一天，单字星期让日期和 +N 都能显示。
        val minimumLargeFont = TimetableWeekWidgetLayout.forSize(180, 180, 1.4f)
        assertTrue(minimumLargeFont.compact)
        assertTrue(minimumLargeFont.dense)
        assertTrue(minimumLargeFont.compactDayLabel)
        assertEquals(1, minimumLargeFont.maxCoursesPerDay)
        assertFalse(minimumLargeFont.showTitle)
        assertFalse(minimumLargeFont.showFooter)
        assertEquals(1f, minimumLargeFont.textScale, 0.001f)

        // 更大字号把有效字号封顶，七个日期行仍能放进最小组件高度。
        val hugeFont = TimetableWeekWidgetLayout.forSize(180, 180, 2f)
        assertEquals(0.7f, hugeFont.textScale, 0.001f)
        assertTrue(hugeFont.compactDayLabel)
    }
}
