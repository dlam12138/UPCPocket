package com.upcpocket.app.data.cardrecharge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardRechargeServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `legacy encrypted pending orders are removed during personal data cleanup`() {
        val root = temp.newFolder()
        val directory = File(root, "card-recharge").apply { mkdirs() }
        File(directory, "legacy.enc").writeText("opaque old marker")
        File(directory, "legacy.enc.new").writeText("opaque pending write")

        assertTrue(CardRechargeService.clearStoredData(root))
        assertFalse(directory.exists())
    }
}
