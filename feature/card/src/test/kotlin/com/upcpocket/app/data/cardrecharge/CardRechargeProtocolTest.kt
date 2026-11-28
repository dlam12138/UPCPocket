package com.upcpocket.app.data.cardrecharge

import com.upcpocket.app.card.CardBackendBalance
import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.card.CardBackendTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CardRechargeProtocolTest {
    @Test
    fun `card backend data transfer objects contain no student identifier field`() {
        val names = listOf(
            CardBackendBalance::class.java,
            CardBackendTransaction::class.java,
        ).flatMap { type -> type.declaredFields.map { it.name } }

        assertFalse(names.any { it.contains("student", ignoreCase = true) })
        assertFalse(names.any { it.contains("password", ignoreCase = true) })
    }

    @Test
    fun `backend exception exposes only a fixed code and retry delay`() {
        val error = CardBackendException(CardBackendFailureCode.MODULE_DISABLED, retryAfterMillis = 1_000)

        assertEquals(CardBackendFailureCode.MODULE_DISABLED, error.code)
        assertEquals(1_000L, error.retryAfterMillis)
        assertEquals("MODULE_DISABLED", error.message)
    }
}
