package com.upcpocket.app.data.local

import com.upcpocket.app.data.repo.ScheduleRepository
import com.upcpocket.app.domain.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 演示课表的**隔离性**测试。
 *
 * 这是 M1 最要紧的一条产品约束：演示数据可以让人看到完整界面，但
 * **绝不能**混进用户自己的数据里、也不能看起来像真实校园数据。
 *
 * 这里钉住四件事：
 *  1. 演示课的 `source` 是 `demo`，与用户日程的 `custom` 不同；
 *  2. 演示课的 id 带 `demo:` 前缀，绝不会撞上用户数据的主键；
 *  3. 课名全部带「（演示）」后缀、地点都是虚构的；
 *  4. 生成是纯函数性质：同一输入两次调用结果完全相同（没有随机、没有时钟依赖）。
 */
class DemoTimetableTest {

    private val periods = listOf(
        period(1, 800, 850),
        period(2, 900, 950),
        period(3, 1010, 1100),
        period(4, 1110, 1200),
        period(5, 1400, 1450),
        period(6, 1500, 1550),
        period(7, 1610, 1700),
        period(8, 1710, 1800),
    )

    private fun period(index: Int, start: Int, end: Int) = Period(
        indexNo = index,
        nameZh = "第 $index 节",
        startTime = start,
        endTime = end,
        dayPart = "MORNING",
        segmentIndex = index,
    )

    @Test
    fun `演示课的来源与用户日程不同`() {
        val sessions = DemoTimetable.sessions(1, listOf(1), periods)
        assertTrue(sessions.isNotEmpty())
        assertTrue(sessions.all { it.source == DemoTimetable.SOURCE })
        assertNotEquals(ScheduleRepository.SOURCE_CUSTOM, DemoTimetable.SOURCE)
    }

    @Test
    fun `演示课的主键带 demo 前缀_不会与用户数据冲突`() {
        val sessions = DemoTimetable.sessions(7, listOf(1, 2), periods)
        assertTrue(sessions.all { it.id.startsWith("demo:7:") })
        // 学期 id 参与主键：切学期不会把两学期的演示课混在一起
        val other = DemoTimetable.sessions(8, listOf(1, 2), periods)
        assertTrue(sessions.map { it.id }.intersect(other.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `课名与地点都明确标注为演示_不冒充真实课程`() {
        val sessions = DemoTimetable.sessions(1, listOf(1), periods)
        sessions.forEach { s ->
            assertTrue("课名缺少演示标注：${s.courseName}", s.courseName.contains("（演示）"))
            assertTrue("地点不是虚构的：${s.roomText}", s.roomText.orEmpty().contains("演示"))
            assertEquals("演示课不应有真实日期", null, s.date)
        }
    }

    @Test
    fun `每条演示课都有作息时间`() {
        val sessions = DemoTimetable.sessions(1, listOf(1), periods)
        sessions.forEach { s ->
            assertTrue("缺少上课时间：${s.courseName}", !s.startTime.isNullOrBlank())
            assertTrue("缺少下课时间：${s.courseName}", !s.endTime.isNullOrBlank())
        }
    }

    @Test
    fun `作息缺失的节次会被跳过而不是生成幽灵课`() {
        // 只给前 2 节；模板里 3 节以后的课都该被跳过，而不是写成 00:00
        val short = listOf(period(1, 800, 850), period(2, 900, 950))
        val sessions = DemoTimetable.sessions(1, listOf(1), short)
        assertTrue(sessions.all { it.endUnit <= 2 })
        assertTrue(sessions.none { it.startTime == "00:00" })
    }

    @Test
    fun `同一输入生成结果完全一致`() {
        val a = DemoTimetable.sessions(1, listOf(1, 2, 3), periods)
        val b = DemoTimetable.sessions(1, listOf(1, 2, 3), periods)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a.map { it.courseName }, b.map { it.courseName })
        assertEquals(a.map { it.weekIndex }, b.map { it.weekIndex })
    }

    @Test
    fun `每个教学周都有课_不会出现空周`() {
        val weeks = listOf(1, 2, 3, 4, 5)
        val sessions = DemoTimetable.sessions(1, weeks, periods)
        val covered = sessions.map { it.weekIndex }.distinct().sorted()
        assertEquals(weeks, covered)
    }

    @Test
    fun `没有进度学期时不生成任何演示课`() {
        assertTrue(DemoTimetable.sessions(0, listOf(1), periods).isEmpty())
    }

    @Test
    fun `课程聚合只包含演示来源的课`() {
        val sessions = DemoTimetable.sessions(1, listOf(1), periods)
        val courses = DemoTimetable.courses(1, sessions)
        assertTrue(courses.isNotEmpty())
        assertTrue(courses.all { c -> c.sessions.all { it.source == DemoTimetable.SOURCE } })
        // 同一门课在模板里出现两次（周一 + 周三的高数），应聚合成一门
        val math = courses.first { it.nameZh.contains("高等数学") }
        assertEquals(2, math.sessions.size)
    }

    @Test
    fun `演示横幅文案明确说明不是真实课表`() {
        assertTrue(DemoTimetable.BANNER.contains("演示"))
        assertTrue(DemoTimetable.BANNER_DETAIL.contains("虚构"))
        assertFalse(DemoTimetable.BANNER.contains("官方"))
    }
}
