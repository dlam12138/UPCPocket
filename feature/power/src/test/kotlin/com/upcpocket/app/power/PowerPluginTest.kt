package com.upcpocket.app.power

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.plugin.runtime.PluginRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 电量插件的最小装配测试：描述符、能力与运行时解析必须一致。
 * 不联网，也不写真实快照。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class PowerPluginTest {

    @Test
    fun `plugin declares and provides the tangdaowan capability`() {
        var widgetRefreshes = 0
        val plugin = PowerPlugin(
            context = ApplicationProvider.getApplicationContext(),
            inputStore = FakeInputStore(),
            onSnapshotChanged = { widgetRefreshes++ },
        )

        val result = PluginRuntime()
            .register(plugin.descriptor) { plugin }
            .start()

        val active = result.plugins
        assertEquals("power", plugin.descriptor.id.value)
        assertEquals(setOf("power.tangdaowan"), plugin.descriptor.capabilities)
        assertSame(plugin.service, active.capability(PowerCapabilities.tangdaowan))
        assertEquals(0, widgetRefreshes)
    }

    private class FakeInputStore : PowerInputStore {
        override val input: Flow<PowerInput> = MutableStateFlow(PowerInput())
        override suspend fun save(building: String, room: String) = Unit
        override suspend fun clear() = Unit
    }
}
