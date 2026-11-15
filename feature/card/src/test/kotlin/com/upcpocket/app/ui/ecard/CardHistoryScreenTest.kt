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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        historyBody: String? = null,
        responseBody: ((Request) -> String)? = null,
        authenticateFor: (suspend (SchoolStudentIdentity) -> String)? = null,
        onRequestSettledForTest: ((SchoolStudentIdentity) -> Unit)? = null,
        onRowsCommittedForTest: ((SchoolStudentIdentity) -> Unit)? = null,
        authenticate: suspend () -> String = { throw CardHistoryException(CardHistoryFailure.NETWORK) },
    ) {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            onRequest(request)
            val body = responseBody?.invoke(request) ?: when (request.url.encodedPath) {
                "/berserker-base/user" -> """{"code":200,"data":{"sno":"2099000999"}}"""
                "/berserker-search/statistics/turnover/count" -> """{"code":200,"data":{"income":0,"expenses":0}}"""
                else -> historyBody ?: """{"code":200,"data":{"current":1,"pages":0,"total":0,"records":[]}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = CardHistoryClient(
            account,
            CardHistoryAuthenticator { identity -> authenticateFor?.invoke(identity) ?: authenticate() },
            http,
            interactive,
        )
        rule.setContent {
            MaterialTheme {
                CardHistoryScreenContent(
                    client,
                    back = {},
                    accountSettings = {},
                    onRequestSettledForTest = onRequestSettledForTest,
                    onRowsCommittedForTest = onRowsCommittedForTest,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        scrollToHistoryText(text)
    }

    private fun scrollToHistoryText(text: String) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun scrollHistoryToTop() {
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
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
        scrollToHistoryText("暂无流水")
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

    @Test fun zeroRowsAndSearchMissHaveDifferentEmptyStates() {
        render(
            historyBody = """{"code":200,"data":{"current":1,"pages":1,"total":1,"records":[{"orderId":"synthetic-order","resume":"合成餐饮消费","tranamt":1250,"typeFrom":"2","jndatetimeStr":"2026-11-15 12:00"}]}}""",
        ) { "synthetic-session" }
        rule.onNodeWithText("登录并查询").performClick()
        waitForText("已加载 1 / 1 条")
        scrollToHistoryText("合成餐饮消费")
        scrollToHistoryText("搜索已加载记录")
        rule.onNodeWithText("搜索已加载记录").performTextInput("没有这条")
        waitForText("没有匹配的流水")
        rule.onNodeWithContentDescription("清空搜索").performScrollTo().assertIsDisplayed().performClick()
        scrollToHistoryText("合成餐饮消费")
    }

    @Test fun lateAccountASummaryCannotClearAccountBHistoryOrSummary() {
        val accountAStudentId = "2099000001"
        val accountBStudentId = "2099000002"
        val account = FakeAccountIdentity().also {
            it.bind(accountId = "acct_test_account_a", studentId = accountAStudentId, generation = 1)
        }
        val accountASummaryEntered = CountDownLatch(1)
        val accountARowsCommitted = CountDownLatch(1)
        val releaseAccountASummary = CountDownLatch(1)
        val accountARequestSettled = CountDownLatch(1)
        val accountBRequestSettled = CountDownLatch(1)

        render(
            account = account,
            authenticateFor = { identity ->
                if (identity.studentId == accountAStudentId) "synthetic-session-a" else "synthetic-session-b"
            },
            onRequestSettledForTest = { identity ->
                when (identity.studentId) {
                    accountAStudentId -> accountARequestSettled.countDown()
                    accountBStudentId -> accountBRequestSettled.countDown()
                }
            },
            onRowsCommittedForTest = { identity ->
                if (identity.studentId == accountAStudentId) accountARowsCommitted.countDown()
            },
            responseBody = { request ->
                val session = request.header("synjones-auth").orEmpty().substringAfter("bearer ")
                when (request.url.encodedPath) {
                    "/berserker-base/user" -> {
                        val studentId = if (session == "synthetic-session-a") accountAStudentId else accountBStudentId
                        """{"code":200,"data":{"sno":"$studentId"}}"""
                    }
                    "/berserker-search/statistics/turnover/count" -> {
                        if (session == "synthetic-session-a") {
                            accountASummaryEntered.countDown()
                            check(releaseAccountASummary.await(10, TimeUnit.SECONDS))
                            """{"code":200,"data":{"income":11100,"expenses":0}}"""
                        } else {
                            """{"code":200,"data":{"income":22200,"expenses":0}}"""
                        }
                    }
                    else -> {
                        val title = if (session == "synthetic-session-a") "A 合成流水" else "B 合成流水"
                        val id = if (session == "synthetic-session-a") "synthetic-a" else "synthetic-b"
                        """{"code":200,"data":{"current":1,"pages":1,"total":1,"records":[{"orderId":"$id","resume":"$title","tranamt":100,"typeFrom":"2","jndatetimeStr":"2026-11-15 12:00"}]}}"""
                    }
                }
            },
        )

        try {
            rule.onNodeWithText("登录并查询").performClick()
            assertTrue(accountASummaryEntered.await(5, TimeUnit.SECONDS))
            assertTrue(accountARowsCommitted.await(5, TimeUnit.SECONDS))
            rule.waitForIdle()
            scrollToHistoryText("A 合成流水")

            rule.runOnIdle {
                account.bind(accountId = "acct_test_account_b", studentId = accountBStudentId, generation = 2)
            }
            scrollHistoryToTop()
            rule.waitUntil(5_000) {
                rule.onAllNodes(hasText("登录并查询") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("登录并查询").performClick()
            assertTrue(accountBRequestSettled.await(5, TimeUnit.SECONDS))
            rule.waitForIdle()
            scrollToHistoryText("B 合成流水")
            scrollToHistoryText("收入 ¥ 222.00 · 支出 ¥ 0.00")

            releaseAccountASummary.countDown()
            assertTrue(accountARequestSettled.await(5, TimeUnit.SECONDS))
            rule.waitForIdle()

            scrollToHistoryText("B 合成流水")
            rule.onNodeWithText("A 合成流水").assertDoesNotExist()
            scrollToHistoryText("收入 ¥ 222.00 · 支出 ¥ 0.00")
            rule.onNodeWithText("收入 ¥ 111.00 · 支出 ¥ 0.00").assertDoesNotExist()
        } finally {
            releaseAccountASummary.countDown()
        }
    }
}
