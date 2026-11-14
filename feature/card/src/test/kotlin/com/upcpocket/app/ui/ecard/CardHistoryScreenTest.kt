package com.upcpocket.app.ui.ecard

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.account.api.SchoolStudentIdentity
import com.upcpocket.app.data.cardhistory.*
import com.upcpocket.app.data.ecard.FakeAccountIdentity
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class CardHistoryScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val interactive = object : CardHistoryInteractiveAuthenticator {
        override val entryUrl = "https://synthetic.invalid/login"
        override fun inspectNavigation(url: String, method: String, isMainFrame: Boolean) = CardHistoryWebNavigation.Allow
        override suspend fun completeResult(identity: SchoolStudentIdentity, resultUrl: String) = "synthetic-session"
    }

    private fun render(
        account: FakeAccountIdentity = FakeAccountIdentity().also { it.bind() },
        onRequest: (Request) -> Unit = {},
        authenticate: suspend () -> String = { throw CardHistoryException(CardHistoryFailure.NETWORK) },
    ) {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            onRequest(chain.request())
            val body = when (chain.request().url.encodedPath) {
                "/berserker-base/user" -> """{"code":200,"data":{"sno":"2099000999"}}"""
                "/berserker-search/statistics/turnover/count" -> """{"code":200,"data":{"income":0,"expenses":0}}"""
                else -> """{"code":200,"data":{"current":1,"pages":0,"total":0,"records":[]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = CardHistoryClient(account, CardHistoryAuthenticator { authenticate() }, http, interactive)
        rule.setContent { MaterialTheme { CardHistoryScreen(client, back = {}, accountSettings = {}) } }
        rule.waitForIdle()
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun webLoginIsAvailableOnInitialBoundPageAndAfterFirstFailure() {
        val attempts = AtomicInteger()
        render { attempts.incrementAndGet(); throw CardHistoryException(CardHistoryFailure.NETWORK) }
        rule.onNodeWithText("网页登录").assertIsEnabled()
        assertEquals(0, attempts.get())
        rule.onNodeWithText("登录并查询").performClick()
        waitForText("学校服务暂时无法连接，请稍后重试。")
        rule.onNodeWithText("网页登录").assertIsEnabled()
        assertEquals(1, attempts.get())
    }

    @Test fun knownChallengeSurvivesBusyRetryAndSubsequentFailure() {
        val attempts = AtomicInteger()
        val retry = CompletableDeferred<Unit>()
        render {
            if (attempts.incrementAndGet() == 1) throw CardHistoryException(CardHistoryFailure.INTERACTION_REQUIRED)
            retry.await()
            throw CardHistoryException(CardHistoryFailure.NETWORK)
        }
        rule.onNodeWithText("登录并查询").performClick()
        waitForText("请在学校页面继续登录。")
        rule.onNodeWithText("网页登录").assertIsEnabled()
        rule.onNodeWithText("登录并查询").performClick()
        rule.waitUntil(5_000) { attempts.get() == 2 }
        rule.onNodeWithText("网页登录").assertIsNotEnabled()
        rule.runOnIdle { retry.complete(Unit) }
        waitForText("学校服务暂时无法连接，请稍后重试。")
        rule.onNodeWithText("网页登录").assertIsEnabled()
    }

    @Test fun successfulNativeQueryClearsKnownChallenge() {
        val attempts = AtomicInteger()
        render {
            if (attempts.incrementAndGet() == 1) throw CardHistoryException(CardHistoryFailure.INTERACTION_REQUIRED)
            "synthetic-session"
        }
        rule.onNodeWithText("登录并查询").performClick()
        waitForText("请在学校页面继续登录。")
        rule.onNodeWithText("登录并查询").performClick()
        waitForText("已加载 0 / 0 条")
        rule.onNodeWithText("网页登录").assertIsEnabled()
        rule.onNodeWithText("请在学校页面继续登录。").assertDoesNotExist()
    }

    @Test fun noAccountOrPendingCleanupCannotOpenWebLogin() {
        val account = FakeAccountIdentity()
        render(account)
        rule.onNodeWithText("网页登录").assertIsNotEnabled()
        rule.onNodeWithText("登录并查询").assertIsNotEnabled()
        rule.runOnIdle { account.bind() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("网页登录") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { account.cleaning() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("网页登录") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun cancellingMonthDraftPreservesAllDatesResultsWithoutRequests() {
        val requests = AtomicInteger()
        render(onRequest = { requests.incrementAndGet() }) { "synthetic-session" }
        rule.onNodeWithText("登录并查询").performClick()
        waitForText("已加载 0 / 0 条")
        val completedRequests = requests.get()

        rule.onNodeWithText("按月").performClick()
        rule.onNodeWithText("${YearMonth.now().year}年").assertExists()
        rule.onNodeWithContentDescription("下一年").performClick()
        rule.onNodeWithText("1月").performClick()
        rule.onNodeWithText("取消").performClick()

        rule.onNodeWithText("全部日期").assertIsSelected()
        rule.onNodeWithText("已加载 0 / 0 条").assertExists()
        rule.onNodeWithText("按月").performClick()
        rule.onNodeWithText("${YearMonth.now().year}年").assertExists()
        rule.onNodeWithText("取消").performClick()
        assertEquals(completedRequests, requests.get())
    }

    @Test fun confirmingMonthWaitsForQueryAndSameMonthPreservesResults() {
        val requests = AtomicInteger()
        render(onRequest = { requests.incrementAndGet() }) { "synthetic-session" }
        val selected = YearMonth.of(YearMonth.now().year + 1, 1)
        rule.onNodeWithText("按月").performClick()
        rule.onNodeWithContentDescription("下一年").performClick()
        rule.onNodeWithText("1月").performClick()
        rule.onNodeWithText("确定").performClick()
        rule.onNodeWithText(selected.toString()).assertIsSelected()
        assertEquals(0, requests.get())

        rule.onNodeWithText("登录并查询").performClick()
        waitForText("已加载 0 / 0 条")
        val completedRequests = requests.get()
        rule.onNodeWithText(selected.toString()).performClick()
        rule.onNodeWithText("1月").assertIsSelected()
        rule.onNodeWithText("确定").performClick()
        rule.onNodeWithText("已加载 0 / 0 条").assertExists()

        rule.onNodeWithText(selected.toString()).performClick()
        rule.onNodeWithText("2月").performClick()
        rule.onNodeWithText("确定").performClick()
        rule.onNodeWithText(selected.plusMonths(1).toString()).assertIsSelected()
        rule.onNodeWithText("已加载 0 / 0 条").assertDoesNotExist()
        assertEquals(completedRequests, requests.get())
    }
}
