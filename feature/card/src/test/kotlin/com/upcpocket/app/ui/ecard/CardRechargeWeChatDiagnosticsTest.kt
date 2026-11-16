package com.upcpocket.app.ui.ecard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import com.upcpocket.app.data.card.CardDiagnosticRecorder
import com.upcpocket.app.data.cardrecharge.CardRechargeServiceTest
import com.upcpocket.app.platform.diagnostics.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CardRechargeWeChatDiagnosticsTest {
    private val recorder = CardDiagnosticRecorder()
    private val uri = "weixin://wap/pay?synthetic-uri-canary=1"
    @Before fun attach() { Diagnostics.install(recorder) }
    @After fun detach() { Diagnostics.install(null) }

    private fun register(activity: Activity, value: String) {
        val target = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.tencent.mm"
                name = "com.tencent.mm.PayActivity"
                exported = true
                enabled = true
                applicationInfo = ApplicationInfo().apply { packageName = "com.tencent.mm"; enabled = true }
            }
        }
        shadowOf(activity.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(value)).setPackage("com.tencent.mm"), target)
    }

    @Test fun `unavailable WeChat and missing handler report blocked without launching`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val launcher = AndroidCardRechargeWeChatLauncher(activity)
        assertFalse(launcher.isAvailable())
        assertFalse(launcher.launch(uri))
        assertEquals(DiagnosticResult.BLOCKED, recorder.terminals(DiagnosticDetail.CARD_WECHAT_AVAILABILITY).single().result)
        assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.BLOCKED),
            recorder.forDetail(DiagnosticDetail.CARD_WECHAT_HANDOFF).map { it.result })
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(recorder.terminals(DiagnosticDetail.CARD_PAYMENT_CONFIRMED).isEmpty())
        assertFalse(recorder.events.joinToString().contains("synthetic-uri-canary"))
    }

    @Test fun `accepted dispatch is single use and cannot confirm or clear the pending order`() = runBlocking {
        val f = CardRechargeServiceTest.Fixture()
        f.service.open()
        val handoff = f.service.submit("20")!!
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        register(activity, "weixin://wap/pay")
        register(activity, "weixin://wap/pay?fixture=1")
        val launcher = AndroidCardRechargeWeChatLauncher(activity)
        assertTrue(launcher.isAvailable())
        assertTrue(launcher.launch(handoff.consume()!!))
        assertNull(handoff.consume())
        assertEquals("weixin://wap/pay?fixture=1", shadowOf(activity).nextStartedActivity.dataString)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(listOf(DiagnosticResult.OK, DiagnosticResult.BLOCKED),
            recorder.terminals(DiagnosticDetail.CARD_WECHAT_HANDOFF).map { it.result })
        assertEquals(2, recorder.terminals(DiagnosticDetail.CARD_WECHAT_HANDOFF).map { it.operationId }.distinct().size)
        assertNotNull(f.store.value)
        assertTrue(f.service.state.value.recoveryRequired)
        assertEquals(1, f.createPosts)
        assertEquals(1, f.payPosts)
        assertTrue(recorder.terminals(DiagnosticDetail.CARD_PAYMENT_CONFIRMED).isEmpty())
    }

    @Test fun `startActivity failure has a failed terminal after exactly one dispatch attempt`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        register(activity, uri)
        var attempts = 0
        val context = object : ContextWrapper(activity) {
            override fun startActivity(intent: Intent) {
                attempts++
                throw ActivityNotFoundException("synthetic-launch-message-canary")
            }
        }
        assertFalse(AndroidCardRechargeWeChatLauncher(context).launch(uri))
        assertEquals(1, attempts)
        val events = recorder.forDetail(DiagnosticDetail.CARD_WECHAT_HANDOFF)
        assertEquals(listOf(DiagnosticResult.PENDING, DiagnosticResult.FAIL), events.map { it.result })
        assertEquals(DiagnosticReason.NOT_CONFIGURED, events.last().reason)
        assertEquals(DiagnosticPhase.PUBLISH, events.last().phase)
        assertEquals(1, events.map { it.operationId }.distinct().size)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(recorder.events.joinToString().contains("canary"))
    }
}
