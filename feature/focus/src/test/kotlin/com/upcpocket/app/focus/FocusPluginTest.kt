package com.upcpocket.app.focus

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.plugin.runtime.PluginRuntime
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 专注插件的最小装配测试：只构造内存对象，不打开 Room、不恢复计时、不发通知。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class FocusPluginTest {

    @Test
    fun `plugin installs the same focus service without opening the database`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbFile = context.getDatabasePath("upcpocket.db")
        assertFalse(dbFile.exists())

        val plugin = FocusPlugin(
            context = context,
            openAppIntent = { error("装配阶段不能创建通知 PendingIntent") },
        )
        assertFalse(dbFile.exists())

        val result = PluginRuntime()
            .register(plugin.descriptor) { plugin }
            .start()

        val active = result.plugins
        assertEquals("focus", plugin.descriptor.id.value)
        assertEquals(setOf("focus.timer"), plugin.descriptor.capabilities)
        assertSame(plugin.service, active.capability(FocusCapabilities.timer))
        assertSame(plugin.service, FocusRuntime.serviceOrNull())
        assertFalse(dbFile.exists())

        val ui = PluginUiRegistry().also(plugin::contribute).build()
        assertEquals(listOf("focus.tab"), ui.bottomNav.map { it.entryId.value })
        assertEquals("focus.main", ui.bottomNav.single().route.value)
        assertEquals(listOf("focus.stats"), ui.entriesFor(UiSurface.MORE_LEARNING).map { it.entryId.value })
        assertTrue(ui.pages.any { it.routeId == FOCUS_MAIN_ROUTE })
        assertTrue(ui.pages.any { it.routeId == FOCUS_STATS_ROUTE })
    }
}
