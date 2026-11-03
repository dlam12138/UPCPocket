package com.upcpocket.app.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.plugin.runtime.PluginRuntime
import com.upcpocket.app.plugin.runtime.PluginStartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 图书馆插件的最小装配测试：不登录、不联网、不写会话密文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class LibraryPluginTest {

    @Test
    fun `plugin declares and provides the seat capability`() {
        val plugin = LibraryPlugin(
            context = ApplicationProvider.getApplicationContext(),
            accountSettings = object : LibraryAccountSettings {
                override val rememberLogin = kotlinx.coroutines.flow.flowOf(false)
                override suspend fun setRememberLogin(enabled: Boolean) = Unit
            },
        )

        val result = PluginRuntime()
            .register(plugin.descriptor) { plugin }
            .start()

        assertTrue(result is PluginStartResult.Active)
        val active = (result as PluginStartResult.Active).plugins
        assertEquals("library", plugin.descriptor.id.value)
        assertEquals(setOf("library.seat"), plugin.descriptor.capabilities)
        assertSame(plugin.service, active.capability(LibraryCapabilities.seat))
    }
}
