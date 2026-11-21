package com.upcpocket.app.ui.official

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.wechat.WeChatDirect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 体育馆与游泳馆预约交接只复制公开名称并启动微信，不解析深链、不读取场次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class GymReservationHandoffTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val weChatActivityName = "com.tencent.mm.ui.LauncherUI"

    @Test
    fun `copy account name writes only the public account name`() {
        assertEquals("中国石油大学体育场馆", GymReservationHandoff.OFFICIAL_ACCOUNT_NAME)
        assertTrue(GymReservationHandoff.copyAccountName(context))

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        assertNotNull(clip)
        assertEquals(1, clip!!.itemCount)
        assertEquals(GymReservationHandoff.OFFICIAL_ACCOUNT_NAME, clip.getItemAt(0).text.toString())
    }

    @Test
    fun `missing wechat stays on the handoff without starting anything`() {
        assertNull(GymReservationHandoff.weChatLaunchIntent(context.packageManager))
        assertEquals(WeChatHandoffResult.NOT_INSTALLED, GymReservationHandoff.openWeChat(context))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `installed wechat launches its own launcher activity`() {
        registerWeChatLauncher()

        val intent = GymReservationHandoff.weChatLaunchIntent(context.packageManager)
        assertNotNull(intent)
        assertEquals(WeChatDirect.PACKAGE_NAME, intent!!.component?.packageName)
        assertEquals(weChatActivityName, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        assertEquals(WeChatHandoffResult.OPENED, GymReservationHandoff.openWeChat(context))
        val started = shadowOf(context).nextStartedActivity
        assertNotNull(started)
        assertEquals(WeChatDirect.PACKAGE_NAME, started!!.component?.packageName)
        assertEquals(weChatActivityName, started.component?.className)
    }

    @Test
    fun `wechat lookup ignores unrelated packages`() {
        registerLauncher("com.tencent.mm.fake", "com.tencent.mm.fake.Launcher")

        assertNull(GymReservationHandoff.weChatLaunchIntent(context.packageManager))
        assertEquals(WeChatHandoffResult.NOT_INSTALLED, GymReservationHandoff.openWeChat(context))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `venue handoffs keep their documented titles menus and public pages`() {
        val gym = OfficialWebCatalog.gymReservation
        val pool = OfficialWebCatalog.poolReservation

        assertEquals(GymReservationHandoff.gym, gym.weChatHandoff)
        assertEquals(GymReservationHandoff.pool, pool.weChatHandoff)
        assertEquals("中国石油大学体育场馆", gym.weChatHandoff?.accountName)
        assertEquals("中国石油大学体育场馆", pool.weChatHandoff?.accountName)
        assertEquals("体育馆预约", gym.title)
        assertEquals("游泳馆预约", pool.title)
        assertEquals("场地预定 → 场地预定", gym.weChatHandoff?.menuPath)
        assertEquals("场地预定 → 游泳馆预定", pool.weChatHandoff?.menuPath)
        assertEquals("查看预约规则", gym.weChatHandoff?.infoLabel)
        assertEquals("查看游泳馆介绍", pool.weChatHandoff?.infoLabel)
        assertEquals(GymReservationHandoff.RULES_URL, gym.url)
        assertEquals(GymReservationHandoff.POOL_INTRO_URL, pool.url)

        val expected = listOf(
            gym to GymReservationHandoff.RULES_URL,
            pool to GymReservationHandoff.POOL_INTRO_URL,
        )
        for ((link, url) in expected) {
            val intent = OfficialWebCatalog.browserIntent(link)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertNull(intent.extras)
        }
    }

    private fun registerWeChatLauncher() {
        registerLauncher(WeChatDirect.PACKAGE_NAME, weChatActivityName)
    }

    private fun registerLauncher(packageName: String, activityName: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = activityName
                exported = true
                enabled = true
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    enabled = true
                }
            }
        }
        for (category in listOf(Intent.CATEGORY_INFO, Intent.CATEGORY_LAUNCHER)) {
            shadowOf(context.packageManager).addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(packageName),
                info,
            )
        }
    }
}
