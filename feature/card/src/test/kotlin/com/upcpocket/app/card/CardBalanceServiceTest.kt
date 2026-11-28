package com.upcpocket.app.card

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CampusCardException
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CardBalanceServiceTest {
    private fun service(account: FakeAccountIdentity, backend: FakeCardBackendDataSource) =
        CardBalanceService(
            context = ApplicationProvider.getApplicationContext(),
            account = account,
            backend = backend,
            onSnapshotChanged = {},
        )

    @Test
    fun `only active managed reference opens balance binding`() {
        val account = FakeAccountIdentity(initialStatus = SchoolAccountStatus.LOADING)
        val service = service(account, FakeCardBackendDataSource())
        assertEquals(CardBindingAvailability.LOADING, service.bindingStatus().availability)
        assertFalse(service.canRefreshNow())
        assertNull(service.readWidgetStateBlocking().state.snapshot)

        account.bind()
        assertEquals(CardBindingAvailability.READY, service.bindingStatus().availability)
        assertTrue(service.canRefreshNow())
        assertTrue(service.bindingToken()!!.startsWith("acct_test_account_a:1"))
        assertFalse(service.bindingToken()!!.contains("student"))

        account.cleaning()
        assertEquals(CardBindingAvailability.CLEANING, service.bindingStatus().availability)
        assertFalse(service.canRefreshNow())
        assertNull(service.readWidgetStateBlocking().state.snapshot)
    }

    @Test
    fun `backend failure leaves previous same account balance and timestamp`() = runBlocking {
        val account = FakeAccountIdentity().also { it.bind() }
        val backend = FakeCardBackendDataSource()
        val service = service(account, backend)
        service.clearCache()
        val original = service.refreshCurrent()
        backend.balanceFailure = CardBackendException(CardBackendFailureCode.MODULE_DISABLED)

        try {
            service.refreshCurrent()
            org.junit.Assert.fail("module disabled should be reported")
        } catch (expected: CampusCardException) {
            assertTrue(expected.message!!.contains("暂未开放"))
        }

        val cached = service.readWidgetStateBlocking().state.snapshot
        assertEquals(original, cached)
        assertEquals(original.fetchedAt, cached?.fetchedAt)
        service.clearCache()
    }

    @Test
    fun `credential rotation retains old balance as same account offline history`() = runBlocking {
        val account = FakeAccountIdentity().also { it.bind() }
        val service = service(account, FakeCardBackendDataSource())
        service.clearCache()
        val previous = service.refreshCurrent()
        account.bind(generation = 2)

        val read = service.readWidgetStateBlocking()
        assertEquals(CardBindingAvailability.READY, read.state.status.availability)
        assertEquals("acct_test_account_a", read.state.status.binding?.accountReference?.accountId?.value)
        assertEquals(previous.accountReference.credentialGeneration, read.state.snapshot?.accountReference?.credentialGeneration)
        assertEquals(read.revision, service.revisionNow())
        service.clearCache()
    }
}
