package com.upcpocket.app.data.cardhistory

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CardHistoryProtocolTest {
    @Test fun `only exact school origins and callback may navigate`() {
        assertTrue(CardHistoryProtocol.allowed("https://cas.upc.edu.cn/cas/login?service=test"))
        assertTrue(CardHistoryProtocol.allowed("https://mb-xyk.upc.edu.cn/plat/"))
        assertFalse(CardHistoryProtocol.allowed("http://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn?ticket=sample"))
        assertFalse(CardHistoryProtocol.allowed("http://mb-xyk.upc.edu.cn/plat/"))
        assertFalse(CardHistoryProtocol.allowed("http://cas.upc.edu.cn/cas/login"))
        assertFalse(CardHistoryProtocol.allowed("https://mb-xyk.upc.edu.cn.attacker.example/plat/"))
        assertFalse(CardHistoryProtocol.allowed("https://mb-xyk.upc.edu.cn@attacker.example/plat/"))
        assertFalse(CardHistoryProtocol.allowed("https://mb-xyk.upc.edu.cn:444/plat/"))
    }

    @Test fun `token capture excludes cas and callback pages`() {
        assertTrue(CardHistoryProtocol.tokenPage("https://mb-xyk.upc.edu.cn/plat/?name=loginTransit"))
        assertTrue(CardHistoryProtocol.tokenPage("https://mb-xyk.upc.edu.cn/campus-card/billing/list"))
        assertFalse(CardHistoryProtocol.tokenPage("https://cas.upc.edu.cn/cas/login"))
        assertFalse(CardHistoryProtocol.tokenPage("https://mb-xyk.upc.edu.cn/berserker-auth/cas/login/wislearn"))
        assertFalse(CardHistoryProtocol.tokenPage("http://mb-xyk.upc.edu.cn/plat/"))
        assertFalse(CardHistoryProtocol.tokenPage("https://evil.example/plat/"))
    }

    @Test fun `identity must match previously bound card`() {
        CardHistoryProtocol.verifyIdentity("""{"code":200,"data":{"user":{"sno":"2099999999"}}}""", "2099999999")
    }

    @Test(expected = IOException::class) fun `different logged in account is rejected`() {
        CardHistoryProtocol.verifyIdentity("""{"code":200,"data":{"sno":"2099999998"}}""", "2099999999")
    }

    @Test(expected = IOException::class) fun `successful envelope alone is not identity verification`() {
        CardHistoryProtocol.verifyIdentity("""{"code":200,"data":{"id":"2099999999"}}""", "2099999999")
    }

    @Test fun `income and expense use integer fen rather than unsigned or double values`() {
        val result = CardHistoryProtocol.parseRows(sample)
        assertEquals(5000L, result.rows[0].amountFen)
        assertEquals(-820L, result.rows[1].amountFen)
        assertEquals(2, result.total)
        assertEquals("演示消费", result.rows[1].title)
    }

    @Test fun `integral decimal amounts keep exact fen without rounding`() {
        val result = CardHistoryProtocol.parseRows(
            sample.replace("\"tranamt\":5000,", "\"tranamt\":5000.0,").replace("\"tranamt\":820,", "\"tranamt\":820.00,"),
        )
        assertEquals(5000L, result.rows[0].amountFen)
        assertEquals(-820L, result.rows[1].amountFen)
    }

    @Test(expected = IOException::class) fun `fractional amounts are rejected rather than rounded`() {
        CardHistoryProtocol.parseRows(sample.replace("\"tranamt\":820,", "\"tranamt\":820.5,"))
    }

    @Test(expected = IOException::class) fun `unknown direction is rejected instead of becoming an expense`() {
        CardHistoryProtocol.parseRows(sample.replace("\"typeFrom\":\"2\",", "\"typeFrom\":\"3\","))
    }

    @Test(expected = IOException::class) fun `zero direction is rejected instead of becoming an expense`() {
        CardHistoryProtocol.parseRows(sample.replace("\"typeFrom\":\"2\",", "\"typeFrom\":\"0\","))
    }

    @Test(expected = IOException::class) fun `blank direction is rejected instead of becoming an expense`() {
        CardHistoryProtocol.parseRows(sample.replace("\"typeFrom\":\"2\",", "\"typeFrom\":\"\",",))
    }

    @Test(expected = IOException::class) fun `missing amount never becomes zero`() {
        CardHistoryProtocol.parseRows(sample.replace("\"tranamt\":820,", ""))
    }

    @Test(expected = IOException::class) fun `missing direction is rejected`() {
        CardHistoryProtocol.parseRows(sample.replace("\"typeFrom\":\"2\",", ""))
    }

    @Test(expected = IOException::class) fun `duplicate orders are rejected`() {
        CardHistoryProtocol.parseRows(sample.replace("example-b", "example-a"))
    }

    @Test(expected = IOException::class) fun `auth error cannot become successful empty records`() {
        CardHistoryProtocol.parseRows("""{"code":401,"data":{"records":[],"total":0,"pages":0}}""")
    }

    @Test fun `explicit empty list remains valid without invented rows`() {
        assertTrue(CardHistoryProtocol.parseRows("""{"code":200,"data":{"records":[],"total":0,"pages":0}}""").rows.isEmpty())
    }

    private val sample = """{"code":200,"data":{"total":2,"pages":1,"records":[
        {"orderId":"example-a","resume":"演示充值","tranamt":5000,"typeFrom":"1","jndatetimeStr":"2026-11-12 12:00:00"},
        {"orderId":"example-b","resume":"演示消费","tranamt":820,"typeFrom":"2","effectdateStr":"2026-11-12 11:00:00"}
    ]}}"""
}
