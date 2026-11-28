package com.upcpocket.app.ui.ecard

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.card.CardBackendException
import com.upcpocket.app.card.CardBackendFailureCode
import com.upcpocket.app.card.CardBalanceService
import com.upcpocket.app.card.FakeCardBackendDataSource
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class DirectCardBalanceSectionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun service(account: FakeAccountIdentity, backend: FakeCardBackendDataSource) =
        CardBalanceService(rule.activity, account, backend) {}

    private fun render(service: CardBalanceService) {
        rule.setContent { MaterialTheme { DirectCardBalanceSection(service, onOpenAccountSettings = {}) } }
        rule.waitForIdle()
    }

    private fun waitEnabled(tag: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun unboundPageShowsManagedAccountGuidanceWithoutEditableIdentity() {
        val backend = FakeCardBackendDataSource()
        val service = service(FakeAccountIdentity(), backend)
        render(service)

        rule.onNodeWithTag("card_binding_status").assertTextContains("校方托管账号", substring = true)
        rule.onNodeWithTag("card_query").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(0, backend.balanceCalls)
    }

    @Test
    fun activeManagedAccountReadsBalanceWithoutShowingStudentIdentity() = runBlocking {
        val backend = FakeCardBackendDataSource()
        val account = FakeAccountIdentity().also { it.bind() }
        val service = service(account, backend)
        service.clearCache()
        render(service)

        rule.onNodeWithTag("card_binding_status").assertTextContains("校方托管账号已绑定", substring = true)
        waitEnabled("card_query")
        rule.onNodeWithTag("card_query").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("card_snapshot").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("130.23", substring = true).assertExists()
        rule.onNodeWithText("过渡余额 6.78 元").assertExists()
        rule.onAllNodesWithText("2099000999").assertCountEquals(0)
        assertEquals(1, backend.balanceCalls)
        service.clearCache()
    }

    @Test
    fun moduleDisabledRetainsSameAccountBalanceAndShowsSpecificReason() = runBlocking {
        val backend = FakeCardBackendDataSource()
        val service = service(FakeAccountIdentity().also { it.bind() }, backend)
        service.clearCache()
        render(service)
        waitEnabled("card_query")
        rule.onNodeWithTag("card_query").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("card_snapshot").fetchSemanticsNodes().isNotEmpty() }

        backend.balanceFailure = CardBackendException(CardBackendFailureCode.MODULE_DISABLED)
        waitEnabled("card_query")
        rule.onNodeWithTag("card_query").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("card_error").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("card_error").assertTextContains("暂未开放", substring = true)
        rule.onNodeWithText("130.23", substring = true).assertExists()
        service.clearCache()
    }
}
