package com.upcpocket.app.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.plugin.ui.UiSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 宿主装配检查：首页/更多入口来自已激活插件，而不是 AppNav/HomeScreen 里的第二份硬编码。
 *
 * 只构建注册表与 UI 快照，不组合页面、不联网、不打开专注数据库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class HostPluginUiTest {

    @Before
    fun setUp() {
        AppGraph.init(ApplicationProvider.getApplicationContext<Application>())
        AppGraph.startPlugins()
    }

    @Test
    fun `home service entries keep existing order copy and stable routes`() {
        val entries = AppGraph.ui.entriesFor(UiSurface.HOME_SERVICES)

        assertEquals(
            listOf("power.electricity", "classroom.free", "library.seat"),
            entries.map { it.entryId.value },
        )
        assertEquals(
            listOf("电费", "空闲教室", "图书馆座位预约"),
            entries.map { it.title },
        )
        assertEquals(
            listOf("唐岛湾 · 古镇口", "按校区、日期、楼宇和节次查询", "学校统一认证登录后选馆区、座位与时间"),
            entries.map { it.subtitle },
        )
        assertEquals(
            listOf("power.electricity", "classroom.free", "library.seat"),
            entries.map { it.route?.value },
        )
        entries.forEach { entry ->
            assertNotNull("入口 ${entry.entryId} 缺少页面", AppGraph.ui.page(entry.route!!))
        }
    }

    @Test
    fun `more groups and card section are plugin owned`() {
        val learning = AppGraph.ui.entriesFor(UiSurface.MORE_LEARNING)
        assertEquals(listOf("focus.stats"), learning.map { it.entryId.value })
        assertEquals("学习统计", learning.single().title)
        assertEquals("专注时长、热力图、标签分布", learning.single().subtitle)
        assertEquals("focus.stats", learning.single().route?.value)

        assertEquals(
            listOf("card.balance.section"),
            AppGraph.ui.sectionsFor(UiSurface.HOME_CARD).map { it.entryId.value },
        )
        assertEquals(
            listOf("library.account.section"),
            AppGraph.ui.sectionsFor(UiSurface.MORE_SCHOOL).map { it.entryId.value },
        )
    }

    @Test
    fun `focus tab is a plugin contribution between host tabs`() {
        val tabs = AppGraph.ui.bottomNav
        assertEquals(listOf("focus.tab"), tabs.map { it.entryId.value })
        assertEquals("专注", tabs.single().label)
        assertEquals("focus", tabs.single().route.value)
        assertEquals(10, tabs.single().order)
    }

    @Test
    fun `all plugin pages are registered for host navigation`() {
        assertEquals(
            setOf(
                "power.electricity",
                "power.tangdaowan",
                "classroom.free",
                "library.seat",
                "library.login",
                "focus",
                "focus.stats",
            ),
            AppGraph.ui.pages.map { it.routeId.value }.toSet(),
        )
        assertTrue(AppGraph.ui.pages.all { it.pluginId.value in setOf("power", "classroom", "library", "focus") })
    }
}
