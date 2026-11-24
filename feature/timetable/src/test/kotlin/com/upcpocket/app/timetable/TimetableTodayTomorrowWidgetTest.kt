package com.upcpocket.app.timetable

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableTodayTomorrowWidgetTest {

    private val today = LocalDate.parse("2026-11-30")
    private val tomorrow = LocalDate.parse("2026-12-01")

    private fun course(name: String, start: Int = 1, end: Int = 2) =
        TimetableTodayTomorrowWidgetCourse(name = name, startPeriod = start, endPeriod = end)

    @Test
    fun `period labels keep double digit ranges intact`() {
        val course = course("高等数学", 13, 14)

        assertEquals("第13–14节", course.periodLabel)
        assertEquals("13–14节", course.compactPeriodLabel)
        assertEquals("第7节", course("体育", 7, 7).periodLabel)
    }

    @Test
    fun `narrow large font keeps the full double digit period and name space`() {
        // 180×110 是 4×2 组件的最小缩放宽度；1.5× 系统字体下课程字号被封顶，
        // 字符预算只有 7，而“13–14节”本身约占 4.3 个中文字符宽。
        val layout = TimetableTodayTomorrowWidgetLayout.forSize(180, 110, 1.5f)

        assertEquals(1, layout.maxCoursesPerDay)
        assertEquals(7, layout.maxCourseChars)
        assertTrue(layout.compactPeriod)
        assertTrue(layout.compactDate)
        assertTrue(layout.dense)
        assertEquals(2f / 3f, layout.textScale, 0.001f)

        val lines = todayTomorrowCourseLines(
            courses = listOf(course("高等数学", 13, 14)),
            maxCourses = layout.maxCoursesPerDay,
            maxChars = layout.maxCourseChars,
            compactPeriod = layout.compactPeriod,
        )

        assertEquals(1, lines.lines.size)
        val line = lines.lines.single()
        // 节次永远完整；只允许省略课程名，且至少保留两个课程名字符与省略号。
        assertTrue("节次被截断：$line", line.startsWith("13–14节 "))
        val name = line.removePrefix("13–14节 ")
        assertTrue("课程名空间不足：$line", name.length >= 3)
        assertTrue("课程名未按预期省略：$line", name.endsWith("…"))
        assertEquals("高等…", name)
        assertEquals(0, lines.hiddenCount)
    }

    @Test
    fun `even the smallest budget never drops the period`() {
        val lines = todayTomorrowCourseLines(
            courses = listOf(course("高等数学", 13, 14)),
            maxCourses = 1,
            maxChars = 5,
            compactPeriod = true,
        )

        // 连一个课程名字符都放不下时只省略名称，仍完整显示“13–14节”。
        assertEquals("13–14节 …", lines.lines.single())
    }

    @Test
    fun `wide layout uses full period and more rows`() {
        val layout = TimetableTodayTomorrowWidgetLayout.forSize(320, 200, 1f)

        assertEquals(3, layout.maxCoursesPerDay)
        assertFalse(layout.compactPeriod)
        assertFalse(layout.compactDate)
        assertFalse(layout.dense)
        assertEquals(1f, layout.textScale, 0f)
        val lines = todayTomorrowCourseLines(
            courses = listOf(course("高等数学", 1, 2)),
            maxCourses = layout.maxCoursesPerDay,
            maxChars = layout.maxCourseChars,
            compactPeriod = layout.compactPeriod,
        )
        assertEquals("第1–2节 高等数学", lines.lines.single())
    }

    @Test
    fun `overflow count is the actual number of hidden courses`() {
        val lines = todayTomorrowCourseLines(
            courses = listOf(
                course("高等数学", 1, 2),
                course("大学物理", 3, 4),
                course("英语读写", 5, 6),
                course("程序设计", 7, 8),
                course("体育", 9, 10),
            ),
            maxCourses = 2,
            maxChars = 12,
            compactPeriod = true,
        )

        assertEquals(2, lines.lines.size)
        assertEquals(3, lines.hiddenCount)
        assertTrue(lines.lines[0].endsWith("高等数学"))
        assertTrue(lines.lines[1].endsWith("大学物理"))
    }

    @Test
    fun `courses stay in original order and overlapping periods remain independent`() {
        val lines = todayTomorrowCourseLines(
            courses = listOf(
                course("课程甲", 3, 4),
                course("课程乙", 3, 4),
                course("课程丙", 1, 2),
            ),
            maxCourses = 3,
            maxChars = 12,
            compactPeriod = true,
        )

        assertEquals(
            listOf("3–4节 课程甲", "3–4节 课程乙", "1–2节 课程丙"),
            lines.lines,
        )
        assertEquals(0, lines.hiddenCount)
    }

    @Test
    fun `snapshot requires two consecutive days for data states`() {
        val days = listOf(
            TimetableTodayTomorrowWidgetDay(
                today,
                TimetableTodayTomorrowWidgetDayState.EMPTY,
            ),
            TimetableTodayTomorrowWidgetDay(
                tomorrow,
                TimetableTodayTomorrowWidgetDayState.COURSES,
                listOf(course("高等数学")),
            ),
        )
        val snapshot = TimetableTodayTomorrowWidgetSnapshot(
            status = TimetableTodayTomorrowWidgetStatus.OK,
            today = today,
            tomorrow = tomorrow,
            days = days,
        )

        assertEquals(2, snapshot.days.size)
        assertFalse(snapshot.isReadResult)

        assertThrows {
            TimetableTodayTomorrowWidgetSnapshot(
                status = TimetableTodayTomorrowWidgetStatus.OK,
                today = today,
                tomorrow = tomorrow,
                days = days.reversed(),
            )
        }
        assertThrows {
            TimetableTodayTomorrowWidgetDay(
                today,
                TimetableTodayTomorrowWidgetDayState.UNSYNCED,
                listOf(course("高等数学")),
            )
        }
        assertThrows {
            TimetableTodayTomorrowWidgetDay(
                tomorrow,
                TimetableTodayTomorrowWidgetDayState.COURSES,
            )
        }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("应当拒绝不合法的组件模型")
    }
}
