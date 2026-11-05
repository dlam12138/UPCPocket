package com.upcpocket.app.timetable

import androidx.compose.ui.graphics.luminance
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableCourseColorsTest {

    private fun meeting(
        name: String,
        courseId: String? = null,
        date: LocalDate = LocalDate.parse("2026-11-02"),
        start: Int = 1,
        end: Int = 2,
    ) = TimetableMeeting(
        courseName = name,
        courseId = courseId,
        weekday = date.dayOfWeek.value,
        startPeriod = start,
        endPeriod = end,
        periods = (start..end).toList(),
        sourcePeriods = (start..end).toList(),
        weeks = listOf(10),
        weekLabel = "10",
        sectionsSource = "test",
        date = date,
    )

    @Test
    fun `course identity ignores date and period so the same course keeps one color`() {
        val monday = meeting("高等数学 A", courseId = "MATH1001")
        val friday = meeting(
            "高等数学 A",
            courseId = "MATH1001",
            date = LocalDate.parse("2026-11-06"),
            start = 5,
            end = 6,
        )
        assertEquals(courseColorKey(monday), courseColorKey(friday))
        assertEquals(
            coursePaletteIndex(courseColorKey(monday)),
            coursePaletteIndex(courseColorKey(friday)),
        )
    }

    @Test
    fun `a normal week uses more than four distinguishable palette entries`() {
        val names = listOf(
            "高等数学 A",
            "大学物理",
            "程序设计基础",
            "学术英语",
            "体育",
            "数据结构",
            "概率论与数理统计",
            "大学英语视听说",
            "金工实习",
            "创新创业基础",
            "电路分析",
            "材料科学导论",
        )
        val indexes = names.map { coursePaletteIndex(courseColorKey(meeting(it))) }
        assertTrue(indexes.all { it in 0 until 16 })
        assertTrue("expected a spread palette, got $indexes", indexes.toSet().size >= 9)
    }

    @Test
    fun `light and dark containers keep text contrast above 4_5 to 1`() {
        fun ratio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
            val la = a.luminance()
            val lb = b.luminance()
            val lighter = maxOf(la, lb)
            val darker = minOf(la, lb)
            return (lighter + 0.05f) / (darker + 0.05f)
        }
        for (index in 0 until 16) {
            val light = courseToneColors(index, dark = false)
            val dark = courseToneColors(index, dark = true)
            assertTrue("light $index ${ratio(light.container, light.content)}",
                ratio(light.container, light.content) >= 4.5f)
            assertTrue("dark $index ${ratio(dark.container, dark.content)}",
                ratio(dark.container, dark.content) >= 4.5f)
        }
    }

    @Test
    fun `negative palette index still maps into the palette`() {
        assertTrue(courseToneColors(-1, dark = false).container != courseToneColors(0, dark = false).container)
    }
}
