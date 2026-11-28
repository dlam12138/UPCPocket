package com.upcpocket.app.ui.ecard

import android.app.Application
import com.upcpocket.app.data.cardrecharge.CardRechargeService
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CardRechargeWeChatLauncherTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `account cleanup removes legacy payment markers without launching a payment app`() {
        val root = temp.newFolder()
        val legacy = File(root, "card-recharge").apply { mkdirs() }
        File(legacy, "pending.enc").writeText("old encrypted marker")
        File(legacy, "pending.enc.bak").writeText("old backup")

        assertTrue(CardRechargeService.clearStoredData(root))
        assertFalse(legacy.exists())
    }
}
