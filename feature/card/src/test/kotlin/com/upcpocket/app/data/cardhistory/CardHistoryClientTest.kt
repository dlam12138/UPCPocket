package com.upcpocket.app.data.cardhistory

import android.app.Application
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.card.CardBackendTransaction
import com.upcpocket.app.card.FakeCardBackendDataSource
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CardHistoryClientTest {
    @Test
    fun `pages and summaries use backend only and remain in memory`() = runBlocking {
        val account = FakeAccountIdentity().also { it.bind() }
        val backend = FakeCardBackendDataSource().apply {
            transactionRows = listOf(CardBackendTransaction("row-1", "食堂", "2026-11-01 12:00", -820))
            incomeFen = 5000
            expenseFen = 820
        }
        val client = CardHistoryClient(account, backend)
        val month = YearMonth.of(2026, 11)

        val page = client.page(month, current = 1, size = 20)
        val summary = client.summary(month)

        assertEquals("row-1", page.data.rows.single().id)
        assertEquals(-820L, page.data.rows.single().amountFen)
        assertEquals(5000L, summary.incomeFen)
        assertEquals(820L, summary.expenseFen)
        assertEquals(listOf(1), backend.requestedPages)
        assertEquals(listOf(month, month), backend.requestedMonths)
        assertTrue(client.isCurrent(page.owner))
    }

    @Test
    fun `load all pages keeps stable owner and reports progress`() = runBlocking {
        val account = FakeAccountIdentity().also { it.bind() }
        val backend = FakeCardBackendDataSource().apply {
            transactionRows = (1..60).map { CardBackendTransaction("row-$it", "交易 $it", "时间", -it.toLong()) }
        }
        val client = CardHistoryClient(account, backend)
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = client.all(onProgress = { loaded, total -> progress += loaded to total })

        assertEquals(60, result.data.rows.size)
        assertEquals(listOf(1, 2), backend.requestedPages)
        assertEquals(listOf(50 to 60, 60 to 60), progress)
    }

    @Test
    fun `module disabled has a stable reason and does not invalidate managed account`() = runBlocking {
        val account = FakeAccountIdentity().also { it.bind() }
        val client = CardHistoryClient(
            account,
            FakeCardBackendDataSource().apply {
                transactionsFailure = CardBackendException(CardBackendFailureCode.MODULE_DISABLED)
            },
        )

        try {
            client.page()
            org.junit.Assert.fail("module disabled should be exposed")
        } catch (error: CardHistoryException) {
            assertEquals(CardHistoryFailure.MODULE_DISABLED, error.reason)
        }
        assertEquals(SchoolAccountStatus.MANAGED_ACTIVE, account.state.value.status)
    }

    @Test
    fun `late page for previous account is rejected`() = runBlocking {
        val account = FakeAccountIdentity().also { it.bind() }
        val oldOwner = account.accountReference.value!!
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<com.upcpocket.app.card.CardBackendTransactionPage>()
        val backend = object : FakeCardBackendDataSource() {
            override suspend fun transactions(
                month: YearMonth?,
                page: Int,
                size: Int,
            ): com.upcpocket.app.card.CardBackendTransactionPage {
                started.complete(Unit)
                return finish.await()
            }
        }
        val client = CardHistoryClient(account, backend)
        val request = async { runCatching { client.page() } }
        started.await()
        account.bind("acct_test_account_b", 2)
        finish.complete(com.upcpocket.app.card.CardBackendTransactionPage(1, 50, 0, 0, emptyList()))

        assertEquals(CardHistoryFailure.ACCOUNT_CHANGED, (request.await().exceptionOrNull() as CardHistoryException).reason)
        assertFalse(client.isCurrent(oldOwner))
    }
}
