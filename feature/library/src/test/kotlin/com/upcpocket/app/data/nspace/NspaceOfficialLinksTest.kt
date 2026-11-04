package com.upcpocket.app.data.nspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NspaceOfficialLinksTest {

    @Test
    fun `room detail link carries only room and date in the official hash route`() {
        val url = NspaceOfficialLinks.roomDetailUrl("room 1/甲", "2026-11-04")
        assertTrue(url.startsWith("https://libspace.upc.edu.cn/nspace/#/detail?"))
        assertTrue(url.contains("id=room+1%2F%E7%94%B2"))
        assertTrue(url.contains("selectDate=2026-11-04"))
        assertFalse(url.contains("token"))
        assertFalse(url.contains("uid"))
        assertFalse(url.contains("enc"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid date is rejected instead of guessed`() {
        NspaceOfficialLinks.roomDetailUrl("room-1", "11/04/2026")
    }

    @Test
    fun `base is the fixed official nspace page`() {
        assertEquals("libspace.upc.edu.cn", NspaceOfficialLinks.HOST)
        assertEquals("https://libspace.upc.edu.cn/nspace/", NspaceOfficialLinks.BASE)
    }
}
