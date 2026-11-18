package com.upcpocket.app.account.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentIdFormatTest {
    @Test
    fun `accepts supported complete student id formats`() {
        listOf(
            "12345678",
            "2099999999",
            "1234567890123456",
            "S99999999",
            "Z99999999",
            "M99999999",
            "TS999999999",
            "BY9999999999",
        ).forEach { assertTrue(it, StudentIdFormat.isValid(it)) }
    }

    @Test
    fun `rejects incomplete lowercase or extended student ids`() {
        listOf(
            "1234567",
            "12345678901234567",
            "S9999999",
            "s99999999",
            "TS99999999",
            "BY999999999",
            "S99999999X",
            "TS999999999X",
            "１２３４５６７８",
        ).forEach { assertFalse(it, StudentIdFormat.isValid(it)) }
    }
}
