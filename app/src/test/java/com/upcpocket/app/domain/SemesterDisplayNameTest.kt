package com.upcpocket.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 学期显示名的测试。
 *
 * 这条看起来纯展示，但 M1 真机首启就踩到了：本地学期的 `nameZh` 已经是
 * "2026-2027 第一学期"，上游的拼接逻辑又把 `schoolYear` 接在前面，
 * 界面上变成 "2026-2027 2026-2027 第一学期"。
 *
 * 所以这里把三种输入形态都钉住：
 *  1. 本地生成的可读名 → 原样返回；
 *  2. 上游教务的紧凑写法 `2026-2027-1` → 整理成可读名；
 *  3. 其它随意名称 → 拼上年号（不重复）。
 */
class SemesterDisplayNameTest {

    private fun semester(nameZh: String, schoolYear: String) = Semester(
        id = 1,
        nameZh = nameZh,
        schoolYear = schoolYear,
        startDate = LocalDate.of(2026, 9, 1),
        endDate = LocalDate.of(2027, 1, 10),
        weekStartOnSunday = false,
        weekIndices = (1..18).toList(),
        isCurrent = true,
    )

    @Test
    fun `本地可读名原样显示_不重复拼学年`() {
        val s = semester("2026-2027 第一学期", "2026-2027")
        assertEquals("2026-2027 第一学期", s.displayName)
    }

    @Test
    fun `教务紧凑写法整理成可读名`() {
        assertEquals(
            "2026-2027 第一学期",
            semester("2026-2027-1", "2026-2027").displayName,
        )
        assertEquals(
            "2026-2027 第二学期",
            semester("2026-2027-2", "2026-2027").displayName,
        )
    }

    @Test
    fun `第三学期也能识别`() {
        assertEquals(
            "2026-2027 第三学期",
            semester("2026-2027-3", "2026-2027").displayName,
        )
    }

    @Test
    fun `名称已含学年时不重复`() {
        val s = semester("2026-2027 秋季", "2026-2027")
        assertEquals("2026-2027 秋季", s.displayName)
    }

    @Test
    fun `名称不含学年时拼上`() {
        val s = semester("小学期", "2026-2027")
        assertEquals("2026-2027 小学期", s.displayName)
    }

    @Test
    fun `名称为空时只显示学年`() {
        val s = semester("", "2026-2027")
        assertEquals("2026-2027", s.displayName)
    }
}
