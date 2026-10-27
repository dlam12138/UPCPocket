package com.upcpocket.app.data.local

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地默认值的测试。
 *
 * 这里的值会被写进数据库（作息 / 学期），所以两件事都不能错：
 *  - 节次必须连续、起止时间递增（否则网格会错位）；
 *  - "按今天推断学期名 / 开学日"必须与日期自洽（否则用户一进来周次就是错的）。
 */
class LocalDefaultsTest {

    @Test
    fun `默认作息是连续的十节`() {
        val periods = LocalDefaults.DEFAULT_PERIODS
        assertEquals(10, periods.size)
        assertEquals((1..10).toList(), periods.map { it.indexNo })
        assertEquals(10, LocalDefaults.PERIOD_COUNT)
    }

    @Test
    fun `每节的结束时间晚于开始时间`() {
        LocalDefaults.DEFAULT_PERIODS.forEach { p ->
            assertTrue("第 ${p.indexNo} 节时间倒挂：${p.startText}-${p.endText}", p.endTime > p.startTime)
            // 800 → "08:00" 这种四位整数表示，不能出现 5 位
            assertTrue("第 ${p.indexNo} 节时间格式异常", p.startTime in 0..2359 && p.endTime in 0..2359)
        }
    }

    @Test
    fun `相邻节次不重叠`() {
        val sorted = LocalDefaults.DEFAULT_PERIODS.sortedBy { it.startTime }
        sorted.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "第 ${a.indexNo} 节与第 ${b.indexNo} 节重叠：${a.endText} > ${b.startText}",
                a.endTime <= b.startTime,
            )
        }
    }

    @Test
    fun `节次按上午下午晚上分段`() {
        val parts = LocalDefaults.DEFAULT_PERIODS.map { it.dayPart }.distinct()
        assertEquals(listOf("MORNING", "AFTERNOON", "EVENING"), parts)
    }

    @Test
    fun `秋季学期名按学年跨年推算`() {
        // 9 月属于 2025-2026 第一学期
        assertEquals("2025-2026 第一学期", LocalDefaults.defaultSemesterName(LocalDate.of(2025, 9, 10)))
        assertEquals("2025-2026", LocalDefaults.defaultSchoolYear(LocalDate.of(2025, 9, 10)))
        // 1 月仍属同一学年的第一学期
        assertEquals("2025-2026 第一学期", LocalDefaults.defaultSemesterName(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `春季学期名归到同一学年的第二学期`() {
        assertEquals("2025-2026 第二学期", LocalDefaults.defaultSemesterName(LocalDate.of(2026, 3, 1)))
        assertEquals("2025-2026", LocalDefaults.defaultSchoolYear(LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `默认开学日是今天所在那一周的周一`() {
        // 2026-10-27 是周二 → 默认开学日应为 2026-10-26（周一）
        val today = LocalDate.of(2026, 10, 27)
        val start = LocalDefaults.defaultStartDate(today)
        assertEquals(LocalDate.of(2026, 10, 26), start)
        assertEquals(1, start.dayOfWeek.value)
        // 周一当天仍然是它自己
        assertEquals(start, LocalDefaults.defaultStartDate(start))
    }

    @Test
    fun `默认开学日让今天落在第 1 周`() {
        val today = LocalDate.of(2026, 10, 27)
        val start = LocalDefaults.defaultStartDate(today)
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, today)
        assertTrue("今天应落在第 1 周内", days in 0..6)
    }

    @Test
    fun `默认周数是正数且不超过一学期常见上限`() {
        assertTrue(LocalDefaults.DEFAULT_TOTAL_WEEKS in 1..30)
    }

    @Test
    fun `默认作息不包含任何真实课表内容`() {
        // 作息里只有节次名与时间，不该出现课名/教师/教室这类业务字段
        LocalDefaults.DEFAULT_PERIODS.forEach { p ->
            assertTrue(p.nameZh.startsWith("第"))
            assertFalse(p.nameZh.contains("演示"))
        }
    }
}
