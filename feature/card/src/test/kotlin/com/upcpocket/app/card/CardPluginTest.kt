package com.upcpocket.app.card

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import com.upcpocket.app.plugin.runtime.PluginRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CardPluginTest {
    @Test
    fun `plugin exposes backend read features but no recharge writer`() {
        val plugin = CardPlugin(
            context = ApplicationProvider.getApplicationContext(),
            account = FakeAccountIdentity(),
            backend = FakeCardBackendDataSource(),
            onSnapshotChanged = {},
        )
        val active = PluginRuntime().register(plugin.descriptor) { plugin }.start().plugins

        assertEquals(setOf("card.balance", "card.history"), plugin.descriptor.capabilities)
        assertSame(plugin.service, active.capability(CardCapabilities.balance))
        assertSame(plugin.history, active.capability(CardCapabilities.history))
        assertNull(active.capability(CardCapabilities.recharge))
    }
}
