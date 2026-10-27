package com.upcpocket.app.data.school

import com.upcpocket.app.data.local.LocalDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 同步计划：日期来源、周数完整性、节次越界、重复同步的身份稳定性。
 */
class SchoolScheduleSyncTest {

    private val term = SchoolTerm(year = "2026", term = "1", totalWeeks = 2, label = "2026 秋季学期")
    private val periods = LocalDefaults.DEFAULT_PERIODS
    private val today = LocalDate.of(2026, 10, 28) // 周三

    private fun block(
        name: String,
        weekday: Int,
        start: Int,
        end: Int,
        teacher: String? = "张老师",
        room: String? = "南教101",
        id: String? = null,
    ) = SchoolClassBlock(name, weekday, start, end, teacher, room, id)

    private fun weeks(vararg classes: List<SchoolClassBlock>) = classes.toList()

    @Test
    fun `历史学期没有当前周线索时不编造日期`() {
        val plan = SchoolScheduleSync.build(
            term = term,
            weeklyWeeks = weeks(listOf(block("高数", 1, 1, 2)), emptyList()),
            periods = periods,
            today = today,
            schoolCurrentWeek = null,
        )
        assertNull(plan.weekOneStart)
        assertEquals(SchoolDateSource.NONE, plan.dateSource)
        assertTrue(plan.sessions.all { it.date == null })
        assertEquals(listOf(2), plan.emptyWeeks)
    }

    @Test
    fun `当前学期按学校当前周推算日期并标记来源`() {
        val plan = SchoolScheduleSync.build(
            term = term,
            weeklyWeeks = weeks(listOf(block("高数", 1, 1, 2)), emptyList()),
            periods = periods,
            today = today,
            schoolCurrentWeek = 2,
        )
        // 今天 2026-10-28 周三 → 本周周一 10-26；第 2 周 → 第 1 周周一 10-19
        assertEquals(LocalDate.of(2026, 10, 19), plan.weekOneStart)
        assertEquals(SchoolDateSource.DERIVED_CURRENT_WEEK, plan.dateSource)
        assertEquals("2026-10-19", plan.sessions.single().date)
    }

    @Test
    fun `已保存的日期优先于推算值`() {
        val saved = LocalDate.of(2026, 9, 7)
        val plan = SchoolScheduleSync.build(
            term = term,
            weeklyWeeks = weeks(listOf(block("高数", 1, 1, 2)), emptyList()),
            periods = periods,
            today = today,
            schoolCurrentWeek = 2,
            savedStart = saved,
        )
        assertEquals(saved, plan.weekOneStart)
        assertEquals(SchoolDateSource.SAVED, plan.dateSource)
        assertEquals("2026-09-07", plan.sessions.single().date)
    }

    @Test
    fun `学校 startday 只在没有已保存日期时使用`() {
        val schoolStart = LocalDate.of(2026, 9, 1)
        val plan = SchoolScheduleSync.build(
            term = term,
            weeklyWeeks = weeks(listOf(block("高数", 1, 1, 2)), emptyList()),
            periods = periods,
            schoolTermStart = schoolStart,
        )
        assertEquals(schoolStart, plan.weekOneStart)
        assertEquals(SchoolDateSource.SCHOOL_PARAMS, plan.dateSource)
    }

    @Test
    fun `周数不完整直接失败不生成半份课表`() {
        val e = runCatching {
            SchoolScheduleSync.build(
                term = term,
                weeklyWeeks = weeks(listOf(block("高数", 1, 1, 2))),
                periods = periods,
            )
        }.exceptionOrNull()
        assertTrue(e is SchoolScheduleFormatException)
        assertTrue(e!!.message!!.contains("1/2"))
    }

    @Test
    fun `超出当前作息的节次明确失败并给出所需节次`() {
        val e = runCatching {
            SchoolScheduleSync.build(
                term = term,
                weeklyWeeks = weeks(listOf(block("晚课", 3, 13, 14)), emptyList()),
                periods = periods,
            )
        }.exceptionOrNull()
        assertTrue(e is SchoolUnitsOutOfRangeException)
        assertEquals(14, (e as SchoolUnitsOutOfRangeException).requiredUnit)
    }

    @Test
    fun `同一课程跨周聚合为一门并保留稳定 code`() {
        val plan = SchoolScheduleSync.build(
            term = term,
            weeklyWeeks = weeks(
                listOf(block("高数", 1, 1, 2, id = "MATH01")),
                listOf(block("高数", 1, 1, 2, id = "MATH01")),
            ),
            periods = periods,
        )
        assertEquals(1, plan.courseCount)
        assertEquals(2, plan.sessions.size)
        assertEquals(plan.sessions.map { it.courseCode }.distinct().size, 1)
        assertTrue(plan.sessions.first().courseCode.startsWith("S"))
    }

    @Test
    fun `没有课程号时同名不同教师不会合并`() {
        val plan = SchoolScheduleSync.build(
            term = term,
            weeklyWeeks = weeks(
                listOf(block("物理", 1, 1, 2, teacher = "甲", id = null)),
                listOf(block("物理", 2, 3, 4, teacher = "乙", id = null)),
            ),
            periods = periods,
        )
        assertEquals(2, plan.courseCount)
    }

    @Test
    fun `rebase 只改日期并标记用户确认`() {
        val plan = SchoolScheduleSync.build(
            term = term,
            weeklyWeeks = weeks(listOf(block("高数", 1, 1, 2)), emptyList()),
            periods = periods,
        )
        val rebased = SchoolScheduleSync.rebase(plan, LocalDate.of(2026, 9, 7))
        assertEquals(SchoolDateSource.USER_CONFIRMED, rebased.dateSource)
        assertEquals("2026-09-07", rebased.sessions.single().date)
        assertEquals(plan.courses, rebased.courses)
    }

    @Test
    fun `空学期是合法结果`() {
        val plan = SchoolScheduleSync.build(term, weeks(emptyList(), emptyList()), periods)
        assertEquals(0, plan.sessions.size)
        assertEquals(listOf(1, 2), plan.emptyWeeks)
    }
}
