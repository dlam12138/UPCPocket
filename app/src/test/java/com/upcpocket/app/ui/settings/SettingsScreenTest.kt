package com.upcpocket.app.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.ui.theme.UpcPocketTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class SettingsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `settings groups are compact and missing library plugin leaves no entry`() {
        var officialWebOpened = false
        val accountState = SchoolAccountState(
            status = SchoolAccountStatus.SAVED_UNVERIFIED,
            generation = AccountGeneration(3),
            maskedAccount = "2026***01",
            savedAtEpochMillis = 1_700_000_000_000,
        )
        rule.setContent {
            UpcPocketTheme {
                SettingsScreen(
                    accountState = accountState,
                    schoolSections = emptyList(),
                    sectionHost = FakeSectionHost(),
                    onBack = {},
                    onOpenAccount = {},
                    onOpenAppearance = {},
                    onOpenWidgets = {},
                    onOpenOfficialWeb = { officialWebOpened = true },
                    onOpenAbout = {},
                )
            }
        }

        rule.onNodeWithText("已保存 · 2026***01").assertIsDisplayed()
        rule.onNodeWithText("校园").assertIsDisplayed()
        rule.onNodeWithText("应用").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("图书馆登录").assertDoesNotExist()
        rule.onNodeWithText("桌面组件").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("校园卡 / 电量 / 课表").assertDoesNotExist()
        rule.onNodeWithText("校园官方网页").performScrollTo().performClick()

        assertTrue(officialWebOpened)
    }

    @Test
    fun `reconfiguration status remains visible on the account row`() {
        rule.setContent {
            UpcPocketTheme {
                SettingsScreen(
                    accountState = SchoolAccountState(
                        status = SchoolAccountStatus.NEEDS_RECONFIGURATION,
                        generation = AccountGeneration(4),
                    ),
                    schoolSections = emptyList(),
                    sectionHost = FakeSectionHost(),
                    onBack = {},
                    onOpenAccount = {},
                    onOpenAppearance = {},
                    onOpenWidgets = {},
                    onOpenOfficialWeb = {},
                    onOpenAbout = {},
                )
            }
        }

        rule.onNodeWithText("需要重新保存账号").assertIsDisplayed()
    }

    @Test
    fun `cleanup failure takes priority over saved account summary`() {
        rule.setContent {
            UpcPocketTheme {
                SettingsScreen(
                    accountState = SchoolAccountState(
                        status = SchoolAccountStatus.SAVED_UNVERIFIED,
                        generation = AccountGeneration(5),
                        maskedAccount = "2026***01",
                        cleanupRequired = true,
                        errorCode = SchoolAccountErrorCode.CLEANUP_FAILED,
                    ),
                    schoolSections = emptyList(),
                    sectionHost = FakeSectionHost(),
                    onBack = {},
                    onOpenAccount = {},
                    onOpenAppearance = {},
                    onOpenWidgets = {},
                    onOpenOfficialWeb = {},
                    onOpenAbout = {},
                )
            }
        }

        rule.onNodeWithText("清理未完成，请重试。").assertIsDisplayed()
        rule.onNodeWithText("已保存 · 2026***01").assertDoesNotExist()
    }

    @Test
    fun `account storage failure displays its specific message`() {
        rule.setContent {
            UpcPocketTheme {
                SettingsScreen(
                    accountState = SchoolAccountState(
                        status = SchoolAccountStatus.SAVED_UNVERIFIED,
                        generation = AccountGeneration(6),
                        maskedAccount = "2026***01",
                        errorCode = SchoolAccountErrorCode.STORAGE_FAILED,
                    ),
                    schoolSections = emptyList(),
                    sectionHost = FakeSectionHost(),
                    onBack = {},
                    onOpenAccount = {},
                    onOpenAppearance = {},
                    onOpenWidgets = {},
                    onOpenOfficialWeb = {},
                    onOpenAbout = {},
                )
            }
        }

        rule.onNodeWithText("保存失败，请重试。").assertIsDisplayed()
        rule.onNodeWithText("已保存 · 2026***01").assertDoesNotExist()
    }

    private class FakeSectionHost : PluginSectionHost {
        override fun openSchoolAccountSettings() = Unit
    }
}
