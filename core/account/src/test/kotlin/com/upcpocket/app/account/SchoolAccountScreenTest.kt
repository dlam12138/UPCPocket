package com.upcpocket.app.account

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountController
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.ui.theme.UpcPocketTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class SchoolAccountScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun saveSuccessShowsUnverifiedAndClearsPasswordFields() {
        val controller = FakeController()
        rule.setContent {
            UpcPocketTheme {
                SchoolAccountScreen(accounts = controller, onBack = {})
            }
        }

        rule.onNodeWithTag("account_input").performTextInput("20260001")
        rule.onNodeWithTag("password_input").performTextInput("synthetic-password")
        rule.onNodeWithTag("confirm_input").performTextInput("synthetic-password")
        rule.onNodeWithTag("save_account").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("account_message").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithTag("account_message").assertTextContains("已保存，尚未验证", substring = true)
        rule.onNodeWithTag("account_message").assertTextContains("返回课表后，点击右上角刷新", substring = true)
        assertPasswordCleared("password_input")
        assertPasswordCleared("confirm_input")
        assertEquals("20260001", controller.lastAccount)
        assertEquals("synthetic-password", controller.lastPassword?.concatToString())
        // FLAG_SECURE 防止账号页中的密码进入截图/录屏。
        assertTrue(
            rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
    }

    @Test
    fun saveFailureShowsFixedErrorAndClearsPasswordFields() {
        val controller = FakeController().apply {
            saveResult = SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.STORAGE_FAILED)
        }
        rule.setContent {
            UpcPocketTheme {
                SchoolAccountScreen(accounts = controller, onBack = {})
            }
        }

        rule.onNodeWithTag("account_input").performTextInput("20260001")
        rule.onNodeWithTag("password_input").performTextInput("synthetic-password")
        rule.onNodeWithTag("confirm_input").performTextInput("synthetic-password")
        rule.onNodeWithTag("save_account").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("account_message").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithTag("account_message").assertTextContains("保存失败", substring = true)
        assertPasswordCleared("password_input")
        assertPasswordCleared("confirm_input")
        assertFalse(controller.lastPassword?.concatToString().orEmpty().contains("Exception"))
    }

    @Test
    fun alreadySavedStateIsRenderedAsUnverifiedNotLoggedIn() {
        val controller = FakeController().apply {
            state.value = SchoolAccountState(
                status = SchoolAccountStatus.SAVED_UNVERIFIED,
                generation = AccountGeneration(3),
                maskedAccount = "2026***01",
                maskedStudentId = "20••••01",
                studentIdAvailable = true,
                savedAtEpochMillis = 1_700_000_000_000,
            )
        }
        rule.setContent {
            UpcPocketTheme {
                SchoolAccountScreen(accounts = controller, onBack = {})
            }
        }

        rule.onNodeWithTag("account_status").assertTextContains("已保存，尚未验证", substring = true)
        rule.onNodeWithTag("account_status").assertTextContains("2026***01", substring = true)
        rule.onNodeWithTag("account_student_binding")
            .assertTextContains("20••••01", substring = true)
        rule.onNodeWithTag("account_status").assertIsDisplayed()
    }

    @Test
    fun legacyCardStudentIdIsOnlyAMaskedHintAndNeverBindsAnAccount() {
        val controller = FakeController()
        rule.setContent {
            UpcPocketTheme {
                SchoolAccountScreen(
                    accounts = controller,
                    onBack = {},
                    legacyStudentIdHint = "2099000123",
                )
            }
        }

        rule.onNodeWithTag("legacy_student_hint")
            .assertTextContains("20••••23", substring = true)
        rule.onNodeWithTag("legacy_student_hint")
            .assertTextContains("余额只按当前账号查询", substring = true)
        // 页面没有把旧草稿填进账号输入框，也没有触发保存。
        assertEquals(null, controller.lastAccount)
        assertTrue(
            rule.onNodeWithTag("account_input")
                .fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.EditableText)
                ?.text
                .isNullOrEmpty(),
        )
    }

    @Test
    fun passwordInputIsNotRestoredAfterSavedStateRestore() {
        val controller = FakeController()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            UpcPocketTheme {
                SchoolAccountScreen(accounts = controller, onBack = {})
            }
        }

        rule.onNodeWithTag("password_input").performTextInput("synthetic-password")
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        assertPasswordCleared("password_input")
        assertPasswordCleared("confirm_input")
    }

    private fun assertPasswordCleared(tag: String) {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertEquals("", text)
    }

    private class FakeController : SchoolAccountController {
        override val state = MutableStateFlow(
            SchoolAccountState(status = SchoolAccountStatus.NOT_CONFIGURED),
        )

        var saveResult: SchoolAccountWriteResult = SchoolAccountWriteResult.Success
        var lastAccount: String? = null
        var lastPassword: CharArray? = null

        override suspend fun restore() = Unit

        override suspend fun save(account: String?, password: CharArray): SchoolAccountWriteResult {
            lastAccount = account
            lastPassword = password.copyOf()
            if (saveResult == SchoolAccountWriteResult.Success) {
                state.value = SchoolAccountState(
                    status = SchoolAccountStatus.SAVED_UNVERIFIED,
                    generation = AccountGeneration(1),
                    maskedAccount = "2026***01",
                    savedAtEpochMillis = 1_700_000_000_000,
                )
            }
            return saveResult
        }

        override suspend fun delete(): SchoolAccountWriteResult = SchoolAccountWriteResult.Success

        override suspend fun retryCleanup(): SchoolAccountWriteResult =
            SchoolAccountWriteResult.Success
    }
}
