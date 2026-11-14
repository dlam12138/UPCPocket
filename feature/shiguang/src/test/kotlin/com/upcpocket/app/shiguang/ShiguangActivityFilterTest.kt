package com.upcpocket.app.shiguang

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ShiguangActivityFilterTest {
    private val now = Instant.parse("2026-11-13T00:00:00Z") // 08:00 in Shanghai

    @Test fun onlyCompleteEndedEventIntervalsAreHidden() {
        val items = listOf(
            activity("ended", "2026-11-12 14:00–16:00"),
            activity("sameDayEnd", "2026/11/13 06:00 至 07:59"),
            activity("atBoundary", "2026-11-13 07:00 至 08:00"),
            activity("future", "2026-11-13 08:00 - 10:00"),
            activity("singleStart", "2026-11-12 14:00"),
            activity("dateOnly", "2026-11-12"),
            activity("invalid", "2026-02-30 14:00 至 16:00"),
            activity("invalidEndDate", "2026-11-12 14:00 至 2026-02-30 16:00"),
            activity("nonLeapDay", "2025-02-29 14:00 至 16:00"),
            activity("nonLeapEndDate", "2026-11-12 14:00 至 2025-02-29 16:00"),
            activity("endBeforeStart", "2026-11-12 14:00 至 13:00"),
            activity("crossMidnightClockOnly", "2026-11-12 22:00 至 01:00"),
            activity("explicitCrossDay", "2026-11-11 23:00 至 2026-11-12 01:00"),
            activity("placeholder", "时间待定"),
            ShiguangActivity("signupOnly", ShiguangKind.ACTIVITY, "signup", mapOf("报名截止时间" to "2026-11-12 12:00")),
            ShiguangActivity("noTime", ShiguangKind.ACTIVITY, "missing"),
        )

        assertEquals(
            listOf("atBoundary", "future", "singleStart", "dateOnly", "invalid", "invalidEndDate", "nonLeapDay",
                "nonLeapEndDate", "endBeforeStart", "crossMidnightClockOnly", "placeholder", "signupOnly", "noTime"),
            ShiguangPage(1, 1, items).visibleActivities(ShiguangActivityFilter.ALL, hideEnded = true, now).map { it.id },
        )
    }

    @Test fun kindAndEndedFiltersComposeAndKeepUnknownTimes() {
        val items = listOf(
            activity("oldCampus", "2026-11-12 14:00 至 16:00"),
            activity("unknownCampus", "2026-11-12 14:00"),
            ShiguangActivity("practice", ShiguangKind.SOCIAL_PRACTICE, "practice", mapOf("活动时间" to "待通知")),
            ShiguangActivity("oldPractice", ShiguangKind.SOCIAL_PRACTICE, "old practice", mapOf("活动时间" to "2026年11月12日 14:00 到 16:00")),
        )
        val page = ShiguangPage(4, 4, items)

        assertEquals(listOf("unknownCampus"), page.visibleActivities(ShiguangActivityFilter.CAMPUS, true, now).map { it.id })
        assertEquals(listOf("practice"), page.visibleActivities(ShiguangActivityFilter.SOCIAL_PRACTICE, true, now).map { it.id })
        assertEquals(listOf("oldCampus", "unknownCampus"), page.visibleActivities(ShiguangActivityFilter.CAMPUS, false, now).map { it.id })
    }

    private fun activity(id: String, eventTime: String) = ShiguangActivity(
        id = id,
        kind = ShiguangKind.ACTIVITY,
        title = id,
        fields = mapOf("活动时间" to eventTime),
    )
}
