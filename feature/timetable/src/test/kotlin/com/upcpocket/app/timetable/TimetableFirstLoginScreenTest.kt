package com.upcpocket.app.timetable

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.account.api.SchoolAccountReference
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.platform.crypto.ByteCrypto
import com.upcpocket.app.upcauth.TimetableAuthenticator
import com.upcpocket.app.upcauth.UpcAuthCookie
import com.upcpocket.app.upcauth.UpcAuthFailure
import com.upcpocket.app.upcauth.UpcAuthResult
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class TimetableFirstLoginScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val directory = java.nio.file.Files.createTempDirectory("timetable-first-login")
        .toFile()
    private val account = FakeAccount()
    private val auth = FakeAuth()

    private fun service(
        transport: ProgrammedTransport,
    ): TimetableService = TimetableService(
        account = account,
        authenticator = auth,
        api = TimetableApi(transport, clock = { 1_700_000_000_000 }),
        sessionStore = TimetableSessionStore(directory, TestCrypto()),
        cacheStore = TimetableCacheStore(directory),
        clock = { 1_700_000_000_000 },
        todayProvider = { java.time.LocalDate.parse("2026-10-28") },
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    @After
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun firstInteractionRequiredShowsReachableLoginAndCanContinue() {
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(javaClass.getResource("/timetable_index.json")!!.readText())
            } else {
                json(javaClass.getResource("/timetable_week_08.json")!!.readText())
            }
        }
        val service = service(transport)
        val viewModel = TimetableViewModel(service)
        rule.setContent {
            MaterialTheme {
                TimetableScreen(
                    viewModel = viewModel,
                    onOpenAccountSettings = {},
                )
            }
        }
        rule.waitUntil(5_000) {
            service.state.value.connection == TimetableConnection.INTERACTION_REQUIRED
        }
        rule.onNodeWithText("学校登录").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) {
            service.state.value.interactionUrl != null
        }
        rule.onNodeWithText("网页登录").assertIsDisplayed()

        // 模拟学校网页正常完成登录后，业务层用页面 Cookie 再验证 index，
        // 然后主动刷新取得当前周；不依赖 WebView 注入表单。
        runBlocking {
            assertTrue(service.adoptInteractiveCookies("eai-sess=web-session"))
            service.refresh()
        }
        rule.waitUntil(5_000) {
            service.state.value.terms.isNotEmpty() &&
                service.state.value.weekState == TimetableWeekState.READY
        }
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithText("第 8 周").assertExists()
        rule.onNodeWithText("共 19 周").assertExists()
        assertEquals(TimetableConnection.CONNECTED, service.state.value.connection)
    }

    @Test
    fun accountSwitchDropsOldDetailAndLoginDialog() {
        auth.result = UpcAuthResult.Success(
            listOf(
                UpcAuthCookie(
                    name = "eai-sess",
                    value = "synthetic-session",
                    domain = "app.upc.edu.cn",
                    path = "/",
                    secure = true,
                    httpOnly = true,
                    expiresAtEpochSeconds = null,
                ),
            ),
        )
        val transport = ProgrammedTransport { request ->
            if (request.method == "GET") {
                json(javaClass.getResource("/timetable_index.json")!!.readText())
            } else {
                json(javaClass.getResource("/timetable_week_08.json")!!.readText())
            }
        }
        val service = service(transport)
        val viewModel = TimetableViewModel(service)
        rule.setContent {
            MaterialTheme {
                TimetableScreen(
                    viewModel = viewModel,
                    onOpenAccountSettings = {},
                )
            }
        }
        rule.waitUntil(5_000) {
            service.state.value.weekState == TimetableWeekState.READY &&
                service.state.value.connection == TimetableConnection.CONNECTED
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("课程详情 示例课程A 第6节、第7节")
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("日期").assertExists()
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithContentDescription("课表说明").performClick()
        rule.onNodeWithText("网页登录").performClick()
        rule.onNodeWithText("网页登录").assertIsDisplayed()

        account.publishOther()
        rule.waitUntil(5_000) {
            service.state.value.accountEpoch > 0 && service.state.value.terms.isEmpty()
        }
        rule.onNodeWithText("日期").assertDoesNotExist()
        rule.onNodeWithText("学校登录").assertDoesNotExist()
    }

    private fun json(body: String): TimetableHttpResponse = TimetableHttpResponse(
        200,
        mapOf("content-type" to listOf("application/json")),
        body.toByteArray(),
    )

    private inner class ProgrammedTransport(
        private val handler: suspend (TimetableHttpRequest) -> TimetableHttpResponse,
    ) : TimetableHttpTransport {
        override suspend fun execute(request: TimetableHttpRequest): TimetableHttpResponse {
            return handler(request)
        }
    }

    private class FakeAccount : SchoolAccountReferenceReader {
        private val _reference = MutableStateFlow(
            SchoolAccountReference(
                SchoolAccountId("acct_synthetic_1"),
                AccountGeneration(1),
            ),
        )
        private val _state = MutableStateFlow(
            SchoolAccountState(
                status = SchoolAccountStatus.SAVED_UNVERIFIED,
                generation = AccountGeneration(1),
                maskedAccount = "20••••01",
                savedAtEpochMillis = 1,
            ),
        )
        override val state: StateFlow<SchoolAccountState> = _state
        override val accountReference: StateFlow<SchoolAccountReference?> = _reference

        fun publishOther() {
            _reference.value = SchoolAccountReference(
                SchoolAccountId("acct_synthetic_2"),
                AccountGeneration(2),
            )
            _state.value = SchoolAccountState(
                status = SchoolAccountStatus.SAVED_UNVERIFIED,
                generation = AccountGeneration(2),
                maskedAccount = "20••••02",
                savedAtEpochMillis = 2,
            )
        }
    }

    private class FakeAuth : TimetableAuthenticator {
        var result: UpcAuthResult = UpcAuthResult.Failure(
            UpcAuthFailure.INTERACTION_REQUIRED,
            "https://cas.upc.edu.cn/cas/login?service=synthetic",
        )

        override suspend fun authenticate(
            expectedAccountId: SchoolAccountId,
            expectedCredentialGeneration: Long,
        ): UpcAuthResult = result
    }

    private class TestCrypto : ByteCrypto {
        override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
        override fun decrypt(stored: ByteArray): ByteArray = stored.reversedArray()
    }
}
