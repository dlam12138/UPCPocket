package com.upcpocket.app.card

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.ecard.FakeAccountIdentity
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
 * 余额插件的最小装配测试：描述符、能力和运行时解析必须一致。
 * 不联网，也不写真实用户快照。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CardPluginTest {

    @Test
    fun `plugin declares and provides the card balance capability`() {
        var widgetRefreshes = 0
        val plugin = CardPlugin(
            context = ApplicationProvider.getApplicationContext(),
            account = FakeAccountIdentity(),
            inputStore = FakeInputStore(),
            onSnapshotChanged = { widgetRefreshes++ },
        )

        val result = PluginRuntime()
            .register(plugin.descriptor) { plugin }
            .start()

        val active = result.plugins
        assertEquals("card", plugin.descriptor.id.value)
        assertEquals(setOf("card.balance"), plugin.descriptor.capabilities)
        assertSame(plugin.service, active.capability(CardCapabilities.balance))
        assertEquals(0, widgetRefreshes)
    }

    @Test
    fun `native history is optional and only registered with auth adapter`() {
        val plugin = CardPlugin(
            context = ApplicationProvider.getApplicationContext(),
            account = FakeAccountIdentity(), inputStore = FakeInputStore(), onSnapshotChanged = {},
            historyAuthenticator = com.upcpocket.app.data.cardhistory.CardHistoryAuthenticator { error("Must not authenticate during assembly") },
        )
        val active = PluginRuntime().register(plugin.descriptor) { plugin }.start().plugins
        assertEquals(setOf("card.balance", "card.history"), plugin.descriptor.capabilities)
        assertSame(plugin.history, active.capability(CardCapabilities.history))
    }

    private class FakeInputStore : CardInputStore {
        override val studentId: Flow<String> = MutableStateFlow("")
        override suspend fun save(studentId: String) = Unit
        override suspend fun clear() = Unit
    }
}
