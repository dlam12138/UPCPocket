package com.upcpocket.app.ui.home

import com.upcpocket.app.data.prefs.HomeAnniversaryMode
import com.upcpocket.app.data.prefs.HomeAnniversarySettings
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAnniversaryTest {

    private val start = LocalDate.of(2026, 11, 24)

    private fun settings(
        date: LocalDate? = start,
        mode: HomeAnniversaryMode = HomeAnniversaryMode.COUNT_UP,
        visible: Boolean = true,
        title: String = "入学纪念日",
    ) = HomeAnniversarySettings(
        title = title,
        date = date,
        mode = mode,
        visible = visible,
    )

    @Test
    fun `count up covers before start start day and later days`() {
        assertEquals(
            "还有 7 天开始",
            homeAnniversaryLine(settings(), start.minusDays(7))?.countText,
        )
        assertEquals(
            "还有 1 天开始",
            homeAnniversaryLine(settings(), start.minusDays(1))?.countText,
        )
        assertEquals(
            "第 1 天",
            homeAnniversaryLine(settings(), start)?.countText,
        )
        assertEquals(
            "第 2 天",
            homeAnniversaryLine(settings(), start.plusDays(1))?.countText,
        )
        assertEquals(
            "第 32 天",
            homeAnniversaryLine(settings(), start.plusDays(31))?.countText,
        )
    }

    @Test
    fun `count down covers future target day and overdue days`() {
        val countDown = settings(mode = HomeAnniversaryMode.COUNT_DOWN)
        assertEquals(
            "还有 7 天",
            homeAnniversaryLine(countDown, start.minusDays(7))?.countText,
        )
        assertEquals(
            "还有 1 天",
            homeAnniversaryLine(countDown, start.minusDays(1))?.countText,
        )
        assertEquals(
            "就是今天",
            homeAnniversaryLine(countDown, start)?.countText,
        )
        assertEquals(
            "已过 1 天",
            homeAnniversaryLine(countDown, start.plusDays(1))?.countText,
        )
        assertEquals(
            "已过 10 天",
            homeAnniversaryLine(countDown, start.plusDays(10))?.countText,
        )
    }

    @Test
    fun `hidden blank or unavailable values produce no home line`() {
        assertNull(homeAnniversaryLine(settings(visible = false), start))
        assertNull(homeAnniversaryLine(settings(title = "   "), start))
        assertNull(homeAnniversaryLine(settings(date = null), start))
    }

    @Test
    fun `date picker millis use utc calendar days independent of device zone`() {
        val millis = homeAnniversaryPickerMillis(start)
        assertEquals(Instant.parse("2026-11-24T00:00:00Z").toEpochMilli(), millis)
        assertEquals(start, homeAnniversaryPickerDate(requireNotNull(millis)))
        // 上海已经是 25 日，但 DatePicker 的 UTC 日历日期仍是 24 日。
        assertEquals(
            start,
            homeAnniversaryPickerDate(Instant.parse("2026-11-24T23:30:00Z").toEpochMilli()),
        )
        assertNull(homeAnniversaryPickerMillis(null))
    }
}
