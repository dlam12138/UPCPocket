package com.upcpocket.app.widget

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.power.PowerSnapshot
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
    fun `power keeps building and room as separately rendered fields with kWh`() {
        val root = render(
            snapshot = PowerSnapshot("01号楼(1)", "1101", "17.34", now),
        )
        val building = root.findViewById<TextView>(R.id.widget_power_building)
        val room = root.findViewById<TextView>(R.id.widget_power_room)
        assertEquals("01号楼(1)", building.text.toString())
        assertEquals("1101", room.text.toString())
        assertEquals(1, building.maxLines)
        assertEquals(1, room.maxLines)

        assertEquals("17.34", text(root, R.id.widget_power_value))
        assertEquals("kWh", text(root, R.id.widget_power_unit))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_power_unit).visibility)
        val meta = text(root, R.id.widget_power_updated)
        assertTrue(meta.endsWith("更新"))
        assertFalse(meta.contains("\n"))
    }

    @Test
    fun `power without snapshot asks for an app query and shows no zero`() {
        val root = render()
        assertTrue(text(root, R.id.widget_power_building).contains("未设置房间"))
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_power_room).visibility)
        assertEquals("尚未查询", text(root, R.id.widget_power_value))
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_power_unit).visibility)
        assertEquals("先在应用查询一次", text(root, R.id.widget_power_updated))
        assertFalse(text(root, R.id.widget_power_value).contains("0.00"))
    }

    @Test
    fun `power long value steps down and keeps a single line`() {
        val renderer = PowerWidgetRenderer(context)
        assertEquals(36f, renderer.numberTextSize("6.99"))
        assertEquals(27f, renderer.numberTextSize("1234.56"))
        assertEquals(23f, renderer.numberTextSize("123456.78"))
        assertEquals(23.4f, renderer.numberTextSize("17.34", fontScale = 1.5f))
        assertTrue(renderer.unitTextSize(23f) < 23f)
    }

    @Test
    fun `160dp at 15x keeps power title value and kWh unclipped`() {
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            fontScale = 1.5f
        }
        val ctx = context.createConfigurationContext(config)
        val views = PowerWidgetRenderer(ctx).views(
            snapshot = PowerSnapshot("01号楼(1)", "1101", "17.34", now),
            refreshing = false,
            failed = false,
            openApp = { pending(11) },
            refresh = { pending(12) },
        )
        val root = views.apply(ctx, FrameLayout(ctx))
        val size = (160 * ctx.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, size, size)
        val title = root.findViewById<TextView>(R.id.widget_power_title)
        val value = root.findViewById<TextView>(R.id.widget_power_value)
        val unit = root.findViewById<TextView>(R.id.widget_power_unit)
        assertTrue(title.height >= title.layout.height)
        assertEquals(0, title.layout.getEllipsisCount(0))
        assertEquals(0, value.layout.getEllipsisCount(0))
        assertEquals("kWh", unit.text.toString())
    }
}
