package com.upcpocket.app.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表协议严格性：合法空周与结构异常必须分开。
 *
 * 旧导出脚本把非数组 `classes`、非对象课程、缺节次都当空数组/跳过，
 * 于是“字段变化”会伪装成“这一周没课”，整学期仍显示完成。这里逐条钉住。
 */
class SchoolScheduleProtocolTest {

    private val indexJson = """
        {"e":0,"m":"","d":{
          "termInfo":[
            {"year":"2026","term":"1","countweek":18},
            {"year":"2025","term":"2","countweek":20}
          ],
          "params":{"year":"2026","term":"1","week":"3","startday":"2026-09-07 00:00:00"}
        }}
    """.trimIndent()

    @Test
    fun `学期列表解析当前学期与周数`() {
        val page = SchoolScheduleProtocol.parseIndex(indexJson)
        assertEquals(2, page.terms.size)
        assertEquals("2026", page.current?.year)
        assertEquals("1", page.current?.term)
        assertEquals(18, page.current?.totalWeeks)
        assertEquals(3, page.currentWeek)
        assertEquals("2026-09-07", page.currentTermStart.toString())
        assertEquals("2026-2027 第一学期", page.current?.displayName)
    }

    @Test
    fun `登录 HTML 或损坏 JSON 报格式错误而不是空学期`() {
        val e = runCatching { SchoolScheduleProtocol.parseIndex("<html>CAS Login</html>") }.exceptionOrNull()
        assertTrue(e is SchoolScheduleFormatException)
        val noE = runCatching { SchoolScheduleProtocol.parseIndex("""{"d":{}}""") }.exceptionOrNull()
        assertTrue(noE is SchoolScheduleFormatException)
    }

    @Test
    fun `登录过期 e=10013 单独识别`() {
        val e = runCatching {
            SchoolScheduleProtocol.parseIndex("""{"e":10013,"m":"请先登录"}""")
        }.exceptionOrNull()
        assertTrue(e is SchoolSessionExpiredException)
    }

    @Test
    fun `学期周数为 0 或超限报错`() {
        val zero = runCatching {
            SchoolScheduleProtocol.parseIndex(
                """{"e":0,"d":{"termInfo":[{"year":"2026","term":"1","countweek":0}],"params":{}}}""",
            )
        }.exceptionOrNull()
        assertTrue(zero is SchoolScheduleFormatException)
        val tooMany = runCatching {
            SchoolScheduleProtocol.parseIndex(
                """{"e":0,"d":{"termInfo":[{"year":"2026","term":"1","countweek":99}],"params":{}}}""",
            )
        }.exceptionOrNull()
        assertTrue(tooMany is SchoolScheduleFormatException)
    }

    @Test
    fun `正常周课表解析连续节次`() {
        val body = """
            {"e":0,"d":{"classes":[
              {"course_name":"高等数学","weekday":"1","lessArr":[1,2,3],"teacher":"张老师",
               "location":"南教101","course_id":"MATH01"},
              {"course_name":"大学物理","weekday":3,"lessArr":[5,6],"teacher":"李老师",
               "location":"南教202","course_id":"PHY01"}
            ]}}
        """.trimIndent()
        val blocks = SchoolScheduleProtocol.parseWeek(body, week = 1)
        assertEquals(2, blocks.size)
        val math = blocks.first { it.name == "高等数学" }
        assertEquals(1, math.startUnit)
        assertEquals(3, math.endUnit)
        assertEquals("MATH01", math.courseId)
    }

    @Test
    fun `学校明确返回空周是合法空列表`() {
        assertTrue(SchoolScheduleProtocol.parseWeek("""{"e":0,"d":{"classes":[]}}""", 4).isEmpty())
    }

    @Test
    fun `缺 classes 数组报错`() {
        val e = runCatching {
            SchoolScheduleProtocol.parseWeek("""{"e":0,"d":{}}""", 2)
        }.exceptionOrNull()
        assertTrue(e is SchoolScheduleFormatException)
    }

    @Test
    fun `非数组或非对象结构不被解释成空周`() {
        val objectClasses = runCatching {
            SchoolScheduleProtocol.parseWeek("""{"e":0,"d":{"classes":{"c_1":[]}}}""", 2)
        }.exceptionOrNull()
        assertTrue(objectClasses is SchoolScheduleFormatException)

        val badItem = runCatching {
            SchoolScheduleProtocol.parseWeek("""{"e":0,"d":{"classes":["oops"]}}""", 2)
        }.exceptionOrNull()
        assertTrue(badItem is SchoolScheduleFormatException)
    }

    @Test
    fun `缺名称星期或节次报错`() {
        val noName = runCatching {
            SchoolScheduleProtocol.parseWeek(
                """{"e":0,"d":{"classes":[{"weekday":1,"lessArr":[1]}]}}""", 1,
            )
        }.exceptionOrNull()
        assertTrue(noName is SchoolScheduleFormatException)
        val noWeekday = runCatching {
            SchoolScheduleProtocol.parseWeek(
                """{"e":0,"d":{"classes":[{"course_name":"X","lessArr":[1]}]}}""", 1,
            )
        }.exceptionOrNull()
        assertTrue(noWeekday is SchoolScheduleFormatException)
        val noUnits = runCatching {
            SchoolScheduleProtocol.parseWeek(
                """{"e":0,"d":{"classes":[{"course_name":"X","weekday":1}]}}""", 1,
            )
        }.exceptionOrNull()
        assertTrue(noUnits is SchoolScheduleFormatException)
    }

    @Test
    fun `非法节次（小数、越界、字符串）报错而不是静默过滤`() {
        val decimal = runCatching {
            SchoolScheduleProtocol.parseWeek(
                """{"e":0,"d":{"classes":[{"course_name":"X","weekday":1,"lessArr":[1.5]}]}}""", 1,
            )
        }.exceptionOrNull()
        assertTrue(decimal is SchoolScheduleFormatException)

        val outOfRange = runCatching {
            SchoolScheduleProtocol.parseWeek(
                """{"e":0,"d":{"classes":[{"course_name":"X","weekday":1,"lessArr":[1,15]}]}}""", 1,
            )
        }.exceptionOrNull()
        assertTrue(outOfRange is SchoolScheduleFormatException)

        val text = runCatching {
            SchoolScheduleProtocol.parseWeek(
                """{"e":0,"d":{"classes":[{"course_name":"X","weekday":1,"lessArr":["2"]}]}}""", 1,
            )
        }.exceptionOrNull()
        assertTrue(text is SchoolScheduleFormatException)
    }

    @Test
    fun `跨节次不连续会拆成多条`() {
        val body = """
            {"e":0,"d":{"classes":[{"course_name":"实验","weekday":2,"lessArr":[1,2,5],"teacher":"王",
              "location":"实验楼"}]}}
        """.trimIndent()
        val blocks = SchoolScheduleProtocol.parseWeek(body, 1)
        assertEquals(2, blocks.size)
        assertEquals(listOf(1 to 2, 5 to 5), blocks.map { it.startUnit to it.endUnit })
    }
}
