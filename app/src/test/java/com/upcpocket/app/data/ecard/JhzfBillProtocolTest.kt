package com.upcpocket.app.data.ecard

import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class JhzfBillProtocolTest {
    private val month = YearMonth.of(2024, 2)
    private val record = """{"sign_tranamt":"-880","occtime":"20240229172300","tranname":"虚构消费","mercname":"虚构商户","acctype":"###"}"""
    private fun body(records: String = record, next: String = "0") =
        """{"retcode":"0","total":[$records],"nextpage":$next}"""

    @Test fun `form preserves empty account type and leap month with distinct card account`() {
        assertEquals("sno=2099000123&xxbh=synjones&curpage=1&pagesize=10&account=987654&acctype=&query_start=20240201&query_end=20240229",
            JhzfBillProtocol.form("2099000123", "987654", month, 1))
    }

    @Test fun `invalid identity account and page rejected before transport`() {
        assertThrows(IllegalArgumentException::class.java) { JhzfBillProtocol.form("bad", "987654", month, 1) }
        assertThrows(IllegalArgumentException::class.java) { JhzfBillProtocol.form("2099000123", "a&b", month, 1) }
        assertThrows(IllegalArgumentException::class.java) { JhzfBillProtocol.form("2099000123", "987654", month, 0) }
    }

    @Test fun `signed cents kept exact with zero and income without direction guessing`() {
        assertEquals("-8.80", JhzfBillProtocol.parse(body(), month, 1).entries.single().amountText)
        assertEquals("+30.00", JhzfBillProtocol.parse(body(record.replace("-880", "+3000")), month, 1).entries.single().amountText)
        assertEquals("0.00", JhzfBillProtocol.parse(body(record.replace("-880", "0")), month, 1).entries.single().amountText)
    }

    @Test fun `identical entries are retained instead of dropping legitimate repeated transactions`() {
        assertEquals(2, JhzfBillProtocol.parse(body("$record,$record"), month, 1).entries.size)
    }

    @Test fun `next page is within month and zero only completes that month`() {
        assertEquals(2, JhzfBillProtocol.parse(body(next = "2"), month, 1).nextPage)
        assertTrue(JhzfBillProtocol.parse(body(), month, 1).monthComplete)
        assertTrue(JhzfBillProtocol.parse(body(""), month, 1).entries.isEmpty())
        listOf("1", "3", "-1", "null").forEach { next ->
            assertThrows(CampusCardException::class.java) { JhzfBillProtocol.parse(body(next = next), month, 1) }
        }
        assertThrows(CampusCardException::class.java) { JhzfBillProtocol.parse(body("", "2"), month, 1) }
    }

    @Test fun `broken dates wrong month money overflow or missing fields fail entire page`() {
        listOf(
            record.replace("20240229172300", "20240230172300"),
            record.replace("20240229172300", "20240301172300"),
            record.replace("-880", "8.8"), record.replace("-880", "999999999999999999999999"),
            record.replace("\"mercname\":\"虚构商户\",", ""),
            record.replace("\"acctype\":\"###\"", "\"acctype\":null"),
        ).forEach { bad -> assertThrows(CampusCardException::class.java) { JhzfBillProtocol.parse(body("$record,$bad"), month, 1) } }
    }

    @Test fun `HTML absent total and failed business never become empty successful history`() {
        listOf("<html>无效token</html>", "{}", """{"retcode":0,"nextpage":0}""", body().replace("\"0\"", "\"60002\""),
            body().replace("[$record]", "null"), body(List(11) { record }.joinToString(","))).forEach {
            assertThrows(CampusCardException::class.java) { JhzfBillProtocol.parse(it, month, 1) }
        }
    }

    @Test fun `server identity and errors excluded from exceptions`() {
        val failure = assertThrows(CampusCardException::class.java) {
            JhzfBillProtocol.parse("""{"retcode":"1","errmsg":"private-fixture-value"}""", month, 1)
        }
        assertFalse(failure.message.orEmpty().contains("private-fixture"))
        assertFalse(failure is CampusCardSessionExpiredException)
    }
}
