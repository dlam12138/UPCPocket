package com.upcpocket.app.timetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableCookieMergeTest {

    @Test
    fun specificPathValueWinsOverRootDuplicate() {
        val merged = mergeCookieHeadersByTarget(
            listOf(
                "sid=root; token=path",
                "sid=root",
            ),
        )!!
        assertTrue(merged.contains("sid=root"))
        assertTrue(merged.contains("token=path"))
        assertEquals(1, merged.split(';').count { it.trim().startsWith("sid=") })
    }

    @Test
    fun valuePresentOnlyInSpecificHeaderIsNotReplacedByRoot() {
        val merged = mergeCookieHeadersByTarget(
            listOf(
                "sid=specific; sid=root",
                "sid=root",
            ),
        )!!
        assertEquals("sid=specific", merged)
    }

    @Test
    fun rootOnlyCookieIsKept() {
        val merged = mergeCookieHeadersByTarget(
            listOf(
                "sid=root",
                "sid=root",
            ),
        )!!
        assertEquals("sid=root", merged)
    }

    @Test
    fun emptyHeadersBecomeNull() {
        assertNull(mergeCookieHeadersByTarget(listOf(null, " ", null)))
    }
}
