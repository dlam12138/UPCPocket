package com.upcpocket.app.data.cardrecharge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.card.CardPlugin
import com.upcpocket.app.card.FakeCardBackendDataSource
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CardRechargeReadTransportTest {
    @Test
    fun `plugin assembly makes no campus request and exposes only explicit backend reads`() {
        val backend = FakeCardBackendDataSource()
        CardPlugin(
            context = ApplicationProvider.getApplicationContext(),
            account = FakeAccountIdentity(),
            backend = backend,
            onSnapshotChanged = {},
        )

        assertEquals(0, backend.balanceCalls)
        assertEquals(emptyList<Int>(), backend.requestedPages)
        assertEquals(emptyList<java.time.YearMonth?>(), backend.requestedMonths)
    }
}
