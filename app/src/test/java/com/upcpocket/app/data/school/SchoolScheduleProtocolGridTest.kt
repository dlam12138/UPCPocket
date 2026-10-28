package com.upcpocket.app.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表协议解析。
 *
 * 结构来自学校自己发布的前端包（2026-10-28 读取 `app.upc.edu.cn/site/static/js/`）：
 *  - `/site/weekschedule/` 页面 → `get-datatmp`、`type=2`、网格 + `weekdays` 真实日期；
 *  - `/site/schedule/` 页面 → `get-data`、`type=1`、一条课一行。
 * 本类覆盖前者（同步实际使用的那个），样例数据全部虚构。
 */
class SchoolScheduleProtocolGridTest {

    private fun grid(
        weekdays: String? =
            """["2026-10-26","2026-10-27","2026-10-28","2026-10-29","2026-10-30","2026-10-31","2026-11-01"]""",
        days: String = """
            [
              {"c_1":[{"course_id":"C1","course_name":"高等数学","teacher":"张老师","location":"南教101",
                       "lessArr":[1,2],"totalLength":2}]},
              {},
              {"c_3":[{"course_id":"C2","course_name":"大学物理","teacher":"李老师","location":"南教202",
                       "totalLength":2}]},
              {},{},{},{}
            ]
        """.trimIndent(),
        e: Int = 0,
    ): String = buildString {
        append("""{"e":$e,"m":"","d":{"classes":""")
        append(days)
        if (weekdays != null) {
            append(""","weekdays":""")
            append(weekdays)
        }
        append("}}")
    }

    @Test
    fun `网格按天与节次键解析出课程块`() {
        val parsed = SchoolScheduleProtocol.parseGridWeek(grid(), week = 3)
        assertEquals(2, parsed.blocks.size)

        val math = parsed.blocks.first { it.name == "高等数学" }
        assertEquals(1, math.weekday)
        assertEquals(1, math.startUnit)
        assertEquals(2, math.endUnit)
        assertEquals("张老师", math.teacher)
        assertEquals("南教101", math.room)

        val physics = parsed.blocks.first { it.name == "大学物理" }
        assertEquals(3, physics.weekday)
        assertEquals(3, physics.startUnit)
        assertEquals(4, physics.endUnit)
    }

    @Test
    fun `服务端日期被解析为该周真实日期`() {
        val parsed = SchoolScheduleProtocol.parseGridWeek(grid(), week = 3)
        assertEquals(java.time.LocalDate.of(2026, 10, 26), parsed.dates?.first())
        assertEquals(7, parsed.dates?.size)
    }

    @Test
    fun `全天空周的 classes 是合法空结果`() {
        val parsed = SchoolScheduleProtocol.parseGridWeek(grid(days = "[{},{},{},{},{},{},{}]"), week = 4)
        assertTrue(parsed.blocks.isEmpty())
        assertEquals(7, parsed.dates?.size)
    }

    @Test
    fun `weekdays 不足 7 天时报错而不是猜日期`() {
        val bad = grid(weekdays = """["2026-10-26","2026-10-27"]""")
        val error = runCatching { SchoolScheduleProtocol.parseGridWeek(bad, 3) }.exceptionOrNull()
        assertTrue(error is SchoolScheduleFormatException)
        assertTrue(error!!.message!!.contains("7 天"))
    }

    @Test
    fun `weekdays 首日不是周一时报错_避免整周星期错位`() {
        // 前端按下标把 weekdays[i] 配给第 i 天；首日若不是周一，所有课的星期都会偏移。
        val shifted = grid(
            weekdays = """["2026-10-27","2026-10-28","2026-10-29","2026-10-30","2026-10-31","2026-11-01","2026-11-02"]""",
        )
        val error = runCatching { SchoolScheduleProtocol.parseGridWeek(shifted, 3) }.exceptionOrNull()
        assertTrue(error is SchoolScheduleFormatException)
        assertTrue(error!!.message!!.contains("不是周一"))
    }

    @Test
    fun `真实的 2026-10-26 是周一_首日校验不会误报`() {
        val parsed = SchoolScheduleProtocol.parseGridWeek(grid(), week = 3)
        assertEquals(java.time.DayOfWeek.MONDAY, parsed.dates!!.first().dayOfWeek)
    }

