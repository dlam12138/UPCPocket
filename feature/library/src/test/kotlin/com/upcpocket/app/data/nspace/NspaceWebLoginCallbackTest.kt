package com.upcpocket.app.data.nspace

import com.upcpocket.app.ui.library.isAllowedNspaceLoginUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NspaceWebLoginCallbackTest {

    @Test
    fun `query token is accepted on the fixed nspace path`() {
        assertEquals(
            "synthetic-query-token",
            NspaceWebLoginCallback.token(
                "https://libspace.upc.edu.cn/nspace/?token=synthetic-query-token",
            ),
        )
    }

    @Test
    fun `hash route token is accepted and decoded`() {
        assertEquals(
            "synthetic-encoded-token",
            NspaceWebLoginCallback.token(
                "https://libspace.upc.edu.cn/nspace/#/login?token=synthetic-encoded-token",
            ),
        )
    }

    @Test
    fun `external host and wrong path are rejected`() {
        assertNull(
            NspaceWebLoginCallback.token("https://evil.invalid/nspace/?token=secret"),
        )
        assertNull(
            NspaceWebLoginCallback.token("https://libspace.upc.edu.cn/libseat/?token=secret"),
        )
        assertFalse(
            NspaceWebLoginCallback.isAllowedPage("http://libspace.upc.edu.cn/nspace/?token=x"),
        )
    }

    @Test
    fun `uid enc code params are not treated as app token`() {
        val url = "https://libspace.upc.edu.cn/nspace/#/login?uid=x&enc=y&code=z"
        assertTrue(NspaceWebLoginCallback.isAllowedPage(url))
        assertNull(NspaceWebLoginCallback.token(url))
    }

    @Test
    fun `webview allowlist accepts school https and rejects lookalikes`() {
        assertTrue(isAllowedNspaceLoginUrl("https://cas.upc.edu.cn/cas/login?service=x"))
        assertTrue(isAllowedNspaceLoginUrl("https://libspace.upc.edu.cn/nspace/"))
        assertFalse(isAllowedNspaceLoginUrl("http://cas.upc.edu.cn/cas/login"))
        assertFalse(isAllowedNspaceLoginUrl("https://cas.upc.edu.cn.evil.invalid/"))
        assertFalse(isAllowedNspaceLoginUrl("https://cas.upc.edu.cn:8443/"))
    }

    @Test
    fun `blank control and oversized token are rejected`() {
        assertNull(NspaceWebLoginCallback.token("https://libspace.upc.edu.cn/nspace/?token="))
        assertNull(
            NspaceWebLoginCallback.token("https://libspace.upc.edu.cn/nspace/?token=a%0Ab"),
        )
        assertNull(
            NspaceWebLoginCallback.token(
                "https://libspace.upc.edu.cn/nspace/?token=" + "a".repeat(4097),
            ),
        )
    }
}
