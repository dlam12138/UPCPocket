package com.upcpocket.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class WidgetIntentsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `widget open target is a fixed allowlist with explicit component`() {
        val intent = WidgetIntents.activityIntent(context, WidgetLaunchTarget.CARD_BALANCE, 9)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(WidgetIntents.ACTION_OPEN_FROM_WIDGET, intent.action)
        assertEquals("CARD_BALANCE", intent.getStringExtra(WidgetIntents.EXTRA_WIDGET_TARGET))
        assertEquals(WidgetLaunchTarget.CARD_BALANCE, WidgetLaunchTarget.fromIntent(intent))

        val forged = Intent(intent).putExtra(WidgetIntents.EXTRA_WIDGET_TARGET, "HOME")
        assertNull(WidgetLaunchTarget.fromIntent(forged))

        val missingAction = Intent(intent).setAction(null)
        assertNull(WidgetLaunchTarget.fromIntent(missingAction))

        val withUrl = Intent(intent).setData(Uri.parse("https://example.invalid/steal"))
        assertNull(WidgetLaunchTarget.fromIntent(withUrl))
    }

    @Test
    fun `card and power pending intents use separate request code ranges`() {
        assertNotEquals(
            WidgetIntents.activityRequestCode(WidgetLaunchTarget.CARD_BALANCE, 7),
            WidgetIntents.activityRequestCode(WidgetLaunchTarget.POWER_TANGDAOWAN, 7),
        )
        assertNotEquals(
            WidgetIntents.refreshRequestCode(WidgetKind.CARD, 7),
            WidgetIntents.refreshRequestCode(WidgetKind.POWER, 7),
        )
        assertNotEquals(
            WidgetIntents.activityRequestCode(WidgetLaunchTarget.CARD_BALANCE, 7),
            WidgetIntents.refreshRequestCode(WidgetKind.CARD, 7),
        )
    }

    @Test
    fun `refresh intent is explicit and carries only widget id`() {
        val intent = WidgetIntents.refreshIntent(context, WidgetKind.CARD, 11)
        assertEquals(CardBalanceWidgetProvider::class.java.name, intent.component?.className)
        assertEquals(WidgetKind.CARD.refreshAction, intent.action)
        assertEquals(11, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertFalse(intent.hasExtra("studentId"))
        assertFalse(intent.hasExtra("building"))
        assertFalse(intent.hasExtra("room"))
        assertNull(intent.data)

        val power = WidgetIntents.refreshIntent(context, WidgetKind.POWER, 11)
        assertEquals(PowerWidgetProvider::class.java.name, power.component?.className)
        assertNotEquals(intent.component, power.component)
        assertNotEquals(intent.action, power.action)
    }

    @Test
    fun `provider refresh action is only accepted for positive ids`() {
        assertFalse(WidgetIntents.isOwnedBy(context, WidgetKind.CARD, 0))
        assertFalse(WidgetIntents.isOwnedBy(context, WidgetKind.CARD, -1))
    }
}
