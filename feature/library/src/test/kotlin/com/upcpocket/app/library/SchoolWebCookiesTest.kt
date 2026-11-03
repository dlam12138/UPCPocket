package com.upcpocket.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolWebCookiesTest {

    @Test
    fun `cookie names ignore empty fragments and values`() {
        assertEquals(
            setOf("SESSION", "cas"),
            SchoolWebCookies.cookieNames(" SESSION=abc; cas=ticket; =; ;"),
        )
        assertTrue(SchoolWebCookies.cookieNames("").isEmpty())
        assertTrue(SchoolWebCookies.cookieNames("   ").isEmpty())
    }
}
