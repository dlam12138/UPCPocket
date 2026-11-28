package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.card.CardBackendTransaction
import com.upcpocket.app.card.CardBackendTransactionPage
import com.upcpocket.app.card.FakeCardBackendDataSource
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CardHistoryProtocolTest {
    @Test
    fun `duplicate rows are rejected instead of appearing as a complete page`() = runBlocking {
        val backend = object : FakeCardBackendDataSource() {
            override suspend fun transactions(
                month: java.time.YearMonth?,
                page: Int,
                size: Int,
            ) = CardBackendTransactionPage(
                page = page,
                size = size,
                total = 2,
                pages = 1,
                rows = listOf(
                    CardBackendTransaction("duplicate", "交易一", "时间", -10),
                    CardBackendTransaction("duplicate", "交易二", "时间", -20),
                ),
            )
        }
        val client = CardHistoryClient(FakeAccountIdentity().also { it.bind() }, backend)

        val failure = runCatching { client.page(size = 2) }.exceptionOrNull() as CardHistoryException

        assertEquals(CardHistoryFailure.PROTOCOL, failure.reason)
    }
}
