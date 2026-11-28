package com.upcpocket.app.data.cardrecharge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RechargePendingStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `cleanup removes legacy pending marker base and atomic siblings`() {
        val root = temp.newFolder()
        val records = File(root, "card-recharge").apply { mkdirs() }
        val base = File(records, "legacy.enc").apply { writeText("encrypted") }
        val backup = File(base.path + ".bak").apply { writeText("backup") }
        val newFile = File(base.path + ".new").apply { writeText("new") }

        assertTrue(CardRechargeService.clearStoredData(root))
        assertFalse(records.exists())
        assertFalse(base.exists())
        assertFalse(backup.exists())
        assertFalse(newFile.exists())
    }
}
