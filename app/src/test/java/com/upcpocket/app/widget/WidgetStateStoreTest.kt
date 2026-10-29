package com.upcpocket.app.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class WidgetStateStoreTest {

    private lateinit var state: WidgetStateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        state = WidgetStateStore(context)
        state.clear(WidgetKind.CARD)
        state.clear(WidgetKind.POWER)
    }

    @Test
    fun `duplicate refresh is merged while the first one is active`() {
        assertTrue(state.tryBegin(WidgetKind.CARD, now = 1_000L))
        assertFalse(state.tryBegin(WidgetKind.CARD, now = 1_001L))
        assertTrue(state.isRefreshing(WidgetKind.CARD, now = 1_001L))

        // 另一类不受影响。
        assertTrue(state.tryBegin(WidgetKind.POWER, now = 1_001L))
    }

    @Test
    fun `stale refreshing state recovers after process death`() {
        assertTrue(state.tryBegin(WidgetKind.CARD, now = 10_000L))
        assertFalse(state.isRefreshing(WidgetKind.CARD, now = 10_000L + WidgetStateStore.STALE_MS))
        assertTrue(state.tryBegin(WidgetKind.CARD, now = 10_000L + WidgetStateStore.STALE_MS))
    }

    @Test
    fun `error is isolated per widget kind and cleared on new refresh`() {
        state.setError(WidgetKind.CARD, "offline fixture")
        assertTrue(state.error(WidgetKind.CARD) != null)
        assertNull(state.error(WidgetKind.POWER))

        assertTrue(state.tryBegin(WidgetKind.CARD, now = 2_000L))
        assertNull(state.error(WidgetKind.CARD))
    }
}
