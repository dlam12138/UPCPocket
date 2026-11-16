package com.upcpocket.app.ui.ecard

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.data.cardrecharge.CardRechargeProtocol
import com.upcpocket.app.data.cardrecharge.CardRechargeServiceTest
import com.upcpocket.app.data.card.CardDiagnosticRecorder
import com.upcpocket.app.platform.diagnostics.DiagnosticDetail
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class CardRechargeScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @After fun detachDiagnostics() { Diagnostics.install(null) }

    private class WeChat(private val available: Boolean = true, private val launches: Boolean = true) : CardRechargeWeChatLauncher {
        var launchCount = 0
        var lastUri: String? = null
        override fun isAvailable() = available
        override fun launch(uri: String): Boolean {
            lastUri = CardRechargeProtocol.wechatUri(uri)
            launchCount++
            return launches
        }
    }

    @Test fun single_click_submits_and_launches_WeChat_without_an_extra_confirmation() {
        val fixture = CardRechargeServiceTest.Fixture()
        val wechat = WeChat()
        runBlocking { fixture.service.open() }
        rule.setContent { MaterialTheme { CardRechargeScreen(fixture.service, {}, {}, wechat) } }
        rule.waitUntil(5_000) { !fixture.service.state.value.busy && fixture.service.state.value.rules != null }
        rule.onNodeWithText("充值范围 ¥ 1–500").assertExists()
        rule.onNodeWithTag("recharge_amount").performTextInput("200")
        rule.onNodeWithText("微信充值 ¥ 200").assertExists()
        rule.onNodeWithTag("recharge_submit").performClick()
        rule.onNodeWithText("确认充值 ¥ 200").assertDoesNotExist()
        rule.onNodeWithText("确认并前往微信").assertDoesNotExist()
        rule.waitUntil(10_000) { wechat.launchCount == 1 }
        assertEquals(1, fixture.createPosts)
        assertEquals(1, fixture.payPosts)
        assertEquals("weixin://wap/pay?fixture=1", wechat.lastUri)
        rule.onNodeWithTag("recharge_submit").assertDoesNotExist()
        rule.onNodeWithText("刷新支付状态").assertExists()
    }

    @Test fun missing_WeChat_stops_before_creating_any_order() {
        val diagnostics = CardDiagnosticRecorder()
        Diagnostics.install(diagnostics)
        val fixture = CardRechargeServiceTest.Fixture()
        runBlocking { fixture.service.open() }
        // Default Android launcher checks PackageManager; this device has no WeChat handler.
        rule.setContent { MaterialTheme { CardRechargeScreen(fixture.service, {}, {}) } }
        rule.waitUntil(5_000) { !fixture.service.state.value.busy && fixture.service.state.value.rules != null }
        rule.onNodeWithTag("recharge_amount").performTextInput("20")
        rule.onNodeWithTag("recharge_submit").performClick()
        rule.onNodeWithText("未找到可用的微信，请安装或启用后重试。").assertExists()
        assertEquals(0, fixture.createPosts)
        assertEquals(0, fixture.payPosts)
        assertNull(fixture.store.value)
        assertEquals(DiagnosticResult.BLOCKED, diagnostics.terminals(DiagnosticDetail.CARD_WECHAT_AVAILABILITY).single().result)
        assertTrue(diagnostics.forDetail(DiagnosticDetail.CARD_ORDER_CREATE).isEmpty())
        rule.onNodeWithTag("recharge_submit").assertIsEnabled()
    }

    @Test fun preset_only_rule_submits_the_selected_amount_directly() {
        val fixture = CardRechargeServiceTest.Fixture()
        fixture.flag = "00000001"
        val wechat = WeChat()
        runBlocking { fixture.service.open() }
        rule.setContent { MaterialTheme { CardRechargeScreen(fixture.service, {}, {}, wechat) } }
        rule.waitUntil(5_000) { !fixture.service.state.value.busy && fixture.service.state.value.rules != null }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("¥ 200").performClick()
        rule.onNodeWithTag("recharge_submit").performClick()
        rule.waitUntil(10_000) { wechat.launchCount == 1 }
        assertEquals("200", fixture.store.value!!.yuan)
        assertEquals(1, fixture.createPosts)
    }

    @Test fun invalid_amount_does_not_create_an_order_or_launch() {
        val fixture = CardRechargeServiceTest.Fixture()
        val wechat = WeChat()
        rule.setContent { MaterialTheme { CardRechargeScreen(fixture.service, {}, {}, wechat) } }
        rule.waitUntil(5_000) { !fixture.service.state.value.busy && fixture.service.state.value.rules != null }
        rule.onNodeWithTag("recharge_amount").performTextInput("500.01")
        rule.onNodeWithTag("recharge_submit").performClick()
        rule.onNodeWithText("请输入符合学校规则的金额").assertExists()
        assertEquals(0, fixture.createPosts)
        assertEquals(0, wechat.launchCount)
    }

    @Test fun failed_launch_retains_the_existing_order_and_recovery_confirmation() {
        val fixture = CardRechargeServiceTest.Fixture()
        val wechat = WeChat(launches = false)
        rule.setContent { MaterialTheme { CardRechargeScreen(fixture.service, {}, {}, wechat) } }
        rule.waitUntil(5_000) { !fixture.service.state.value.busy && fixture.service.state.value.rules != null }
        rule.onNodeWithText("¥ 20").performClick()
        rule.onNodeWithTag("recharge_submit").performClick()
        rule.waitUntil(10_000) { fixture.service.state.value.message?.contains("未能唤起微信") == true }
        assertNotNull(fixture.store.value)
        rule.onNodeWithTag("recharge_submit").assertDoesNotExist()
        rule.onNodeWithText("已核对官方记录，处理未完成订单").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        rule.onNodeWithText("已核对学校与微信记录？").assertExists()
        rule.onNodeWithText("返回核对").performClick()
        assertNotNull(fixture.store.value)
        assertEquals(1, fixture.createPosts)
        assertEquals(1, wechat.launchCount)
    }
}
