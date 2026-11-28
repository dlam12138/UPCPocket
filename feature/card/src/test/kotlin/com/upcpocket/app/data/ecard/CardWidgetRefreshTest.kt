package com.upcpocket.app.data.ecard

import android.app.Application
import com.upcpocket.app.card.CardBackendBalance
import com.upcpocket.app.card.CardBackendDataSource
import com.upcpocket.app.card.CardBackendTransactionPage
import com.upcpocket.app.card.CardBackendTransactionsSummary
import com.upcpocket.app.platform.RefreshInvalidatedException
import java.io.File
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
class CardWidgetRefreshTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `cache clear fences a queued balance response before widget publication`() = runBlocking {
        val directory = temp.newFolder()
        val account = FakeAccountIdentity().also { it.bind() }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<CardBackendBalance>()
        val backend = object : CardBackendDataSource {
            override suspend fun balance(): CardBackendBalance {
                started.complete(Unit)
                return finish.await()
            }
            override suspend fun transactions(month: YearMonth?, page: Int, size: Int) =
                CardBackendTransactionPage(page, size, 0, 0, emptyList())
            override suspend fun transactionsSummary(month: YearMonth?) =
                CardBackendTransactionsSummary(0, 0)
        }
        val store = DirectCardBalanceStore(directory)
        val repository = DirectCardBalanceRepository(store, account, backend)
        val request = async { runCatching { repository.refreshCurrent() } }
        started.await()
        repository.clearCache()
        finish.complete(CardBackendBalance(10_000, 0, 1_700_000_000_000))

        assertTrue(request.await().exceptionOrNull() is RefreshInvalidatedException)
        assertNull(repository.cached())
        assertNull(store.loadCurrent(repository.currentBinding()!!))
    }

    @Test
    fun `widget cache cleanup deletes legacy id cache and atomic leftovers`() {
        val directory = temp.newFolder()
        val old = File(directory, "card-balance-v2.json")
        File(old.path + ".bak").writeText("legacy")
        File(old.path + ".new").writeText("legacy")
        DirectCardBalanceStore(directory).clearAll()

        assertTrue(!old.exists())
        assertTrue(!File(old.path + ".bak").exists())
        assertTrue(!File(old.path + ".new").exists())
    }
}
