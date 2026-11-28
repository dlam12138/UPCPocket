package com.upcpocket.app.ui.ecard

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.card.CardBackendTransaction
import com.upcpocket.app.card.FakeCardBackendDataSource
import com.upcpocket.app.data.cardhistory.CardHistoryClient
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], qualifiers = "w360dp-h1200dp")
class CardHistoryScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(account: FakeAccountIdentity, backend: FakeCardBackendDataSource) {
        rule.setContent {
            MaterialTheme {
                CardHistoryScreenContent(CardHistoryClient(account, backend), back = {}, accountSettings = {})
            }
        }
        rule.waitForIdle()
    }

    private fun waitQueryEnabled() {
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText("查询流水") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun managedHistoryUsesBackendAndHasNoSchoolLoginAction() {
        val account = FakeAccountIdentity().also { it.bind() }
        val backend = FakeCardBackendDataSource().apply {
            transactionRows = listOf(CardBackendTransaction("row-1", "食堂消费", "2026-11-01 12:00", -820))
            expenseFen = 820
        }
        render(account, backend)

        waitQueryEnabled()
        rule.onNodeWithText("查询流水").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("食堂消费").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("食堂消费").assertExists()
        rule.onNodeWithText("登录并查询").assertDoesNotExist()
        rule.onNodeWithText("网页登录").assertDoesNotExist()
        rule.onNodeWithText("校方托管账号").assertExists()
    }

    @Test
    fun moduleDisabledShowsReasonAndKeepsSamePageRows() {
        val account = FakeAccountIdentity().also { it.bind() }
        val backend = FakeCardBackendDataSource().apply {
            transactionRows = listOf(CardBackendTransaction("row-1", "食堂消费", "2026-11-01 12:00", -820))
        }
        render(account, backend)
        waitQueryEnabled()
        rule.onNodeWithText("查询流水").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("食堂消费").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("食堂消费").assertExists()

        backend.transactionsFailure = CardBackendException(CardBackendFailureCode.MODULE_DISABLED)
        waitQueryEnabled()
        rule.onNodeWithText("查询流水").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("校方暂未开放校园卡流水；本页已有结果已保留。", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("食堂消费").assertExists()
        rule.onNodeWithText("校方暂未开放校园卡流水；本页已有结果已保留。", substring = true).assertExists()
    }
}
