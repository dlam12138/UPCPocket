package com.upcpocket.app.data.ecard

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CampusCardTransactionsTest {
    private val record = """{"orderId":"fixture-order","tranamt":"1205","typeFrom":"2","ebagamt":3450,"resume":"虚构食堂","jndatetimeStr":"2026-10-01 12:00:00","effectdateStr":"2026-10-02 00:00:00"}"""
    private fun body(records: String = record) = """{"code":200,"data":{"records":[$records],"total":1,"pages":1,"current":1}}"""

    @Test fun `personal query has fixed endpoint dates and page without student identifiers`() {
        assertEquals("/berserker-search/search/personal/turnover?size=20&current=2&timeFrom=2026-10-01&timeTo=2026-10-31&orderBy=jndatetime",
            CampusCardTransactions.query(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), 2))
    }
    @Test fun `expense sign units and posting date match school frontend`() {
        val result = CampusCardTransactions.parse(body(), 1)
        assertEquals("-12.05", result.entries.single().amountText)
        assertEquals(3450L, result.entries.single().balanceCents)
        assertEquals("2026-10-02 00:00:00", result.entries.single().postedAt)
        assertEquals(1, result.pages)
    }
    @Test fun `income sign and fractional yuan are exact`() {
        assertEquals("+0.01", CampusCardTransactions.parse(body(record.replace("1205", "1").replace("\"2\"", "\"1\"")), 1).entries.single().amountText)
    }
    @Test fun `explicit empty page is valid`() {
        assertTrue(CampusCardTransactions.parse("""{"code":200,"data":{"records":[],"pages":0,"total":0}}""", 1).entries.isEmpty())
    }
    @Test fun `malformed payload is never an empty history`() {
        listOf("""{"code":200,"data":null}""", """{"code":200,"data":{"pages":0,"total":0}}""",
            body().replace("\"tranamt\":\"1205\"", "\"tranamt\":\"1.1\""),
            body().replace("\"current\":1", "\"current\":2"), body().replace("\"total\":1", "\"total\":0"),
            body().replace("\"typeFrom\":\"2\",", ""), "<html>login</html>").forEach {
            assertThrows(it, CampusCardException::class.java) { CampusCardTransactions.parse(it, 1) }
        }
    }
    @Test fun `expired session is separate from business failures and server messages are not exposed`() {
        assertThrows(CampusCardSessionExpiredException::class.java) { CampusCardTransactions.parse("""{"code":401}""", 1) }
        val error = assertThrows(CampusCardException::class.java) { CampusCardTransactions.parse("""{"code":500,"msg":"sensitive-fixture"}""", 1) }
        assertFalse(error.message.orEmpty().contains("sensitive-fixture"))
    }
    @Test fun `unbounded and backwards dates are rejected`() {
        val from = LocalDate.parse("2026-10-01")
        assertThrows(IllegalArgumentException::class.java) { CampusCardTransactions.query(from, from.minusDays(1), 1) }
        assertThrows(IllegalArgumentException::class.java) { CampusCardTransactions.query(from, from.plusMonths(4), 1) }
    }
}
