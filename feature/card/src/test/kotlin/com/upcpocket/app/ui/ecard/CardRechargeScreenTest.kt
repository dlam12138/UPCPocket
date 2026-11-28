package com.upcpocket.app.ui.ecard

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class CardRechargeScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun guideShowsOfficialChannelAndNoLocalCheckoutControls() {
        rule.setContent { MaterialTheme { CardRechargeGuideScreen(back = {}, openOfficialPortal = null) } }

        rule.onNodeWithText("请通过学校官方渠道为校园卡充值。").assertExists()
        rule.onNodeWithText("请从学校官方应用或门户进入一卡通服务。", substring = true).assertExists()
        rule.onNodeWithText("提交充值").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun controlledHostEntryIsOpenedOnlyByUserAction() {
        var opened = 0
        rule.setContent {
            MaterialTheme {
                CardRechargeGuideScreen(back = {}, openOfficialPortal = { opened++ })
            }
        }
        rule.onNodeWithText("打开数字石大官方门户").assertExists()
        assertEquals(0, opened)
        rule.onNodeWithText("打开数字石大官方门户").performClick()
        assertEquals(1, opened)
    }
}