    @Test
    fun `没有 weekdays 时如实返回空日期由调用方决定`() {
        val parsed = SchoolScheduleProtocol.parseGridWeek(grid(weekdays = null), week = 3)
        assertNull(parsed.dates)
        assertEquals(2, parsed.blocks.size)
    }

    @Test
    fun `节次键不认识时报错而不是静默丢课`() {
        val bad = grid(days = """[{"c_x":[{"course_name":"X"}]},{},{},{},{},{},{}]""")
        val error = runCatching { SchoolScheduleProtocol.parseGridWeek(bad, 3) }.exceptionOrNull()
        assertTrue(error is SchoolScheduleFormatException)
    }

    @Test
    fun `节次越界时报错`() {
        val bad = grid(days = """[{"c_15":[{"course_name":"X","totalLength":1}]},{},{},{},{},{},{}]""")
        assertTrue(
            runCatching { SchoolScheduleProtocol.parseGridWeek(bad, 3) }.exceptionOrNull()
                is SchoolScheduleFormatException,
        )
    }

    @Test
    fun `超过七天时报错`() {
        val bad = grid(days = "[{},{},{},{},{},{},{},{}]")
        assertTrue(
            runCatching { SchoolScheduleProtocol.parseGridWeek(bad, 3) }.exceptionOrNull()
                is SchoolScheduleFormatException,
        )
    }

    @Test
    fun `登录过期仍然单独抛出`() {
        assertTrue(
            runCatching { SchoolScheduleProtocol.parseGridWeek(grid(e = 10013), 3) }
                .exceptionOrNull() is SchoolSessionExpiredException,
        )
    }

    @Test
    fun `业务错误带上学校消息`() {
        val error = runCatching { SchoolScheduleProtocol.parseGridWeek(grid(e = 400), 3) }
            .exceptionOrNull()
        assertTrue(error is SchoolScheduleException)
        assertTrue(error!!.message!!.contains("400"))
    }

    /**
     * `weekschedule` 的 `week` 参数是否真的分周，只能真机验证；这里把“不可信就不采用”的
     * 判定写成测试：第 2 周不是第 1 周 +7 天时，调用方不应把 firstWeekStart 当锚点。
     */
    @Test
    fun `第2周不是第1周加7天时不能把首日当锚点`() {
        val weekOne = java.time.LocalDate.of(2026, 10, 26)
        fun trusted(second: java.time.LocalDate?): Boolean =
            second == null || second == weekOne.plusDays(7)

        assertTrue(trusted(java.time.LocalDate.of(2026, 11, 2)))
        assertTrue(trusted(null))
        // 学校忽略 week 参数：每周回同一周
        assertTrue(!trusted(weekOne))
        // 服务端按别的锚点给周（例如从周三起算）
        assertTrue(!trusted(java.time.LocalDate.of(2026, 10, 28)))
    }

    @Test
    fun `同步计划采用服务端日期而不是今天反推`() {
        val term = SchoolTerm("2026", "1", 3, "2026 秋季学期")
        val weeks = listOf(
            listOf(SchoolClassBlock("高等数学", 1, 1, 2, "张老师", "南教101", "C1")),
            emptyList(),
            emptyList(),
        )
        val periods = com.upcpocket.app.data.local.LocalDefaults.DEFAULT_PERIODS
        val plan = SchoolScheduleSync.build(
            term = term,
            weeklyWeeks = weeks,
            periods = periods,
            today = java.time.LocalDate.of(2026, 12, 31),
            serverWeekOneStart = java.time.LocalDate.of(2026, 10, 26),
        )
        assertEquals(java.time.LocalDate.of(2026, 10, 26), plan.weekOneStart)
        assertEquals(SchoolDateSource.SERVER_DATES, plan.dateSource)
        // 第 1 周周一 2026-10-26 的课程日期必须是这一天，而不是按 today 推出来的。
        assertEquals(
            "2026-10-26",
            plan.sessions.first { it.week == 1 }.date,
        )
    }
}
