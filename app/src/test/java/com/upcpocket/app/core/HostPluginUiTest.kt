package com.upcpocket.app.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.todo.TodoLaunchContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 宿主装配检查：首页服务、课表/专注 tab 与设置里的学校登录区来自已激活插件，
 * 而不是 AppNav/HomeScreen 的第二份硬编码。
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
    fun `home service entries keep concise copy and stable routes`() {
        val entries = AppGraph.ui.entriesFor(UiSurface.HOME_SERVICES)

        assertEquals(
            listOf("power.electricity", "classroom.free", "library.home"),
            entries.map { it.entryId.value },
        )
        assertEquals(
            listOf("电费", "空闲教室", "图书馆"),
            entries.map { it.title },
        )
        assertEquals(
            listOf(
                "唐岛湾 / 古镇口",
                "按日期与节次",
                "三馆座位",
            ),
            entries.map { it.subtitle },
        )
        assertEquals(
            listOf("power.electricity", "classroom.free", "library.home"),
            entries.map { it.route?.value },
        )
        entries.forEach { entry ->
            assertNotNull("入口 ${entry.entryId} 缺少页面", AppGraph.ui.page(entry.route!!))
        }
    }

    @Test
    fun `todo open intents keep colliding uuid hashes separate`() {
        val first = "47ef2254-ebd1-4c07-9a5e-022143e6ab61"
        val second = "5235b5d5-d2ef-4431-829c-b670e3e11dfb"
        assertEquals(first.hashCode(), second.hashCode())

        val firstIntent = AppGraph.todoOpenIntent(first)
        val secondIntent = AppGraph.todoOpenIntent(second)

        assertNotSame(firstIntent, secondIntent)
        assertNotEquals(
            shadowOf(firstIntent).savedIntent.data,
            shadowOf(secondIntent).savedIntent.data,
        )
        assertEquals(
            first,
            shadowOf(firstIntent).savedIntent.getStringExtra(TodoLaunchContract.EXTRA_TASK_ID),
        )
        assertEquals(
            second,
            shadowOf(secondIntent).savedIntent.getStringExtra(TodoLaunchContract.EXTRA_TASK_ID),
        )
    }

    @Test
    fun `timetable todo and focus are the only plugin tabs and more list is gone`() {
        val tabs = AppGraph.ui.bottomNav
        assertEquals(
            listOf("timetable.tab", "todo.tab", "focus.tab"),
            tabs.map { it.entryId.value },
        )
        assertEquals(listOf("课表", "待办", "专注"), tabs.map { it.label })
        assertEquals(listOf(5, 7, 10), tabs.map { it.order })
        assertEquals(
            listOf("timetable.week", "todo.list", "focus"),
            tabs.map { it.route.value },
        )
        tabs.forEach { tab ->
            assertNotNull("tab ${tab.entryId} 缺少已注册页面", AppGraph.ui.page(tab.route))
        }
        assertTrue(AppGraph.ui.entriesFor(UiSurface.MORE_LEARNING).isEmpty())

        assertEquals(
            listOf("card.balance.section"),
            AppGraph.ui.sectionsFor(UiSurface.HOME_CARD).map { it.entryId.value },
        )
        assertEquals(
            listOf("todo.home"),
            AppGraph.ui.sectionsFor(UiSurface.HOME_TODO).map { it.entryId.value },
        )
        assertEquals(
            listOf("library.account.section"),
            AppGraph.ui.sectionsFor(UiSurface.MORE_SCHOOL).map { it.entryId.value },
        )
    }

    @Test
    fun `timetable plugin is active with a service capability`() {
        val service = AppGraph.activePlugins.capability(
            com.upcpocket.app.timetable.TimetableCapabilities.week,
        )
        assertNotNull(service)
    }

    @Test
    fun `todo plugin is active with the shared store capability`() {
        val store = AppGraph.activePlugins.capability(
            com.upcpocket.app.todo.TodoCapabilities.store,
        )
        assertNotNull(store)
        assertEquals("todo", AppGraph.ui.page(com.upcpocket.app.todo.TODO_ROUTE)?.pluginId?.value)
    }

    @Test
    fun `focus tab remains a plugin contribution between host home and timetable`() {
        val tabs = AppGraph.ui.bottomNav.associateBy { it.entryId.value }
        assertEquals("专注", tabs.getValue("focus.tab").label)
        assertEquals("focus", tabs.getValue("focus.tab").route.value)
        assertEquals(10, tabs.getValue("focus.tab").order)
    }

    @Test
    fun `all plugin pages are registered for host navigation`() {
        assertEquals(
            setOf(
                "power.electricity",
                "power.tangdaowan",
                "classroom.free",
                "library.home",
                "library.seat",
                "library.login",
                "library.nspace",
                "library.nspace.login",
                "library.nspace.reservations",
                "library.chaoxing.seat",
                "library.chaoxing.seatengine",
                "library.chaoxing.reserve",
                "library.chaoxing.reserve.qiushi",
                "library.chaoxing.login",
                "focus",
                "focus.stats",
                "timetable.week",
                "todo.list",
            ),
            AppGraph.ui.pages.map { it.routeId.value }.toSet(),
        )
        assertTrue(
            AppGraph.ui.pages.all {
                it.pluginId.value in setOf("power", "classroom", "library", "focus", "timetable", "todo")
            },
        )
    }
}
