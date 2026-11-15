package com.upcpocket.app.ui.ecard

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.data.cardrecharge.CardRechargeServiceTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk=[31])
class CardRechargeScreenTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun `school maximum is shown and confirmation proceeds directly to WeChat handoff`() {
        val fixture=CardRechargeServiceTest.Fixture()
        runBlocking { fixture.service.open() }
        rule.setContent { MaterialTheme { CardRechargeScreen(fixture.service,{}, {}) } }
        rule.waitUntil(5_000) { !fixture.service.state.value.busy && fixture.service.state.value.rules!=null }
        rule.onNodeWithText("学校充值范围 ¥ 1–500").assertExists()
        rule.onNodeWithText("准备并核对本人卡").assertDoesNotExist()
        rule.onNodeWithText("充值金额（元）").performTextInput("200")
        rule.onNodeWithText("微信充值").performClick()
        rule.onNodeWithText("确认充值 ¥ 200").assertExists()
        assertEquals(0,fixture.createPosts)
        rule.onNodeWithText("确认并前往微信").performClick()
        // The fixture device has no WeChat. Seeing this error proves dispatch was automatic,
        // after one confirmation, rather than waiting for another launch button.
        rule.waitUntil(10_000) { fixture.service.state.value.message?.contains("未能唤起微信")==true }
        assertEquals(1,fixture.createPosts);assertEquals(1,fixture.payPosts)
        rule.onNodeWithText("唤起微信付款").assertDoesNotExist()
    }
    @Test fun `preset only school rule removes manual amount entry`() {
        val fixture=CardRechargeServiceTest.Fixture();fixture.flag="00000001"
        runBlocking {fixture.service.open()}
        rule.setContent {MaterialTheme {CardRechargeScreen(fixture.service,{}, {})}}
        rule.waitUntil(5_000) {!fixture.service.state.value.busy && fixture.service.state.value.rules!=null}
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("¥ 200").performClick()
        rule.onNodeWithText("微信充值").performClick()
        rule.onNodeWithText("确认充值 ¥ 200").assertExists()
        assertEquals(0,fixture.createPosts)
    }
}
