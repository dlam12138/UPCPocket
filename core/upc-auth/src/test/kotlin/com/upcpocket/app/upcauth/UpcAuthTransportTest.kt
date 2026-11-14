package com.upcpocket.app.upcauth

import org.junit.Assert.assertFalse
import org.junit.Test

class UpcAuthTransportTest {
    @Test
    fun requestStringFormDoesNotExposeUrlHeadersOrBody() {
        val request = UpcAuthHttpRequest(
            method = "POST",
            url = "https://service.upc.edu.cn/callback?ticket=ST-synthetic-ticket",
            headers = mapOf("Cookie" to "session=synthetic-cookie"),
            body = "password=synthetic-password",
        )

        val safe = request.toString()

        assertFalse(safe.contains("ticket="))
        assertFalse(safe.contains("session="))
        assertFalse(safe.contains("password="))
    }
}
