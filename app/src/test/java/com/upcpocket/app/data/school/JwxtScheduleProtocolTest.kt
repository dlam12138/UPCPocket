package com.upcpocket.app.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务当前页解析的严格性。
 *
 * 样本全部是虚构课程，只验证解析规则，不代表 UPC 真实页面的 DOM 已被验证。
 * 关键约束：一处坏数据整批失败、单双周按段展开、学期/节次不猜、教师教室不按行序猜。
 */
class JwxtScheduleProtocolTest {

    private fun course(vararg lines: String): String =
        """{"lines":[""" + lines.joinToString(",") { "\"$it\"" } + """]}"""

    private fun cell(
        day: Int,
        start: Int,
        end: Int,
        unit: String,
        vararg courses: String,
        rowSpan: Int = 1,
        colSpan: Int = 1,
    ): String = """{"day":$day,"startUnit":$start,"endUnit":$end,"unitText":"$unit",""" +
        """"rowSpan":$rowSpan,"colSpan":$colSpan,"courses":[${courses.joinToString(",")}]}"""

    private fun payload(
        cells: String,
        term: String = """{"value":"2026-2027-1","label":"2026-2027学年第一学期"}""",
        maxWeek: Int = 16,
        frames: String = """[{"kind":"main","sameOrigin":true}]""",
    ): String = """{"ok":true,"term":$term,"weekSelectorMax":$maxWeek,"frames":$frames,"cells":[$cells]}"""

    private fun failure(body: String): String {
        try {
            JwxtScheduleProtocol.parse(body)
        } catch (e: SchoolScheduleFormatException) {
            return e.message.orEmpty()
        }
        throw AssertionError("应当解析失败，但返回了成功")
    }

    @Test
    fun `evaluateJavascript 的字符串返回值按 JSON 解码`() {
        assertEquals(
            """{"ok":true,"cells":[]}""",
            JwxtScheduleProtocol.decodeEvaluateResult(""" "{\"ok\":true,\"cells\":[]}" """),
        )
        assertNull(JwxtScheduleProtocol.decodeEvaluateResult("null"))
        assertNull(JwxtScheduleProtocol.decodeEvaluateResult("undefined"))
        assertNull(JwxtScheduleProtocol.decodeEvaluateResult("""{"ok":true}"""))
        assertNull(JwxtScheduleProtocol.decodeEvaluateResult(""))
    }

    @Test
    fun `开学日必须由用户给出真实日期`() {
        assertNull(JwxtScheduleProtocol.parseStartDate(""))
        assertNull(JwxtScheduleProtocol.parseStartDate("2026-02-30"))
        assertNull(JwxtScheduleProtocol.parseStartDate("2026-9-1"))
        assertEquals("2026-09-01", JwxtScheduleProtocol.parseStartDate("2026-09-01").toString())
    }

    @Test
    fun `第 1 周日期必须是周一`() {
        assertTrue(JwxtScheduleProtocol.isMonday("2026-09-07"))
        assertFalse(JwxtScheduleProtocol.isMonday("2026-09-08"))
        assertFalse(JwxtScheduleProtocol.isMonday("2026-09-06"))
        assertFalse(JwxtScheduleProtocol.isMonday(""))
    }

    @Test
    fun `分段单双周各自展开`() {
        val body = payload(
            cell(1, 1, 2, "1-2", course("高等数学", "1-8周(单),10-16周(双)", "1教101")),
        )
        val parsed = JwxtScheduleProtocol.parse(body)
        val weeks = parsed.blocks.indices.filter { parsed.blocks[it].isNotEmpty() }.map { it + 1 }
        assertEquals(listOf(1, 3, 5, 7, 10, 12, 14, 16), weeks)
        assertEquals(16, parsed.totalWeeks)
    }

    @Test
    fun `逗号列表周次只带一个周字也能展开`() {
        val body = payload(
            cell(4, 5, 6, "5-6", course("数据库", "1,3,5周", "4教401")),
            maxWeek = 8,
        )
        val parsed = JwxtScheduleProtocol.parse(body)
        val weeks = parsed.blocks.indices.filter { parsed.blocks[it].isNotEmpty() }.map { it + 1 }
        assertEquals(listOf(1, 3, 5), weeks)
    }

    @Test
    fun `同一单元格多门课全部保留`() {
        val body = payload(
            cell(
                2, 3, 4, "3-4",
                course("大学物理", "1-16周", "2教201"),
                course("体育", "1-16周", "操场"),
            ),
        )
        val parsed = JwxtScheduleProtocol.parse(body)
        assertEquals(2, parsed.courses.size)
        assertEquals(2, parsed.blocks[0].size)
        assertEquals(setOf("大学物理", "体育"), parsed.blocks[0].map { it.name }.toSet())
    }

    @Test
    fun `第二门课周次坏掉时整批失败并指出课程`() {
        val body = payload(
            cell(
                2, 3, 4, "3-4",
                course("大学物理", "1-16周", "2教201"),
                course("坏课", "周次待定", "3教"),
            ),
        )
        val message = failure(body)
        assertTrue(message, message.contains("坏课"))
    }

    @Test
    fun `非法学期不得默认成第一学期`() {
        val body = payload(
            cell(1, 1, 2, "1-2", course("高等数学", "1-16周", "1教101")),
            term = """{"value":"2026-2027-9","label":"非法学期"}""",
        )
        val message = failure(body)
        assertTrue(message, message.contains("2026-2027-9"))
        // 不能悄悄按第 1 学期成功入库：失败文本本身说明未自动纠正。
        assertTrue(message, message.contains("无法识别"))
    }

