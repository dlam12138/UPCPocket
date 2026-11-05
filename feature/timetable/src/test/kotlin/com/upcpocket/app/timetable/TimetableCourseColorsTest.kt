package com.upcpocket.app.timetable

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt
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
            courseToneColors(3, dark = false),
            courseToneColors(3, dark = true),
        )
    }

    @Test
    fun `palette has twenty perceptually separated colors and readable text`() {
        val containers = (0 until COURSE_COLOR_CAPACITY).map {
            courseToneColors(it, dark = false).container
        }
        assertEquals(COURSE_COLOR_CAPACITY, containers.toSet().size)
        for (index in 0 until COURSE_COLOR_CAPACITY) {
            val tone = courseToneColors(index, dark = false)
            assertTrue(
                "contrast $index container=${tone.container} content=${tone.content} " +
                    "ratio=${contrast(tone.container, tone.content)}",
                contrast(tone.container, tone.content) >= 4.5f,
            )
            for (other in index + 1 until COURSE_COLOR_CAPACITY) {
                val distance = deltaE76(tone.container, courseToneColors(other, false).container)
                assertTrue("ΔE($index,$other)=$distance", distance >= 15.0)
            }
        }
    }

    @Test
    fun `known colliding hash identities get different assigned slots`() {
        val first = "概率论与数理统计"
        val second = "程序设计基础"
        val store = TimetableColorStore.inMemory()
        val slots = store.assign(
            accountId = "account-1234",
            termKey = "2026-2027|1",
            identities = listOf(first, second),
        )
        assertEquals(2, slots.values.toSet().size)
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun deltaE76(a: Color, b: Color): Double {
        val la = lab(a)
        val lb = lab(b)
        return sqrt(
            (la[0] - lb[0]).pow(2) + (la[1] - lb[1]).pow(2) + (la[2] - lb[2]).pow(2),
        )
    }

    private fun lab(color: Color): DoubleArray {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(color.red)
        val g = linear(color.green)
        val b = linear(color.blue)
        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = r * 0.2126 + g * 0.7152 + b * 0.0722
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        fun f(value: Double): Double =
            if (value > 0.008856) value.pow(1.0 / 3.0) else 7.787 * value + 16.0 / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }
}
