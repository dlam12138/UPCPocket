package com.upcpocket.app.data.chaoxing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaoxingWebCookiesTest {

    @Test
    fun `clear scopes expires every observed cookie without touching values in assertions`() {
        val writes = mutableListOf<String>()
        val jar = object : ChaoxingWebCookies.CookieJar {
            override fun getCookie(url: String): String =
                "oa_uid=test; oa_deptid=e32f8fbde51f9dba"

            override fun setCookie(url: String, cookie: String) {
                writes += cookie
            }

            override fun flush() = Unit
        }

        val names = ChaoxingWebCookies.clearScopes(jar)

        assertEquals(setOf("oa_uid", "oa_deptid"), names)
        assertTrue(writes.isNotEmpty())
        assertTrue(writes.all { it.contains("Max-Age=0") })
        assertTrue(writes.any { it.contains("Domain=.chaoxing.com") })
    }
}
