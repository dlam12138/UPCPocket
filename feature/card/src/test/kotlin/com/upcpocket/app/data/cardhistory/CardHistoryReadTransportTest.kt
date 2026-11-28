package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.card.FakeCardBackendDataSource
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardHistoryReadTransportTest {
    @Test
    fun `backend not ready is surfaced without losing managed account ownership`() = runBlocking {
        val account = FakeAccountIdentity().also { it.bind() }
        val backend = FakeCardBackendDataSource().apply {
            transactionsFailure = CardBackendException(CardBackendFailureCode.BACKEND_NOT_READY)
        }
        val client = CardHistoryClient(account, backend)

        val result = runCatching { client.page() }

        assertEquals(CardHistoryFailure.SERVICE_NOT_READY, (result.exceptionOrNull() as CardHistoryException).reason)
        assertTrue(client.isCurrent(account.accountReference.value!!))
        assertEquals(listOf(1), backend.requestedPages)
    }
}
