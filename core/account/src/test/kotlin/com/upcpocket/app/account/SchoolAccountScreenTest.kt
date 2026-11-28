package com.upcpocket.app.account

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.account.api.ManagedDevice
import com.upcpocket.app.account.api.ManagedDeviceListResult
import com.upcpocket.app.account.api.SchoolAccountController
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.account.api.SchoolAccountWriteResult
import com.upcpocket.app.ui.theme.UpcPocketTheme
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
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
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bindingRequiresCustodyConsentAndClearsTheInputAfterSubmission() {
        val controller = FakeController()
        rule.setContent { UpcPocketTheme { SchoolAccountScreen(controller, onBack = {}) } }

        rule.onNodeWithTag("bind_account_input").performScrollTo().performTextInput("synthetic-user")
        rule.onNodeWithTag("bind_password_input").performScrollTo().performTextInput("synthetic-password")
        rule.onNodeWithTag("bind_account").performScrollTo().performClick()
        rule.onNodeWithText("请先阅读说明并同意由校方服务代管学校账号。").performScrollTo().assertIsDisplayed()
        assertEquals(0, controller.bindCalls)

        rule.onNodeWithTag("custody_consent").performScrollTo().performClick()
        rule.onNodeWithTag("bind_account").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("account_message").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("account_message").performScrollTo()
            .assertTextContains("校方托管账号已绑定", substring = true)
        assertEquals("synthetic-user", controller.lastLoginName)
        assertEquals("synthetic-password", controller.lastPassword?.concatToString())
        assertTrue(controller.passwordWasCleared)
        assertPasswordCleared("bind_password_input")
        assertTrue(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun backendFailureDisplaysFixedReasonAndDoesNotRetainPassword() {
        val controller = FakeController().apply {
            bindResult = SchoolAccountWriteResult.Failure(SchoolAccountErrorCode.MODULE_DISABLED)
        }
        rule.setContent { UpcPocketTheme { SchoolAccountScreen(controller, onBack = {}) } }

        rule.onNodeWithTag("bind_account_input").performScrollTo().performTextInput("synthetic-user")
        rule.onNodeWithTag("bind_password_input").performScrollTo().performTextInput("synthetic-password")
        rule.onNodeWithTag("custody_consent").performScrollTo().performClick()
        rule.onNodeWithTag("bind_account").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("account_message").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("account_message").performScrollTo()
            .assertTextContains("校方暂未开放此功能", substring = true)
        assertPasswordCleared("bind_password_input")
        assertTrue(controller.passwordWasCleared)
    }

    @Test
    fun passwordUpdateUsesItsOwnForegroundInputAndClearsIt() {
        val controller = FakeController()
        rule.setContent { UpcPocketTheme { SchoolAccountScreen(controller, onBack = {}) } }

        rule.onNodeWithTag("update_account_input").performScrollTo().performTextInput("synthetic-user")
        rule.onNodeWithTag("update_password_input").performScrollTo().performTextInput("synthetic-new-password")
        rule.onNodeWithTag("update_password").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("account_message").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("account_message").performScrollTo()
            .assertTextContains("密码更新请求已完成", substring = true)
        assertEquals("synthetic-user", controller.updatedLoginName)
        assertEquals("synthetic-new-password", controller.updatedPassword?.concatToString())
        assertTrue(controller.passwordWasCleared)
        assertPasswordCleared("update_password_input")
    }

    @Test
    fun managedStatusListsDevicesAndUnbindRequiresConfirmation() {
        val controller = FakeController().apply { setManagedActive() }
        rule.setContent { UpcPocketTheme { SchoolAccountScreen(controller, onBack = {}) } }

        rule.onNodeWithTag("account_status").assertTextContains("校方托管已连接", substring = true)
        rule.onNodeWithText("查看设备").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("解除托管并删除账号").performScrollTo().performClick()
        rule.onNodeWithText("确认删除").performClick()
        rule.waitUntil(5_000) { controller.unbindConfirmed }
        assertTrue(controller.unbindConfirmed)
    }

    @Test
    fun unresolvedUnbindShowsHonestStatusAndVisibleRetryAction() {
        val controller = FakeController().apply {
            state.value = SchoolAccountState(
                status = SchoolAccountStatus.UNBIND_PENDING,
                errorCode = SchoolAccountErrorCode.UNBIND_RESULT_UNKNOWN,
                deviceId = UUID.randomUUID().toString(),
            )
        }
        rule.setContent { UpcPocketTheme { SchoolAccountScreen(controller, onBack = {}) } }

        rule.onNodeWithTag("account_status")
            .assertTextContains("解绑结果未知，本机个人数据已封闭", substring = true)
        rule.onNodeWithTag("bind_account").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("update_password").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("重试解除托管").performScrollTo().performClick()
        rule.waitUntil(5_000) { controller.unbindConfirmed }
        assertTrue(controller.unbindConfirmed)
    }

    @Test
    fun unresolvedSignOutShowsVisibleRetryAction() {
        val controller = FakeController().apply {
            state.value = SchoolAccountState(
                status = SchoolAccountStatus.SIGN_OUT_PENDING,
                deviceId = UUID.randomUUID().toString(),
            )
        }
        rule.setContent { UpcPocketTheme { SchoolAccountScreen(controller, onBack = {}) } }

        rule.onNodeWithTag("account_status")
            .assertTextContains("退出本机待重试，本机个人数据已封闭", substring = true)
        rule.onNodeWithTag("bind_account").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("update_password").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("重试退出本机").performScrollTo().performClick()
        rule.waitUntil(5_000) { controller.signOutCalls > 0 }
        assertEquals(1, controller.signOutCalls)
    }

    @Test
    fun passwordsAreNotRestoredFromSavedInstanceState() {
        val controller = FakeController()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { UpcPocketTheme { SchoolAccountScreen(controller, onBack = {}) } }
        rule.onNodeWithTag("bind_password_input").performTextInput("synthetic-password")
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertPasswordCleared("bind_password_input")
        assertPasswordCleared("update_password_input")
    }

    private fun assertPasswordCleared(tag: String) {
        val text = rule.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertEquals("", text)
    }

    private class FakeController : SchoolAccountController {
        override val state = MutableStateFlow(SchoolAccountState(status = SchoolAccountStatus.BACKEND_NOT_READY))
        var bindResult: SchoolAccountWriteResult = SchoolAccountWriteResult.Success
        var bindCalls = 0
        var lastLoginName: String? = null
        var lastPassword: CharArray? = null
        var updatedLoginName: String? = null
        var updatedPassword: CharArray? = null
        var passwordWasCleared = false
        var unbindConfirmed = false
        var signOutCalls = 0

        override suspend fun restore() = Unit

        override suspend fun bind(
            loginName: String,
            password: CharArray,
            acceptCustody: Boolean,
        ): SchoolAccountWriteResult {
            bindCalls++
            lastLoginName = loginName
            lastPassword = password.copyOf()
            password.fill('\u0000')
            passwordWasCleared = password.all { it == '\u0000' }
            if (bindResult == SchoolAccountWriteResult.Success) setManagedActive()
            return bindResult
        }

        override suspend fun updateManagedCredential(
            loginName: String,
            password: CharArray,
        ): SchoolAccountWriteResult {
            updatedLoginName = loginName
            updatedPassword = password.copyOf()
            password.fill('\u0000')
            passwordWasCleared = password.all { it == '\u0000' }
            return SchoolAccountWriteResult.Success
        }

        override suspend fun listDevices(): ManagedDeviceListResult =
            ManagedDeviceListResult.Success(emptyList())

        override suspend fun revokeDevice(deviceId: String): SchoolAccountWriteResult =
            SchoolAccountWriteResult.Success

        override suspend fun signOut(): SchoolAccountWriteResult {
            signOutCalls++
            state.value = SchoolAccountState(status = SchoolAccountStatus.NOT_CONFIGURED)
            return SchoolAccountWriteResult.Success
        }

        override suspend fun unbind(confirmed: Boolean): SchoolAccountWriteResult {
            unbindConfirmed = confirmed
            state.value = SchoolAccountState(status = SchoolAccountStatus.NOT_CONFIGURED)
            return SchoolAccountWriteResult.Success
        }

        override suspend fun retryCleanup(): SchoolAccountWriteResult = SchoolAccountWriteResult.Success

        fun setManagedActive() {
            state.value = SchoolAccountState(
                status = SchoolAccountStatus.MANAGED_ACTIVE,
                generation = com.upcpocket.app.account.api.AccountGeneration(1),
                deviceId = UUID.randomUUID().toString(),
            )
        }
    }
}
