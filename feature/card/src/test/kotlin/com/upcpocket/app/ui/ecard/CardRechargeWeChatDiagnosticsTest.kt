package com.upcpocket.app.ui.ecard

import android.app.Application
import com.upcpocket.app.data.cardrecharge.CardRechargeService
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CardRechargeWeChatDiagnosticsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `cleanup is idempotent when no local checkout record remains`() {
        val root = temp.newFolder()
        assertTrue(CardRechargeService.clearStoredData(root))
        assertTrue(CardRechargeService.clearStoredData(root))
    }
}
