package com.upcpocket.app.timetable

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableColorStoreTest {

    private val directory: File = Files.createTempDirectory("timetable-colors").toFile()
    private val account = "account-1234"
    private val term = "2026-2027|1"

    private val twelve = listOf(
        "高等数学 A",
        "大学物理",
        "程序设计基础",
        "学术英语",
        "体育",
        "数据结构",
        "概率论与数理统计",
        "大学英语视听说",
        "金工实习",
        "创新创业基础",
        "电路分析",
        "材料科学导论",
    )

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun `twelve courses receive twelve distinct slots`() {
        val slots = TimetableColorStore(directory).assign(account, term, twelve)
        assertEquals(twelve.size, slots.size)
        assertEquals(twelve.size, slots.values.toSet().size)
        assertTrue(slots.values.all { it in 0 until COURSE_COLOR_CAPACITY })
        val containers = slots.values.map { courseToneColors(it, false).container }.toSet()
        assertEquals(twelve.size, containers.size)
    }

    @Test
    fun `existing slots survive reorder cold start and a newly added course`() {
        val first = TimetableColorStore(directory).assign(account, term, twelve)

        val reordered = TimetableColorStore(directory)
            .assign(account, term, twelve.reversed())
        assertEquals(first, reordered)

        val withNew = TimetableColorStore(directory)
            .assign(account, term, twelve.reversed() + "新课程")
        twelve.forEach { name ->
            assertEquals("$name moved after adding a course", first[name], withNew[name])
        }
        assertFalse(withNew["新课程"] in first.values)

        val coldStart = TimetableColorStore(directory)
            .assign(account, term, listOf("新课程") + twelve)
        assertEquals(withNew, coldStart)
    }

    @Test
    fun `a different account does not inherit the previous account mapping`() {
        val store = TimetableColorStore(directory)
        val first = store.assign(account, term, twelve)
        val second = store.assign("account-5678", term, listOf("仅一门课"))
        assertEquals(setOf(0), second.values.toSet())
        assertEquals(twelve.size, first.values.toSet().size)

        val cleared = store.clear()
        assertTrue(cleared)
        assertFalse(File(directory, TimetableStorage.COLOR_FILE).exists())
        val fresh = TimetableColorStore(directory).assign(account, term, twelve)
        assertEquals(twelve.size, fresh.values.toSet().size)
    }

    @Test
    fun `capacity is honest and overflow reuses slots after twenty courses`() {
        val names = (1..25).map { "课程 $it" }
        val slots = TimetableColorStore(directory).assign(account, term, names)
        assertEquals(25, slots.size)
        assertEquals(COURSE_COLOR_CAPACITY, slots.values.toSet().size)
    }
}
