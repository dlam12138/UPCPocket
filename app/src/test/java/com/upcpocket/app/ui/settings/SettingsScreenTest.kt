package com.upcpocket.app.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountErrorCode
import com.upcpocket.app.account.api.SchoolAccountState
import com.upcpocket.app.account.api.SchoolAccountStatus
import com.upcpocket.app.data.prefs.TodoHostPlacement
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.ui.theme.UpcPocketTheme
import org.junit.Assert.assertEquals
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
        // 宿主未提供两个可用宿主时不展示位置卡，避免出现无效选项。
        rule.onNodeWithText("日程待办位置").assertDoesNotExist()
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

    @Test
    fun `todo placement card shows saved host and reports changes`() {
        var changed: TodoHostPlacement? = null
        rule.setContent {
            UpcPocketTheme {
                SettingsScreen(
                    accountState = SchoolAccountState(
                        status = SchoolAccountStatus.SAVED_UNVERIFIED,
                        generation = AccountGeneration(7),
                    ),
                    schoolSections = emptyList(),
                    sectionHost = FakeSectionHost(),
                    todoHostPlacement = TodoHostPlacement.FOCUS,
                    showTodoHostPlacement = true,
                    onTodoHostPlacementChange = { changed = it },
                    onBack = {},
                    onOpenAccount = {},
                    onOpenAppearance = {},
                    onOpenWidgets = {},
                    onOpenOfficialWeb = {},
                    onOpenAbout = {},
                )
            }
        }

        rule.onNodeWithText("日程待办位置").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("专注页").performScrollTo().assertIsDisplayed().assertIsSelected()
        rule.onNodeWithText("课表页").performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(TodoHostPlacement.TIMETABLE, changed)
    }

    @Test
    fun `todo placement choices stay disabled before the preference is read`() {
        rule.setContent {
            UpcPocketTheme {
                SettingsScreen(
                    accountState = SchoolAccountState(
                        status = SchoolAccountStatus.SAVED_UNVERIFIED,
                        generation = AccountGeneration(8),
                    ),
                    schoolSections = emptyList(),
                    sectionHost = FakeSectionHost(),
                    todoHostPlacement = null,
                    showTodoHostPlacement = true,
                    onBack = {},
                    onOpenAccount = {},
                    onOpenAppearance = {},
                    onOpenWidgets = {},
                    onOpenOfficialWeb = {},
                    onOpenAbout = {},
                )
            }
        }

        // DataStore 尚未读完时不用默认的“课表页”冒充真实偏好。
        rule.onNodeWithText("日程待办位置").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("课表页").assertIsNotEnabled()
        rule.onNodeWithText("专注页").assertIsNotEnabled()
    }

    @Test
    fun `module reorder switch shows saved value and reports changes`() {
        var changed: Boolean? = null
        rule.setContent {
            UpcPocketTheme {
                SettingsScreen(
                    accountState = SchoolAccountState(
                        status = SchoolAccountStatus.SAVED_UNVERIFIED,
                        generation = AccountGeneration(9),
                    ),
                    schoolSections = emptyList(),
                    sectionHost = FakeSectionHost(),
                    moduleReorderEnabled = true,
                    onModuleReorderEnabledChange = { changed = it },
                    onBack = {},
                    onOpenAccount = {},
                    onOpenAppearance = {},
                    onOpenWidgets = {},
                    onOpenOfficialWeb = {},
                    onOpenAbout = {},
                )
            }
        }

        val row = rule.onNodeWithText("允许拖动卡片").performScrollTo()
        row.assertIsDisplayed().assertIsOn()
        rule.onNodeWithText("长按拖动首页和课表的卡片。关闭后锁定当前顺序。")
            .performScrollTo()
            .assertIsDisplayed()
        row.performClick()
        rule.waitForIdle()

        assertEquals(false, changed)
    }

    @Test
    fun `module reorder switch stays disabled before the preference is read`() {
        rule.setContent {
            UpcPocketTheme {
                SettingsScreen(
                    accountState = SchoolAccountState(
                        status = SchoolAccountStatus.SAVED_UNVERIFIED,
                        generation = AccountGeneration(10),
                    ),
                    schoolSections = emptyList(),
                    sectionHost = FakeSectionHost(),
                    moduleReorderEnabled = null,
                    onBack = {},
                    onOpenAccount = {},
                    onOpenAppearance = {},
                    onOpenWidgets = {},
                    onOpenOfficialWeb = {},
                    onOpenAbout = {},
                )
            }
        }

        // DataStore 尚未读完时不用默认的关闭状态冒充真实偏好。
        rule.onNodeWithText("允许拖动卡片")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    private class FakeSectionHost : PluginSectionHost {
        override fun openSchoolAccountSettings() = Unit
    }
}
