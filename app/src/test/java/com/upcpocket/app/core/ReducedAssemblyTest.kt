package com.upcpocket.app.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.card.CardCapabilities
import com.upcpocket.app.classroom.ClassroomPlugin
import com.upcpocket.app.focus.FocusPlugin
import com.upcpocket.app.library.LibraryAccountSettings
import com.upcpocket.app.library.LibraryPlugin
import com.upcpocket.app.plugin.runtime.PluginRuntime
import com.upcpocket.app.plugin.runtime.PluginStartResult
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.power.PowerInput
import com.upcpocket.app.power.PowerInputStore
import com.upcpocket.app.power.PowerPlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 精简装配：不注册 card 插件时，其余插件仍能启动，首页/更多/页面快照里不再出现
 * 余额入口或余额区块；同时不触碰任何网络与数据库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ReducedAssemblyTest {

    @Test
    fun `without card plugin remaining ui and capabilities stay available`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val power = PowerPlugin(
            context = context,
            inputStore = object : PowerInputStore {
                override val input: Flow<PowerInput> = flowOf(PowerInput())
                override suspend fun save(building: String, room: String) = Unit
                override suspend fun clear() = Unit
            },
            onSnapshotChanged = {},
        )
        val classroom = ClassroomPlugin()
        val focus = FocusPlugin(context) { error("不应创建通知 PendingIntent") }
        val library = LibraryPlugin(
            context = context,
            accountSettings = object : LibraryAccountSettings {
                override val rememberLogin = flowOf(false)
                override suspend fun setRememberLogin(enabled: Boolean) = Unit
            },
        )

        val runtime = PluginRuntime()
            .register(power.descriptor) { power }
            .register(classroom.descriptor) { classroom }
            .register(focus.descriptor) { focus }
            .register(library.descriptor) { library }
        val result = runtime.start()

        assertTrue(result is PluginStartResult.Active)
        val active = (result as PluginStartResult.Active).plugins
        assertNull(active.capability(CardCapabilities.balance))

        val ui = HostUiAssembly.build(active)
        assertTrue(ui.sectionsFor(UiSurface.HOME_CARD).isEmpty())
        assertEquals(
            listOf("power.electricity", "classroom.free", "library.seat"),
            ui.entriesFor(UiSurface.HOME_SERVICES).map { it.entryId.value },
        )
        assertEquals(
            setOf(
                "power.electricity",
                "power.tangdaowan",
                "classroom.free",
                "library.seat",
                "library.login",
                "focus.stats",
            ),
            ui.pages.map { it.routeId.value }.toSet(),
        )
        assertFalse(ui.entries.any { it.pluginId.value == "card" })
    }
}
