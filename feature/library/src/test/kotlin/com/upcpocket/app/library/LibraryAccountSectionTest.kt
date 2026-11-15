package com.upcpocket.app.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class LibraryAccountSectionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `disclosure starts compact and toggling it does not change library state`() {
        var rememberLoginChanges = 0
        var logoutCalls = 0
        rule.setContent {
            MaterialTheme {
                LibraryAccountSectionContent(
                    rememberLogin = true,
                    hasSession = true,
                    onRememberLoginChange = { rememberLoginChanges++ },
                    onLogout = { logoutCalls++ },
                )
            }
        }

        rule.onNodeWithTag("library_account_toggle").assertIsDisplayed()
        rule.onNodeWithText("图书馆登录").assertIsDisplayed()
        rule.onNodeWithText("已登录").assertIsDisplayed()
        rule.onNodeWithTag("library_account_details").assertDoesNotExist()

        rule.onNodeWithTag("library_account_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("library_account_details").assertIsDisplayed()
        rule.onNodeWithTag("library_remember_switch").assertIsOn()
        rule.onNodeWithTag("library_session_status").assertIsDisplayed()
        rule.onNodeWithTag("library_logout").assertIsDisplayed()
        rule.onNodeWithTag("library_account_explanation").assertIsDisplayed()

        rule.onNodeWithTag("library_account_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("library_account_details").assertDoesNotExist()
        assertEquals(0, rememberLoginChanges)
        assertEquals(0, logoutCalls)
    }

    @Test
    fun `expanded disclosure survives saved instance restoration`() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                LibraryAccountSectionContent(
                    rememberLogin = false,
                    hasSession = false,
                    onRememberLoginChange = {},
                    onLogout = {},
                )
            }
        }

        rule.onNodeWithTag("library_account_toggle").performClick()
        rule.onNodeWithTag("library_account_details").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithTag("library_account_details").assertIsDisplayed()
        rule.onNodeWithTag("library_session_status").assertTextEquals("未登录")
    }
}
