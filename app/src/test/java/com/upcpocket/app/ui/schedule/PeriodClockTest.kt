package com.upcpocket.app.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 节次时间解析的测试。
 *
 * 这几行纯函数决定"课到底几点上"：解析错了，课表、今天页时间轴、桌面组件的
 * "下一节课"会整体偏一两个小时，而且用户很难从界面反推出是哪一步错的。
 *
 * 数据层的口径是 `HHmm` 四位整数（800 = 08:00），和上游教务接口一致；
 * 界面上允许 `08:00`、`8:00`、`0800`、`800` 这几种写法，都要归一到同一个值。
 */
class PeriodClockTest {

    @Test
    fun `四位时间格式化为冒号形式`() {
        assertEquals("08:00", clock(800))
        assertEquals("13:45", clock(1345))
        assertEquals("00:05", clock(5))
        assertEquals("23:59", clock(2359))
    }

    @Test
    fun `冒号写法可以解析`() {
        assertEquals(800, parseClock("08:00"))
        assertEquals(1345, parseClock("13:45"))
        assertEquals(5, parseClock("00:05"))
    }

    @Test
    fun `不带冒号的四位与三位写法都能解析`() {
        assertEquals(800, parseClock("0800"))
        assertEquals(800, parseClock("800"))
        assertEquals(1345, parseClock("1345"))
    }

    @Test
    fun `前后空白被忽略`() {
        assertEquals(800, parseClock("  08:00 "))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(parseClock(""))
        assertNull(parseClock("24:00"))
        assertNull(parseClock("08:60"))
        assertNull(parseClock("abc"))
        assertNull(parseClock("8:0:0"))
        assertNull(parseClock("12345"))
    }

    @Test
    fun `往返转换保持一致`() {
        listOf(0, 5, 800, 1159, 1200, 1345, 2359).forEach { v ->
            assertEquals(v, parseClock(clock(v)))
        }
    }

    @Test
    fun `越界值在格式化时被钳制而不是崩溃`() {
        assertEquals("23:59", clock(9999))
        assertEquals("00:00", clock(-1))
    }
}
