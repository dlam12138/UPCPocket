package com.upcpocket.app.widget

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.designsystem.WidgetRenderSupport
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.feature.card.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CardWidgetRenderingTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_700_000_000_000L

    private fun pending(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, Application::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun render(
        snapshot: DirectCardSnapshot? = null,
        refreshing: Boolean = false,
        failed: Boolean = false,
    ): View = CardWidgetRenderer(context).views(
        snapshot = snapshot,
        refreshing = refreshing,
        failed = failed,
        openApp = { pending(1) },
        refresh = { pending(2) },
    ).apply(context, FrameLayout(context))

    private fun text(root: View, id: Int): String =
        root.findViewById<TextView>(id).text.toString()

    @Test
    fun `compact card shows masked id main balance and hides secondary transition`() {
        val id = "2099000123"
        val root = render(snapshot = DirectCardSnapshot(id, 12345, 678, now))
        val account = text(root, R.id.widget_card_account)
        assertTrue(account.contains("20"))
        assertTrue(account.contains("••••"))
        assertFalse(account.contains(id))
        assertFalse(account.contains("2099000123"))

        assertTrue(text(root, R.id.widget_card_balance).contains("123.45 元"))
        val transition = text(root, R.id.widget_card_transition)
        assertTrue(transition.contains("6.78"))
        assertTrue(transition.contains("不计入"))
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_card_transition).visibility)

        val meta = text(root, R.id.widget_card_updated)
        assertEquals(WidgetRenderSupport.queryTime(now), meta)
        assertFalse(meta.contains("刚刚"))
        assertFalse(meta.contains("分钟前"))

        val refresh = root.findViewById<View>(R.id.widget_card_refresh)
        assertTrue(refresh.contentDescription.toString().contains("刷新校园卡余额"))
        assertTrue(refresh.layoutParams.width >= 48 * context.resources.displayMetrics.density)
        assertTrue(refresh.layoutParams.height >= 48 * context.resources.displayMetrics.density)
    }

    @Test
    fun `card without snapshot guides the user and never fabricates zero`() {
        val root = render()
        assertTrue(text(root, R.id.widget_card_account).contains("未设置"))
        assertTrue(text(root, R.id.widget_card_balance).contains("尚未查询"))
        assertFalse(text(root, R.id.widget_card_balance).contains("0.00"))
        val meta = text(root, R.id.widget_card_updated)
        assertTrue(meta.contains("先在应用查询一次"))
        assertTrue(meta.contains("尚无本地记录"))
        assertTrue(
            root.findViewById<View>(R.id.widget_card_refresh)
                .contentDescription.toString().contains("打开应用"),
        )
    }

    @Test
    fun `refreshing and failure keep old values and the last success time`() {
        val card = DirectCardSnapshot("2099000123", 12345, 0, now)
        val refreshing = render(snapshot = card, refreshing = true)
        assertTrue(text(refreshing, R.id.widget_card_balance).contains("123.45 元"))
        assertTrue(text(refreshing, R.id.widget_card_updated).contains("正在刷新"))

        val failed = render(snapshot = card, failed = true)
        assertTrue(text(failed, R.id.widget_card_balance).contains("123.45 元"))
        val failedMeta = text(failed, R.id.widget_card_updated)
        assertTrue(failedMeta.contains("刷新失败"))
        assertTrue(failedMeta.contains(WidgetRenderSupport.queryTime(now)))
        assertFalse(failedMeta.contains("刚刚"))
    }

    @Test
    fun `time color resolves in the launcher theme rather than the rendering process theme`() {
        fun themed(mode: Int) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            },
        )
        val light = themed(Configuration.UI_MODE_NIGHT_NO)
        val dark = themed(Configuration.UI_MODE_NIGHT_YES)
        val views = CardWidgetRenderer(light).views(
            snapshot = DirectCardSnapshot("12345678", 12345, 0, now),
            refreshing = false,
            failed = false,
            openApp = { pending(1) },
            refresh = { pending(2) },
        )
        val root = views.apply(dark, FrameLayout(dark))
        assertEquals(
            dark.getColor(DesignSystemR.color.widget_secondary),
            root.findViewById<TextView>(R.id.widget_card_updated).currentTextColor,
        )
    }
}
