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

    private fun assertNoOneTimeParams(query: String) {
        val keys = query.split('&').map { it.substringBefore('=') }.toSet()
        listOf("uid", "enc", "code", "state", "wfwEnc", "wfw_token")
            .forEach { assertFalse("不应包含 $it", it in keys) }
    }
}
