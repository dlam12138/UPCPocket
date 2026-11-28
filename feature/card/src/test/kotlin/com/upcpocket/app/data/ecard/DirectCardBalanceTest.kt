package com.upcpocket.app.data.ecard

import android.app.Application
import com.upcpocket.app.card.CardBackendBalance
import com.upcpocket.app.card.CardBackendDataSource
import com.upcpocket.app.card.CardBackendTransactionPage
import com.upcpocket.app.card.CardBackendTransactionsSummary
import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.platform.RefreshInvalidatedException
import java.io.File
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class DirectCardBalanceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private open class Backend : CardBackendDataSource {
        var response = CardBackendBalance(12345, 678, 1_700_000_000_000)
        var failure: Exception? = null
        open override suspend fun balance(): CardBackendBalance {
            failure?.let { throw it }
            return response
        }
        open override suspend fun transactions(month: YearMonth?, page: Int, size: Int) =
            CardBackendTransactionPage(page, size, 0, 0, emptyList())
        open override suspend fun transactionsSummary(month: YearMonth?) =
            CardBackendTransactionsSummary(0, 0)
    }

    private fun activeAccount() = FakeAccountIdentity().also { it.bind() }
    private fun repository(directory: File, account: FakeAccountIdentity, backend: CardBackendDataSource = Backend()) =
        DirectCardBalanceRepository(DirectCardBalanceStore(directory), account, backend)

    @Test
    fun `new format is owned by account reference and stores no student identity`() = runBlocking {
        val directory = temp.newFolder()
        val account = activeAccount()
        val store = DirectCardBalanceStore(directory)
        val value = repository(directory, account).refreshCurrent()
        assertEquals(12345L, value.balanceCents)
        assertEquals(account.accountReference.value, store.loadCurrent(repository(directory, account).currentBinding()!!)?.accountReference)
        val encoded = File(directory, "card-balance-v3.json").readText()
        assertTrue(encoded.contains("accountId"))
        assertFalse(encoded.contains("studentId"))
    }

    @Test
    fun `legacy student id balance file is ignored and clear removes every sibling`() {
        val directory = temp.newFolder()
        val account = activeAccount()
        val legacy = File(directory, "card-balance-v2.json")
        legacy.writeText("""{"studentId":"2099999999","balanceCents":12345}""")
        File(legacy.path + ".bak").writeText("old backup")
        File(legacy.path + ".new").writeText("old new")
        val store = DirectCardBalanceStore(directory)

        assertNull(store.loadCurrent(repository(directory, account).currentBinding()!!))
        store.clearAll()
        assertFalse(legacy.exists())
        assertFalse(File(legacy.path + ".bak").exists())
        assertFalse(File(legacy.path + ".new").exists())
    }

    @Test
    fun `module disabled preserves cached amount and original fetch time`() = runBlocking {
        val directory = temp.newFolder()
        val account = activeAccount()
        val backend = Backend()
        val repo = repository(directory, account, backend)
        val first = repo.refreshCurrent()
        backend.failure = CardBackendException(CardBackendFailureCode.MODULE_DISABLED)

        try {
            repo.refreshCurrent()
            org.junit.Assert.fail("module disabled should fail the request")
        } catch (_: Exception) {
        }

        val cached = repo.cached()
        assertEquals(first.balanceCents, cached?.balanceCents)
        assertEquals(first.fetchedAt, cached?.fetchedAt)
    }

    @Test
    fun `late balance response cannot write after account changes`() = runBlocking {
        val directory = temp.newFolder()
        val account = activeAccount()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<CardBackendBalance>()
        val backend = object : Backend() {
            override suspend fun balance(): CardBackendBalance {
                started.complete(Unit)
                return response.await()
            }
        }
        val repo = repository(directory, account, backend)
        val request = async { runCatching { repo.refreshCurrent() } }
        started.await()
        account.bind(accountId = "acct_test_account_b", generation = 2)
        response.complete(CardBackendBalance(999, 0, 1_700_000_000_001))

        assertTrue(request.await().exceptionOrNull() is RefreshInvalidatedException)
        assertNull(repo.cached())
    }
}
