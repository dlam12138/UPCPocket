package com.upcpocket.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.MainActivity
import com.upcpocket.app.BuildConfig
import com.upcpocket.app.designsystem.WidgetAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class WidgetIntentsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `widget open target is a fixed allowlist with explicit component`() {
        val intent = WidgetIntents.activityIntent(context, WidgetLaunchTarget.CARD_BALANCE, 9)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("com.upcpocket.next", intent.component?.packageName)
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
    fun `todo list opens through its stable target`() {
        val intent = WidgetIntents.activityIntent(context, WidgetLaunchTarget.TODO, 12)
        assertEquals(WidgetLaunchTarget.TODO, WidgetLaunchTarget.fromIntent(intent))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
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
        assertEquals(BuildConfig.APPLICATION_ID, intent.component?.packageName)
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

        val glassPower = WidgetIntents.refreshIntent(
            context,
            WidgetKind.POWER,
            11,
            WidgetProviderVariant.POWER_GLASS,
        )
        assertEquals(PowerGlassWidgetProvider::class.java.name, glassPower.component?.className)
        assertEquals(power.action, glassPower.action)
        assertEquals(500_011, WidgetIntents.refreshRequestCode(WidgetKind.POWER, 11))
    }

    @Test
    fun `card provider action and unique work name remain compatible`() {
        assertEquals("widget-refresh-card", WidgetKind.CARD.uniqueWorkName)
        assertEquals(WidgetKind.CARD, WidgetKind.fromKey("card"))
        assertEquals(CardBalanceWidgetProvider::class.java, WidgetProviderVariant.standard(WidgetKind.CARD).providerClass)
        assertEquals(listOf(WidgetProviderVariant.CARD, WidgetProviderVariant.CARD_GLASS), WidgetProviderVariant.forKind(WidgetKind.CARD))
        assertEquals(
            "com.upcpocket.app.action.REFRESH_CARD_WIDGET",
            WidgetKind.CARD.refreshAction,
        )
        assertEquals(
            WidgetRefreshWorker::class.java.name,
            "com.upcpocket.app.widget.WidgetRefreshWorker",
        )
    }

    @Test
    fun `provider refresh action is only accepted for positive ids`() {
        assertFalse(WidgetIntents.isOwnedBy(context, WidgetKind.CARD, 0))
        assertFalse(WidgetIntents.isOwnedBy(context, WidgetKind.CARD, -1))
    }

    @Test
    fun `provider ownership stays specific while same-kind instances are shared`() {
        val manager = AppWidgetManager.getInstance(context)
        val shadow = Shadows.shadowOf(manager)
        val standard = ComponentName(context, TodoWidgetProvider::class.java)
        val glass = ComponentName(context, TodoGlassWidgetProvider::class.java)
        shadow.addBoundWidget(31, AppWidgetProviderInfo().apply { provider = standard })
        shadow.addBoundWidget(32, AppWidgetProviderInfo().apply { provider = glass })

        assertTrue(WidgetIntents.isOwnedBy(context, WidgetKind.TODO, 31))
        assertTrue(WidgetIntents.isOwnedBy(context, WidgetKind.TODO, 32))
        assertTrue(WidgetIntents.isOwnedBy(context, WidgetProviderVariant.TODO, 31))
        assertFalse(WidgetIntents.isOwnedBy(context, WidgetProviderVariant.TODO_GLASS, 31))
        assertTrue(WidgetIntents.isOwnedBy(context, WidgetProviderVariant.TODO_GLASS, 32))
        assertTrue(WidgetAutoRefresh.hasWidgets(context, WidgetKind.TODO))
    }

    @Test
    fun `glass provider appearance overrides the saved global widget appearance`() {
        assertEquals(
            WidgetAppearance.TRANSLUCENT,
            WidgetProviderVariant.CARD.resolveAppearance(WidgetAppearance.TRANSLUCENT),
        )
        assertEquals(
            WidgetAppearance.GLASS,
            WidgetProviderVariant.CARD.resolveAppearance(WidgetAppearance.GLASS),
        )
        assertEquals(
            WidgetAppearance.FROSTED_GLASS,
            WidgetProviderVariant.CARD_GLASS.resolveAppearance(WidgetAppearance.TRANSLUCENT),
        )
    }

    @Test
    fun `independent glass providers always use the frosted surface`() {
        val glassVariants = listOf(
            WidgetProviderVariant.CARD_GLASS,
            WidgetProviderVariant.POWER_GLASS,
            WidgetProviderVariant.TIMETABLE_GLASS,
            WidgetProviderVariant.TODO_GLASS,
        )

        glassVariants.forEach { variant ->
            assertEquals(WidgetAppearance.FROSTED_GLASS, variant.fixedAppearance)
            assertEquals(
                WidgetAppearance.FROSTED_GLASS,
                variant.resolveAppearance(WidgetAppearance.SOLID),
            )
            assertEquals(
                WidgetAppearance.FROSTED_GLASS,
                variant.resolveAppearance(WidgetAppearance.TRANSLUCENT),
            )
            assertEquals(
                WidgetAppearance.FROSTED_GLASS,
                variant.resolveAppearance(WidgetAppearance.GLASS),
            )
        }
        assertEquals(
            listOf(WidgetProviderVariant.TODO, WidgetProviderVariant.TODO_GLASS),
            WidgetProviderVariant.forKind(WidgetKind.TODO),
        )
    }
}
