package com.upcpocket.app.widget

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.R
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.data.power.PowerSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class WidgetRenderingTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_700_000_000_000L

    private fun render(
        kind: WidgetKind,
        card: DirectCardSnapshot? = null,
        power: PowerSnapshot? = null,
        refreshing: Boolean = false,
        failed: Boolean = false,
    ): View = WidgetRenderer.views(
        context = context,
        kind = kind,
        widgetId = 7,
        card = card,
        power = power,
        refreshing = refreshing,
        failed = failed,
    ).apply(context, FrameLayout(context))

    private fun text(root: View, id: Int): String =
        root.findViewById<TextView>(id).text.toString()

    @Test
    fun `card shows masked id balance and separate transition`() {
        val id = "2099000123"
        val root = render(
            kind = WidgetKind.CARD,
            card = DirectCardSnapshot(id, 12345, 678, now),
        )
        val account = text(root, R.id.widget_card_account)
        assertTrue(account.contains("20"))
        assertTrue(account.contains("••••"))
        assertFalse(account.contains(id))
        assertFalse(account.contains("2099000123"))

        assertTrue(text(root, R.id.widget_card_balance).contains("123.45 元"))
        val transition = text(root, R.id.widget_card_transition)
        assertTrue(transition.contains("6.78"))
        assertTrue(transition.contains("不计入"))
        assertTrue(text(root, R.id.widget_card_updated).startsWith("更新于"))

        val refresh = root.findViewById<View>(R.id.widget_card_refresh)
        assertTrue(refresh.contentDescription.toString().contains("刷新校园卡余额"))
        assertTrue(refresh.layoutParams.width >= 48 * context.resources.displayMetrics.density)
        assertTrue(refresh.layoutParams.height >= 48 * context.resources.displayMetrics.density)
    }

    @Test
    fun `card without snapshot guides the user and never fabricates zero`() {
        val root = render(kind = WidgetKind.CARD)
        assertTrue(text(root, R.id.widget_card_account).contains("未设置"))
        assertTrue(text(root, R.id.widget_card_balance).contains("尚未查询"))
        assertFalse(text(root, R.id.widget_card_balance).contains("0.00"))
        assertTrue(text(root, R.id.widget_card_status).contains("先在应用查询一次"))
        assertTrue(text(root, R.id.widget_card_updated).contains("尚无本地记录"))
        assertTrue(
            root.findViewById<View>(R.id.widget_card_refresh)
                .contentDescription.toString().contains("打开应用"),
        )
    }

    @Test
    fun `power shows last successful room and uses kwh unit`() {
        val root = render(
            kind = WidgetKind.POWER,
            power = PowerSnapshot("01号楼(1)", "1101", "17.34", now),
        )
        assertTrue(text(root, R.id.widget_power_room).contains("01号楼(1)"))
        assertTrue(text(root, R.id.widget_power_room).contains("1101"))
        assertTrue(text(root, R.id.widget_power_value).contains("17.34 度"))
        assertFalse(text(root, R.id.widget_power_value).contains("元"))
        assertTrue(text(root, R.id.widget_power_updated).startsWith("更新于"))
    }

    @Test
    fun `power without snapshot asks for an app query and shows no zero`() {
        val root = render(kind = WidgetKind.POWER)
        assertTrue(text(root, R.id.widget_power_room).contains("未设置"))
        assertTrue(text(root, R.id.widget_power_value).contains("尚未查询"))
        assertFalse(text(root, R.id.widget_power_value).contains("0.00"))
        assertTrue(text(root, R.id.widget_power_status).contains("先在应用查询一次"))
    }

    @Test
    fun `refreshing and failure keep old values with a short status`() {
        val card = DirectCardSnapshot("2099000123", 12345, 0, now)
        val refreshing = render(kind = WidgetKind.CARD, card = card, refreshing = true)
        assertTrue(text(refreshing, R.id.widget_card_balance).contains("123.45 元"))
        assertTrue(text(refreshing, R.id.widget_card_status).contains("正在刷新"))

        val failed = render(kind = WidgetKind.CARD, card = card, failed = true)
        assertTrue(text(failed, R.id.widget_card_balance).contains("123.45 元"))
        assertTrue(text(failed, R.id.widget_card_status).contains("刷新失败"))
        assertTrue(text(failed, R.id.widget_card_updated).startsWith("更新于"))
    }
}
