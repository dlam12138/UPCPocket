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
    fun `compact card shows masked id main balance and hides secondary transition`() {
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
        val account = text(root, R.id.widget_card_account)
        assertTrue(account.contains("20"))
        assertTrue(account.contains("••••"))
        assertFalse(account.contains(id))

        assertTrue(text(root, R.id.widget_card_balance).contains("123.45 元"))
        val transition = text(root, R.id.widget_card_transition)
        assertTrue(transition.contains("6.78"))
        assertTrue(transition.contains("不计入"))
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_card_transition).visibility)

        val meta = text(root, R.id.widget_card_updated)
        assertEquals(WidgetRenderSupport.queryTime(now), meta)
        assertFalse(meta.contains("刚刚"))

        val refresh = root.findViewById<View>(R.id.widget_card_refresh)
        assertTrue(refresh.contentDescription.toString().contains("刷新校园卡余额"))
        assertTrue(refresh.layoutParams.width >= 48 * context.resources.displayMetrics.density)
        assertTrue(refresh.layoutParams.height >= 48 * context.resources.displayMetrics.density)
    }

    @Test
    fun `bound account without snapshot enables refresh instead of requiring old cache`() {
        var refreshFactory = 0
        val root = render(onRefreshFactory = { refreshFactory++ })
        val account = text(root, R.id.widget_card_account)
        assertTrue(account.contains("20••••23"))
        assertFalse(account.contains(id))
        assertTrue(text(root, R.id.widget_card_balance).contains("尚未查询"))
        assertFalse(text(root, R.id.widget_card_balance).contains("0.00"))
        assertTrue(text(root, R.id.widget_card_updated).contains("点击刷新"))
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
        assertTrue(text(root, R.id.widget_card_account).contains("未绑定"))
        assertTrue(text(root, R.id.widget_card_balance).contains("尚未查询"))
        assertTrue(text(root, R.id.widget_card_updated).contains("打开应用设置"))
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
        assertFalse(text(root, R.id.widget_card_account).contains("20••••"))
        assertFalse(text(root, R.id.widget_card_balance).contains("123.45"))
        assertTrue(text(root, R.id.widget_card_updated).contains("隐藏旧身份"))
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
        assertTrue(text(refreshing, R.id.widget_card_balance).contains("123.45 元"))
        assertTrue(text(refreshing, R.id.widget_card_updated).contains("正在刷新"))

        val failed = render(snapshot = card, failed = true)
        assertTrue(text(failed, R.id.widget_card_balance).contains("123.45 元"))
        val failedMeta = text(failed, R.id.widget_card_updated)
        assertTrue(failedMeta.contains("刷新失败"))
        assertTrue(failedMeta.contains(WidgetRenderSupport.queryTime(now)))
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
        val meta = text(root, R.id.widget_card_updated)
        assertTrue(meta.contains("历史值"))
        assertTrue(meta.contains(WidgetRenderSupport.queryTime(now)))
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
            dark.getColor(DesignSystemR.color.widget_secondary),
            root.findViewById<TextView>(R.id.widget_card_updated).currentTextColor,
        )
    }
}