    @Test
    fun `学期缺失时返回待确认而不是编造`() {
        val body = payload(
            cell(1, 1, 2, "1-2", course("高等数学", "1-16周", "1教101")),
            term = "null",
        )
        val parsed = JwxtScheduleProtocol.parse(body)
        assertNull(parsed.suggestedTerm)
        assertNull(JwxtScheduleProtocol.parseTermInput("2026-2027-9"))
        assertNull(JwxtScheduleProtocol.parseTermInput("2026-2027"))
        assertEquals("2026" to "1", JwxtScheduleProtocol.parseTermInput("2026-2027-1"))
    }

    @Test
    fun `节次缺失时整批失败`() {
        val body = payload(cell(1, 0, 0, "", course("高等数学", "1-16周", "1教101")))
        val message = failure(body)
        assertTrue(message, message.contains("节次"))
    }

    @Test
    fun `非法星期整批失败`() {
        val body = payload(cell(8, 1, 2, "1-2", course("高等数学", "1-16周", "1教101")))
        val message = failure(body)
        assertTrue(message, message.contains("星期"))
    }

    @Test
    fun `跨列合并单元格无法归属时失败`() {
        val body = payload(
            cell(1, 1, 2, "1-2", course("高等数学", "1-16周", "1教101"), colSpan = 2),
        )
        val message = failure(body)
        assertTrue(message, message.contains("跨列"))
    }

    @Test
    fun `超出页面周次范围不被截断`() {
        val body = payload(
            cell(1, 1, 2, "1-2", course("高等数学", "1-18周", "1教101")),
            maxWeek = 16,
        )
        val message = failure(body)
        assertTrue(message, message.contains("18"))
    }

    @Test
    fun `倒序周次不被自动翻转`() {
        val body = payload(cell(1, 1, 2, "1-2", course("高等数学", "16-1周", "1教101")))
        val message = failure(body)
        assertTrue(message, message.contains("周次"))
    }

    @Test
    fun `教师教室只在带标签时采用`() {
        val unlabeled = JwxtScheduleProtocol.parse(
            payload(cell(1, 1, 2, "1-2", course("高等数学", "张老师", "1-16周", "1教101"))),
        )
        assertNull(unlabeled.courses.single().teacher)
        assertNull(unlabeled.courses.single().room)

        val labeled = JwxtScheduleProtocol.parse(
            payload(
                cell(
                    1, 1, 2, "1-2",
                    course("高等数学", "教师：李老师", "地点：3教305", "1-16周"),
                ),
            ),
        )
        assertEquals("李老师", labeled.courses.single().teacher)
        assertEquals("3教305", labeled.courses.single().room)
    }

    @Test
    fun `同一节次跨多行时按明确节次生成`() {
        val body = payload(
            cell(3, 3, 5, "3-5", course("程序设计", "1-16周", "机房 2"), rowSpan = 3),
        )
        val parsed = JwxtScheduleProtocol.parse(body)
        val block = parsed.blocks[0].single()
        assertEquals(3, block.startUnit)
        assertEquals(5, block.endUnit)
    }

    @Test
    fun `非课表页面明确失败`() {
        val body = """{"ok":false,"error":"NO_TABLE","tableCount":2}"""
        val message = failure(body)
        assertTrue(message, message.contains("学期理论课表"))
    }

    @Test
    fun `非同源框架声明被拒绝`() {
        val body = payload(
            cell(1, 1, 2, "1-2", course("高等数学", "1-16周", "1教101")),
            frames = """[{"kind":"main","sameOrigin":true},{"kind":"iframe","sameOrigin":false}]""",
        )
        val message = failure(body)
        assertTrue(message, message.contains("非同源"))
    }

    @Test
    fun `页面上多行周次信息无法确定时失败`() {
        val body = payload(
            cell(1, 1, 2, "1-2", course("高等数学", "1-8周", "10-16周", "1教101")),
        )
        val message = failure(body)
        assertTrue(message, message.contains("多行周次"))
    }
    @Test
    fun `强智显式周次节次元数据必须与表格一致`() {
        val valid = JwxtScheduleProtocol.parse(payload(
            cell(7, 1, 2, "第1-2节", course("虚构课程", "教师：虚构教师", "教室：虚构教室", "1-16(周)[01-02节]")),
        ))
        assertEquals("虚构教师", valid.courses.single().teacher)
        assertEquals("虚构教室", valid.courses.single().room)
        assertEquals(7, valid.blocks[0].single().weekday)
        val invalid = failure(payload(
            cell(1, 3, 4, "第3-4节", course("虚构课程", "1-16(周)[01-02节]")),
        ))
        assertTrue(invalid, invalid.contains("不一致"))
    }

    /**
     * fixture 已在 headless Edge 里实际执行过 EXTRACTION_TEMPLATE（见交付说明）；
     * 这里只保证资源还在、关键标注没被改坏，防止以后改 DOM 约定时只剩口头约定。
     */
    @Test
    fun `合成 font 标题 DOM fixture 保留关键结构`() {
        val html = javaClass.getResourceAsStream("/jwxt/font-title.html")
            ?.bufferedReader()?.use { it.readText() }
        assertTrue("fixture 缺失", html != null)
        assertTrue(html!!.contains("font title=\"老师\""))
        assertTrue(html.contains("font title=\"周次(节次)\""))
        assertTrue(html.contains("1-16(周)[01-02节]"))
        assertTrue(html.contains("星期天"))
    }

}
