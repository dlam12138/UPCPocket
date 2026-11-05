package com.upcpocket.app.ui.library

import com.upcpocket.app.data.chaoxing.ChaoxingSeatPlace
import com.upcpocket.app.data.chaoxing.ChaoxingSeatRoomDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaoxingSeatTimeSelectionTest {

    private val detail = ChaoxingSeatRoomDetail(
        roomId = 12814,
        name = "求是图书馆-二楼-阶梯学习区",
        capacity = 30,
        timeUnit = 30,
        startMinute = 8 * 60,
        endMinute = 22 * 60,
        places = listOf(ChaoxingSeatPlace("001", 0f, 0f, 30f, 30f, 0f)),
    )

    @Test
    fun `start and end options honour open hours and 30 minute step`() {
        val starts = chaoxingStartOptions(detail)
        val ends = chaoxingEndOptions(detail, 8 * 60)

        assertTrue(starts.first() == 8 * 60)
        assertTrue(starts.last() == 21 * 60 + 30)
        assertTrue(ends.first() == 8 * 60 + 30)
        assertTrue(ends.last() == 22 * 60)
        assertTrue(ends.all { (it - 8 * 60) % 30 == 0 })
        assertFalse(ends.any { it > 22 * 60 })
    }

    @Test
    fun `viewing supports 30 60 90 120 minute durations without a fixed hour`() {
        val ends = chaoxingEndOptions(detail, 8 * 60)
        val durations = ends.take(4).map { it - 8 * 60 }

        assertEquals(listOf(30, 60, 90, 120), durations)
        assertEquals("30 分钟", formatDuration(30))
        assertEquals("1 小时", formatDuration(60))
        assertEquals("1 小时 30 分钟", formatDuration(90))
        assertEquals("2 小时", formatDuration(120))
    }

    @Test
    fun `end options stop at closing time and start must be before close`() {
        assertTrue(chaoxingEndOptions(detail, 21 * 60 + 30) == listOf(22 * 60))
        assertTrue(chaoxingEndOptions(detail, 22 * 60).isEmpty())
        assertTrue(chaoxingStartOptions(detail.copy(timeUnit = 0)).isEmpty())
    }
}
