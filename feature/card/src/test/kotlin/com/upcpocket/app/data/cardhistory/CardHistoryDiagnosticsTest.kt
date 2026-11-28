package com.upcpocket.app.data.cardhistory

import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.card.FakeCardBackendDataSource
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CardHistoryDiagnosticsTest {
    @Test
    fun `typed module failure remains distinct and leaves managed account active`() = runBlocking {
        val account = FakeAccountIdentity().also { it.bind() }
        val backend = FakeCardBackendDataSource().apply {
            transactionsFailure = CardBackendException(CardBackendFailureCode.MODULE_DISABLED)
        }
        val client = CardHistoryClient(account, backend)

        val failure = runCatching { client.page() }.exceptionOrNull() as CardHistoryException

        assertEquals(CardHistoryFailure.MODULE_DISABLED, failure.reason)
        assertEquals(SchoolAccountStatus.MANAGED_ACTIVE, account.state.value.status)
    }
}
