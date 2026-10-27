package com.upcpocket.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class CsvScheduleImportTest {
    private val header = "课程名称,星期,开始节次,结束节次,周次,教师,教室\r\n"
    private fun parse(text: String) = CsvScheduleImport.parse(text.toByteArray(), 20, (1..13).toSet())

    @Test
    fun bomQuotedCommaNewlineAndEscapedQuote() {
        val p = parse("\uFEFF" + header + "\"课程,\"\"A\"\"\n实验\",星期一,1,2,\"1-8(单),10\",老师,教室")
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertEquals("课程,\"A\"\n实验", p.rows.single().name)
        assertEquals(listOf(1, 3, 5, 7, 10), p.rows.single().weeks)
    }

    @Test
    fun gb18030AndAliases() {
        val p = CsvScheduleImport.parse(
            "课程名,周几,节次,上课周次,周类型\n数学,周日,3-4,2-8,双周".toByteArray(Charset.forName("GB18030")),
            20,
            (1..13).toSet(),
        )
        assertEquals("GB18030", p.encoding)
        assertEquals(listOf(2, 4, 6, 8), p.rows.single().weeks)
        assertEquals(7, p.rows.single().weekday)
    }

    @Test
    fun latePeriods11To13AreAcceptedWhenConfigured() {
        val p = parse(header + "晚课,1,11,13,1-2,,")
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertEquals(11, p.rows.single().start)
        assertEquals(13, p.rows.single().end)
    }

    @Test
    fun latePeriodsAreStillExplicitlyRejectedWhenNotConfigured() {
        val p = CsvScheduleImport.parse(header.toByteArray() + "数学,1,11,13,1-2,,".toByteArray(), 20, (1..10).toSet())
        assertTrue(p.rows.isEmpty())
        assertTrue(p.errors.single().contains("未配置第 11–13 节"))
    }

    @Test
    fun multipleSlotsInOneCellBecomeMultipleRows() {
        val p = CsvScheduleImport.parse(
            "课程名称,星期,节次,周次,教师,教室\n高数,星期二,\"1-2,3-4；5-6\",1-16,张老师,A101"
                .toByteArray(),
            20,
            (1..13).toSet(),
        )
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertEquals(listOf(1 to 2, 3 to 4, 5 to 6), p.rows.map { it.start to it.end })
        assertTrue(p.rows.all { it.name == "高数" && it.weekday == 2 && it.weeks == (1..16).toList() })
    }

    @Test
    fun perSegmentParityIsAppliedWithoutConflicting() {
        val p = parse(header + "物理,3,1,2,\"1-8单,10-16双\",,")
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertEquals(listOf(1, 3, 5, 7, 10, 12, 14, 16), p.rows.single().weeks)
    }

    @Test
    fun combinedTimeColumnSupportsMultipleBlocksAndInheritedWeekday() {
        val p = CsvScheduleImport.parse(
            "课程名称,上课时间,周次,教师,教室\n电路,星期一第1-2节,1-8,李老师,B202\n实验,星期三 5-6节,3-4,,实验室"
                .toByteArray(),
            20,
            (1..13).toSet(),
        )
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertEquals(listOf(1 to 2, 5 to 6), p.rows.map { it.start to it.end })
        assertEquals(listOf(1, 3), p.rows.map { it.weekday })
    }

    @Test
    fun malformedEncodingFailsInsteadOfReplacingCharacters() {
        assertTrue(runCatching {
            CsvScheduleImport.parse(byteArrayOf(0x81.toByte()), 20, (1..13).toSet())
        }.isFailure)
    }

    @Test
    fun missingColumnFails() {
        assertTrue(runCatching { parse("课程名称,星期\n数学,1") }.isFailure)
    }

    @Test
    fun badQuoteFails() {
        assertTrue(runCatching { parse(header + "\"未闭合") }.isFailure)
    }

    @Test
    fun duplicateHeaderFails() {
        assertTrue(runCatching { parse("课程名称,课程名,星期,节次,周次") }.isFailure)
    }

    @Test
    fun invalidRowsAreNotSilentlyGuessed() {
        val p = parse(header + "数学,8,1,2,1-2,,\n数学,1,9,14,1-2,,\n数学,1,1,2,1-21,,\n数学,1,1,2,1-2,,多余,列")
        assertEquals(4, p.errors.size)
        assertTrue(p.rows.isEmpty())
    }

    @Test
    fun blankWeeksAreRejectedInsteadOfAssumingWholeSemester() {
        val p = parse(header + "数学,1,1,2,,,")
        assertTrue(p.rows.isEmpty())
        assertTrue(p.errors.single().contains("空白不代表整学期"))
    }

    @Test
    fun stableIdentityIncludesScheduleAndRoom() {
        val a = CsvScheduleImport.Row("数学", 1, 1, 2, listOf(2, 1), "老师", "教室")
        assertEquals(a.spec(1).code, a.copy(weeks = listOf(1, 2)).spec(1).code)
        assertNotEquals(a.spec(1).code, a.copy(room = "另一教室").spec(1).code)
    }

    @Test
    fun emptyAndOversizedFilesFail() {
        assertTrue(runCatching { parse("") }.isFailure)
        assertTrue(runCatching {
            CsvScheduleImport.parse(ByteArray(CsvScheduleImport.MAX_BYTES + 1), 20, (1..13).toSet())
        }.isFailure)
    }
}
