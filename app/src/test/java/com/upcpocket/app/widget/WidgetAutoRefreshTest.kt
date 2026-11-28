package com.upcpocket.app.widget

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAutoRefreshTest {
    @Test
    fun `card cadence is stable and spread within configured bounds`() {
        val installationIds = (1..32).map { index ->
            UUID.nameUUIDFromBytes("installation-$index".toByteArray()).toString()
        }
        val cadences = installationIds.map { WidgetAutoRefresh.cardCadence(it) }

        assertTrue(cadences.all {
            it.intervalMinutes in WidgetAutoRefresh.CARD_MIN_INTERVAL_MINUTES..
                WidgetAutoRefresh.CARD_MAX_INTERVAL_MINUTES &&
                it.initialDelayMinutes in 1L..it.intervalMinutes
        })
        installationIds.zip(cadences).forEach { (installationId, cadence) ->
            assertEquals(cadence, WidgetAutoRefresh.cardCadence(installationId))
        }
        assertTrue(cadences.map { it.intervalMinutes }.toSet().size > 1)
        assertTrue(cadences.map { it.initialDelayMinutes }.toSet().size > 1)
    }
}
