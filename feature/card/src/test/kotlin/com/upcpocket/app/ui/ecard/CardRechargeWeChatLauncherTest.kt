package com.upcpocket.app.ui.ecard

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CardRechargeWeChatLauncherTest {
    private val uri = "weixin://wap/pay?fixture=1"
    private fun register(activity: Activity, value: String, packageName: String = "com.tencent.mm", exported: Boolean = true) {
        val target = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.PayActivity"
                this.exported = exported
                enabled = true
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName; enabled = true }
            }
        }
        shadowOf(activity.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(value)).setPackage("com.tencent.mm"), target,
        )
    }

    @Test fun preflight_and_launch_are_pinned_to_the_resolved_exported_WeChat_activity() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        register(activity, "weixin://wap/pay")
        register(activity, uri)
        val launcher = AndroidCardRechargeWeChatLauncher(activity)
        assertTrue(launcher.isAvailable())
        assertTrue(launcher.launch(uri))
        val launched = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals(uri, launched.dataString)
        assertEquals("com.tencent.mm", launched.`package`)
        assertEquals("com.tencent.mm.PayActivity", launched.component!!.className)
        assertEquals(0, launched.flags)
        assertNull(launched.selector)
        assertNull(launched.extras)
    }

    @Test fun other_packages_and_private_handlers_are_rejected_without_launching() {
        for ((packageName, exported) in listOf("com.example.other" to true, "com.tencent.mm" to false)) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            register(activity, "weixin://wap/pay", packageName, exported)
            register(activity, uri, packageName, exported)
            val launcher = AndroidCardRechargeWeChatLauncher(activity)
            assertFalse(launcher.isAvailable())
            assertFalse(launcher.launch(uri))
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun arbitrary_remote_intents_and_non_payment_URIs_never_launch() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val launcher = AndroidCardRechargeWeChatLauncher(activity)
        for (value in listOf("intent://wap/pay#Intent;scheme=weixin;end", "weixin://dl/business?fixture=1", "https://example.org/")) {
            assertFalse(launcher.launch(value))
        }
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
