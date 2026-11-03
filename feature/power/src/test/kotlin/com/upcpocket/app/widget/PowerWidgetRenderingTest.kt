package com.upcpocket.app.widget

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.power.PowerSnapshot
import com.upcpocket.app.designsystem.WidgetRenderSupport
import com.upcpocket.app.feature.power.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class PowerWidgetRenderingTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_700_000_000_000L

    private fun pending(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, Application::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun render(
        snapshot: PowerSnapshot? = null,
        refreshing: Boolean = false,
        failed: Boolean = false,
    ): View = PowerWidgetRenderer(context).views(
        snapshot = snapshot,
        refreshing = refreshing,
        failed = failed,
        openApp = { pending(11) },
        refresh = { pending(12) },
    ).apply(context, FrameLayout(context))

    private fun text(root: View, id: Int): String =
        root.findViewById<TextView>(id).text.toString()

    @Test
    fun `power keeps building and room as separately rendered fields`() {
        val root = render(
            snapshot = PowerSnapshot("01号楼(1)", "1101", "17.34", now),
        )
        val building = root.findViewById<TextView>(R.id.widget_power_building)
        val room = root.findViewById<TextView>(R.id.widget_power_room)
        assertEquals("01号楼(1)", building.text.toString())
        assertEquals("1101", room.text.toString())
        assertEquals(1, building.maxLines)
        assertEquals(1, room.maxLines)

        assertTrue(text(root, R.id.widget_power_value).contains("17.34 度"))
        assertFalse(text(root, R.id.widget_power_value).contains("元"))
        val meta = text(root, R.id.widget_power_updated)
        assertEquals(WidgetRenderSupport.queryTime(now), meta)
        assertFalse(meta.contains("刚刚"))
        assertFalse(meta.contains("分钟前"))
    }

    @Test
    fun `power without snapshot asks for an app query and shows no zero`() {
        val root = render()
        assertTrue(text(root, R.id.widget_power_building).contains("未设置楼号"))
        assertTrue(text(root, R.id.widget_power_room).contains("未设置房间"))
        assertTrue(text(root, R.id.widget_power_value).contains("尚未查询"))
        assertFalse(text(root, R.id.widget_power_value).contains("0.00"))
        assertTrue(text(root, R.id.widget_power_updated).contains("先在应用查询一次"))
        assertTrue(text(root, R.id.widget_power_updated).contains("尚无本地记录"))
    }
}
