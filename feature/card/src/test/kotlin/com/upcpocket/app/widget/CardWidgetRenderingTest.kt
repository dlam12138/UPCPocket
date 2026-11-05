package com.upcpocket.app.widget

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.data.ecard.CardBalanceBinding
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.ecard.DirectCardSnapshot
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
    private val id = "2099000123"
    private val binding = CardBalanceBinding(
        accountId = SchoolAccountId("acct_test_account_a"),
        studentId = id,
        credentialGeneration = AccountGeneration(2),
    )

    private fun pending(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, Application::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun render(
        status: CardBindingStatus = CardBindingStatus(CardBindingAvailability.READY, binding),
        snapshot: DirectCardSnapshot? = null,
        refreshing: Boolean = false,
        failed: Boolean = false,
        onRefreshFactory: () -> Unit = {},
        onOpenFactory: () -> Unit = {},
    ): View = CardWidgetRenderer(context).views(
        status = status,
        snapshot = snapshot,
        refreshing = refreshing,
        failed = failed,
        openApp = {
            onOpenFactory()
            pending(1)
        },
        refresh = {
            onRefreshFactory()
            pending(2)
        },
    ).apply(context, FrameLayout(context))

    private fun text(root: View, id: Int): String =
        root.findViewById<TextView>(id).text.toString()

    @Test
    fun `compact card shows main balance unit and one-line update only`() {
        val root = render(
            snapshot = DirectCardSnapshot(
                accountId = binding.accountId.value,
                studentId = id,
                credentialGeneration = 2,
                balanceCents = 12345,
                transitionCents = 678,
                fetchedAt = now,
            ),
        )
        assertEquals("123.45", text(root, R.id.widget_card_balance))
        assertEquals("元", text(root, R.id.widget_card_unit))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_card_unit).visibility)

        val meta = text(root, R.id.widget_card_updated)
        assertTrue(meta.endsWith("更新"))
        assertFalse(meta.contains("\n"))
        assertFalse(meta.contains("12-"))

        val refresh = root.findViewById<View>(R.id.widget_card_refresh)
        assertTrue(refresh.contentDescription.toString().contains("刷新校园卡余额"))
        val minTouch = (44 * context.resources.displayMetrics.density).toInt()
        assertTrue(refresh.layoutParams.width >= minTouch)
        assertTrue(refresh.layoutParams.height >= minTouch)
    }

    @Test
    fun `bound account without snapshot enables refresh instead of requiring old cache`() {
        var refreshFactory = 0
        val root = render(onRefreshFactory = { refreshFactory++ })
        assertEquals("尚未查询", text(root, R.id.widget_card_balance))
        assertEquals("已绑定 · 点击刷新", text(root, R.id.widget_card_updated))
        assertTrue(
            root.findViewById<View>(R.id.widget_card_refresh)
                .contentDescription.toString().contains("刷新校园卡余额"),
        )
        assertEquals(1, refreshFactory)
    }

    @Test
    fun `no account guides setup and never creates a network refresh target`() {
        var refreshFactory = 0
        var openFactory = 0
        val root = render(
            status = CardBindingStatus(CardBindingAvailability.NO_ACCOUNT),
            onRefreshFactory = { refreshFactory++ },
            onOpenFactory = { openFactory++ },
        )
        assertEquals("尚未绑定", text(root, R.id.widget_card_balance))
        assertEquals("打开应用设置学校账号", text(root, R.id.widget_card_updated))
        assertTrue(
            root.findViewById<View>(R.id.widget_card_refresh)
                .contentDescription.toString().contains("打开应用"),
        )
        assertEquals(0, refreshFactory)
        assertTrue(openFactory >= 1)
    }

    @Test
    fun `cleaning hides old identity values even when an old snapshot is supplied`() {
        val old = DirectCardSnapshot(
            accountId = binding.accountId.value,
            studentId = id,
            credentialGeneration = 1,
            balanceCents = 12345,
            transitionCents = 0,
            fetchedAt = now,
        )
        val root = render(
            status = CardBindingStatus(CardBindingAvailability.CLEANING),
            snapshot = old,
        )
        assertEquals("清理中", text(root, R.id.widget_card_balance))
        assertEquals("旧身份数值已隐藏", text(root, R.id.widget_card_updated))
        assertFalse(text(root, R.id.widget_card_balance).contains("123.45"))
    }

    @Test
    fun `refreshing and failure keep old values and the last success time`() {
        val card = DirectCardSnapshot(
            accountId = binding.accountId.value,
            studentId = id,
            credentialGeneration = 2,
            balanceCents = 12345,
            transitionCents = 0,
            fetchedAt = now,
        )
        val refreshing = render(snapshot = card, refreshing = true)
        assertEquals("123.45", text(refreshing, R.id.widget_card_balance))
        assertEquals("正在更新…", text(refreshing, R.id.widget_card_updated))

        val failed = render(snapshot = card, failed = true)
        assertEquals("123.45", text(failed, R.id.widget_card_balance))
        val failedMeta = text(failed, R.id.widget_card_updated)
        assertTrue(failedMeta.contains("更新失败"))
        assertFalse(failedMeta.contains("\n"))
    }

    @Test
    fun `same-owner cache from older credential generation is marked historical`() {
        val card = DirectCardSnapshot(
            accountId = binding.accountId.value,
            studentId = id,
            credentialGeneration = 1,
            balanceCents = 12345,
            transitionCents = 0,
            fetchedAt = now,
        )
        val root = render(snapshot = card)
        assertTrue(text(root, R.id.widget_card_updated).contains("历史值"))
    }

    @Test
    fun `long balance steps down instead of clipping the number`() {
        val renderer = CardWidgetRenderer(context)
        assertEquals(36f, renderer.numberTextSize("6.99"))
        assertEquals(30f, renderer.numberTextSize("1234.56"))
        assertEquals(25f, renderer.numberTextSize("123456.78"))
        assertEquals(16.5f, renderer.numberTextSize("12345.67", fontScale = 1.5f))
        assertTrue(renderer.unitTextSize(30f) < 30f)
    }

    @Test
    fun `160dp at 15x keeps title and balance unclipped`() {
        val config = Configuration(context.resources.configuration).apply {
            fontScale = 1.5f
        }
        val ctx = context.createConfigurationContext(config)
        val views = CardWidgetRenderer(ctx).views(
            status = CardBindingStatus(CardBindingAvailability.READY, binding),
            snapshot = DirectCardSnapshot(
                accountId = binding.accountId.value,
                studentId = id,
                credentialGeneration = 2,
                balanceCents = 699,
                transitionCents = 0,
                fetchedAt = now,
            ),
            refreshing = false,
            failed = false,
            openApp = { pending(1) },
            refresh = { pending(2) },
        )
        val root = views.apply(ctx, FrameLayout(ctx))
        val size = (160 * ctx.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, size, size)
        val title = root.findViewById<TextView>(R.id.widget_card_title)
        val balance = root.findViewById<TextView>(R.id.widget_card_balance)
        assertTrue(title.height >= title.layout.height)
        assertEquals(0, balance.layout.getEllipsisCount(0))
        assertEquals("6.99", balance.text.toString())
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
            status = CardBindingStatus(CardBindingAvailability.READY, binding),
            snapshot = DirectCardSnapshot(
                accountId = binding.accountId.value,
                studentId = id,
                credentialGeneration = 2,
                balanceCents = 12345,
                transitionCents = 0,
                fetchedAt = now,
            ),
            refreshing = false,
            failed = false,
            openApp = { pending(1) },
            refresh = { pending(2) },
        )
        val root = views.apply(dark, FrameLayout(dark))
        assertEquals(
            dark.getColor(DesignSystemR.color.widget_tertiary),
            root.findViewById<TextView>(R.id.widget_card_updated).currentTextColor,
        )
    }
}
