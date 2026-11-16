package com.upcpocket.app.widget

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTimeReceiverTest {
    @Test
    fun `only protected clock boot and package events are accepted`() {
        assertTrue(WidgetTimeReceiver.accepts(Intent.ACTION_TIME_CHANGED))
        assertTrue(WidgetTimeReceiver.accepts(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(WidgetTimeReceiver.accepts(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(WidgetTimeReceiver.accepts(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(WidgetTimeReceiver.accepts(null))
        assertFalse(WidgetTimeReceiver.accepts(WidgetKind.CARD.refreshAction))
        assertFalse(WidgetTimeReceiver.accepts(Intent.ACTION_DATE_CHANGED))
    }
}
