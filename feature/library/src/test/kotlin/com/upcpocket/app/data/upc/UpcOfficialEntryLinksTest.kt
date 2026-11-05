package com.upcpocket.app.data.upc

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcOfficialEntryLinksTest {

    @Test
    fun `seat and seatengine links carry only stable identifiers`() {
        listOf(UpcOfficialEntryLinks.SEAT, UpcOfficialEntryLinks.SEATENGINE).forEach { raw ->
            val uri = URI(raw)
            assertEquals("https", uri.scheme)
            assertTrue(uri.host.endsWith(".chaoxing.com"))
            assertTrue(uri.query.contains("fidEnc="))
            assertTrue(uri.query.contains("mappId="))
            assertNoOneTimeParams(uri.query)
        }
    }

    @Test
    fun `only fixed chaoxing seat hosts are accepted`() {
        UpcOfficialEntryLinks.chaoxingEntries.forEach {
            assertTrue(UpcOfficialEntryLinks.isAllowedChaoxingHost(it))
        }
        assertFalse(UpcOfficialEntryLinks.isAllowedChaoxingHost("http://office.chaoxing.com/x"))
        assertFalse(UpcOfficialEntryLinks.isAllowedChaoxingHost("https://example.com/x"))
        assertFalse(UpcOfficialEntryLinks.isAllowedChaoxingHost("https://reserve.chaoxing.com/x"))
        assertFalse(UpcOfficialEntryLinks.isAllowedChaoxingHost("https://chaoxing.com.evil.test/x"))
    }

    private fun queryKeys(query: String): Set<String> =
        query.split('&').map { it.substringBefore('=') }.toSet()

    private fun assertNoOneTimeParams(query: String) {
        val keys = queryKeys(query)
        listOf(
            "uid",
            "enc",
            "code",
            "state",
            "wfwEnc",
            "wfw_token",
            "wxAppid",
        ).forEach { assertFalse("不应包含 $it", it in keys) }
    }
}
