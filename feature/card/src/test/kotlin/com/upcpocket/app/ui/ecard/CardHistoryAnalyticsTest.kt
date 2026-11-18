package com.upcpocket.app.ui.ecard

import com.upcpocket.app.data.cardhistory.CardHistoryRow
import org.junit.Assert.assertEquals
import org.junit.Test

class CardHistoryAnalyticsTest {

    @Test
    fun `aggregates income expense categories and daily trend from rows`() {
        val rows = listOf(
            CardHistoryRow("1", "玉兰苑一楼餐厅", "2026-11-10 12:10:00", -1250),
            CardHistoryRow("2", "校园超市", "2026-11-10 19:00:00", -800),
            CardHistoryRow("3", "微信充值", "2026-11-11 08:00:00", 10000),
            CardHistoryRow("4", "学生浴室水控", "2026-11-12 21:15:00", -350),
            CardHistoryRow("5", "荟萃餐厅", "2026-11-12 12:00:00", -1500),
        )

        val analytics = CardHistoryAnalytics.fromRows(rows)

        assertEquals(10000L, analytics.loadedIncomeFen)
        assertEquals(3900L, analytics.loadedExpenseFen)
        assertEquals("餐饮美食", analytics.categories.first().label)
        assertEquals(2750L, analytics.categories.first().expenseFen)
        assertEquals(2, analytics.categories.first().count)
        assertEquals(listOf("11-10", "11-12"), analytics.dailyExpenses.map { it.shortLabel })
        assertEquals(listOf(2050L, 1850L), analytics.dailyExpenses.map { it.expenseFen })
    }
}
