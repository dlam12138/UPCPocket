package com.upcpocket.app.ui.ecard

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.card.CardBalanceService
import com.upcpocket.app.card.CardInputStore
import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class DirectCardBalanceSectionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    private val id = "2099000999"
    private val otherId = "2099000124"

    private class MemoryInput : CardInputStore {
        override val studentId: Flow<String> = MutableStateFlow("")
        override suspend fun save(studentId: String) = Unit
        override suspend fun clear() = Unit
    }

    private fun service(
        account: FakeAccountIdentity,
        directory: File = temp.newFolder(),
        onRequest: (String) -> Unit = {},
    ): CardBalanceService = serviceWithDirectory(account, directory) { form ->
        val requested = java.net.URLDecoder
            .decode(form.substringAfter("sno=").substringBefore("&"), "UTF-8")
        onRequest(requested)
        """{"retcode":"0","card":[{"sno":"$requested","db_balance":"12345","unsettle_amount":"678"}]}"""
    }

    private fun serviceWithDirectory(
        account: FakeAccountIdentity,
        directory: File,
        request: suspend (String) -> String,
    ): CardBalanceService = CardBalanceService(
        repositoryProvider = {
            DirectCardBalanceRepository(DirectCardBalanceStore(directory), account, request = request)
        },
        account = account,
        legacyInput = MemoryInput(),
        onSnapshotChanged = {},
    )

    private fun render(service: CardBalanceService) {
        rule.setContent {
            MaterialTheme {
                DirectCardBalanceSection(service = service, onOpenAccountSettings = {})
            }
        }
        rule.waitForIdle()
    }

    private fun waitEnabled(tag: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun unboundPageShowsAccountGuidanceAndNoEditableTarget() {
        var requests = 0
        val service = service(FakeAccountIdentity()) { requests++ }
        render(service)

        rule.onNodeWithTag("card_binding_status").assertTextContains("请先设置学校账号", substring = true)
        rule.onNodeWithTag("card_query").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(0, requests)
    }

    @Test
    fun boundPageQueriesCurrentAccountWithoutSnapshotAndSwitchingHidesOldValue() {
        val account = FakeAccountIdentity().also { it.bind(studentId = id) }
        val requested = mutableListOf<String>()
        val service = service(account) { requested += it }
        render(service)

        rule.onNodeWithTag("card_binding_status").assertTextContains("20••••99", substring = true)
        waitEnabled("card_query")
        rule.onNodeWithTag("card_query").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("card_snapshot").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("123.45", substring = true).assertExists()
        rule.onNodeWithText("本次成功读取", substring = true).assertExists()
        assertEquals(listOf(id), requested)

        // 换号立即撤下旧数值；新号尚未成功查询，不能先显示旧余额。
        account.bind(
            accountId = "acct_test_account_b",
            studentId = otherId,
            generation = 2,
        )
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("card_snapshot").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("card_binding_status").assertTextContains("20••••24", substring = true)
    }

    @Test
    fun clearCacheKeepsAccountBindingAndDoesNotQuery() {
        val account = FakeAccountIdentity().also { it.bind(studentId = id) }
        var requests = 0
        val service = service(account) { requests++ }
        render(service)

        waitEnabled("card_query")
        rule.onNodeWithTag("card_query").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("card_snapshot").fetchSemanticsNodes().isNotEmpty()
        }
        waitEnabled("card_clear_cache")
        rule.onNodeWithTag("card_clear_cache")
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("card_snapshot").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("card_binding_status").assertTextContains("20••••99", substring = true)
        rule.onNodeWithTag("card_query").assertExists()
        assertEquals(1, requests)
    }

    @Test
    fun failedQueryKeepsSameAccountHistoryWithExplicitHistoricalLabel() {
        val account = FakeAccountIdentity().also { it.bind(studentId = id) }
        var fail = false
        val service = serviceWithDirectory(account, temp.newFolder()) { form ->
            if (fail) error("synthetic offline")
            val requested = java.net.URLDecoder
                .decode(form.substringAfter("sno=").substringBefore("&"), "UTF-8")
            """{"retcode":"0","card":[{"sno":"$requested","db_balance":"12345","unsettle_amount":"678"}]}"""
        }
        render(service)

        waitEnabled("card_query")
        rule.onNodeWithTag("card_query").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("card_snapshot").fetchSemanticsNodes().isNotEmpty()
        }

        fail = true
        waitEnabled("card_query")
        rule.onNodeWithTag("card_query").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("card_error").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("历史缓存：本次查询失败", substring = true).assertExists()
        rule.onNodeWithText("123.45", substring = true).assertExists()
        // 失败仍显示同一账号的历史值，且不能把本次失败当成新快照。
        rule.onNodeWithTag("card_binding_status").assertTextContains("20••••99", substring = true)
    }

    @Test
    fun failedCacheClearHidesOldValueAndLeavesRetryAvailable() {
        val account = FakeAccountIdentity().also { it.bind(studentId = id) }
        val directory = temp.newFolder()
        val service = service(account, directory = directory)
        render(service)

        rule.onNodeWithTag("card_query").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("card_snapshot").fetchSemanticsNodes().isNotEmpty()
        }

        // 用非空目录替换 v2，使删除失败；模拟清理失败而不是成功。
        val v2 = File(directory, "card-balance-v2.json")
        assertTrue(v2.delete())
        assertTrue(v2.mkdirs())
        val blocker = File(v2, "blocker")
        blocker.writeText("x")
        try {
            waitEnabled("card_clear_cache")
            rule.onNodeWithTag("card_clear_cache")
                .performSemanticsAction(SemanticsActions.OnClick)
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("card_error").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("card_error")
                .assertTextContains("余额缓存清除未完成", substring = true)
            rule.onAllNodesWithTag("card_snapshot").assertCountEquals(0)
            rule.onNodeWithTag("card_binding_status")
                .assertTextContains("20••••99", substring = true)
            // 失败后仍保留重试入口；账号没有被删除。
            rule.onNodeWithTag("card_clear_cache").assertExists()
        } finally {
            blocker.delete()
            v2.deleteRecursively()
        }
    }
}
