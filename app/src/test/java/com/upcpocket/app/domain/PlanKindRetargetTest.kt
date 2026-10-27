package com.upcpocket.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「类型记在 code 前缀上」的标识迁移测试。
 *
 * 这是 P1 缺陷的根因所在：编辑日程时把类型从「日程」改成「课程」，等于换了一个标识
 * （`P…` → `C…`），而旧标识的课表行、courses 行、显示覆盖都得跟着清理。
 * 一旦"新 code 是什么"算错，清理就会指向错误的行 —— 结果就是网格里同一门课出现两次、
 * 课程总览留下一条删不掉的残留。
 *
 * 所以 [PlanKind.retarget] 必须是纯函数并且钉死：**基名一位都不能变**。
 */
class PlanKindRetargetTest {

    @Test
    fun `日程改课程只换类型位`() {
        assertEquals("Cm1abc", PlanKind.retarget("Pm1abc", PlanKind.COURSE))
    }

    @Test
    fun `课程改日程只换类型位`() {
        assertEquals("Pm1abc", PlanKind.retarget("Cm1abc", PlanKind.EVENT))
    }

    @Test
    fun `类型本来就对时原样返回`() {
        assertEquals("Cm1abc", PlanKind.retarget("Cm1abc", PlanKind.COURSE))
        assertEquals("Pm1abc", PlanKind.retarget("Pm1abc", PlanKind.EVENT))
    }

    @Test
    fun `基名保持不变_不会吞掉字符也不会重复前缀`() {
        val base = "m1abcdef"
        listOf(PlanKind.EVENT, PlanKind.COURSE).forEach { kind ->
            val code = PlanKind.retarget("P$base", kind)
            assertEquals(PlanKind.codePrefix(kind) + base, code)
            // 反复调用是幂等的（编辑多次也不会把标识越改越短）
            assertEquals(code, PlanKind.retarget(code, kind))
        }
    }

    @Test
    fun `迁移后读回来的类型就是新类型`() {
        assertEquals(PlanKind.COURSE, PlanKind.ofCode(PlanKind.retarget("Pm1abc", PlanKind.COURSE)))
        assertEquals(PlanKind.EVENT, PlanKind.ofCode(PlanKind.retarget("Cm1abc", PlanKind.EVENT)))
    }

    @Test
    fun `换类型一定改变标识_否则旧行清不掉`() {
        // 这条是缺陷的判据：如果换类型时 code 不变，就无从用"旧 code"去清旧行
        val fromEvent = PlanKind.retarget("Pm1abc", PlanKind.COURSE)
        assertNotEquals("Pm1abc", fromEvent)
        val fromCourse = PlanKind.retarget("Cm1abc", PlanKind.EVENT)
        assertNotEquals("Cm1abc", fromCourse)
    }

    @Test
    fun `异常前缀不会被咬掉首字符`() {
        // 老数据/手改数据可能不是 C/P 开头。此时整体当基名，保住用户数据的完整性
        assertEquals("Cx123", PlanKind.retarget("x123", PlanKind.COURSE))
        assertEquals("Px123", PlanKind.retarget("x123", PlanKind.EVENT))
        assertTrue(PlanKind.retarget("x123", PlanKind.COURSE).endsWith("x123"))
    }

    @Test
    fun `单字符标识不会崩`() {
        assertEquals("C", PlanKind.retarget("P", PlanKind.COURSE))
        assertEquals("P", PlanKind.retarget("C", PlanKind.EVENT))
    }
}
