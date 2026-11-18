package com.upcpocket.app.ui.ecard

import org.junit.Assert.assertEquals
import org.junit.Test

class CardHistoryCategoryShareTest {

    @Test
    fun equalCategoriesEachFillHalfOfLoadedExpenses() {
        val loadedExpenseFen = 800L

        assertEquals(0.5f, categoryExpenseFraction(400L, loadedExpenseFen), 0.0001f)
        assertEquals(0.5f, categoryExpenseFraction(400L, loadedExpenseFen), 0.0001f)
    }

    @Test
    fun unequalCategoriesUseLoadedExpenseTotalAsDenominator() {
        val loadedExpenseFen = 800L

        assertEquals(0.75f, categoryExpenseFraction(600L, loadedExpenseFen), 0.0001f)
        assertEquals(0.25f, categoryExpenseFraction(200L, loadedExpenseFen), 0.0001f)
    }
}
