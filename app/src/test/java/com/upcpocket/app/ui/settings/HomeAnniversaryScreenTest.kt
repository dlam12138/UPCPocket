package com.upcpocket.app.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.upcpocket.app.data.prefs.HomeAnniversaryMode
import com.upcpocket.app.data.prefs.HomeAnniversarySettings
import com.upcpocket.app.ui.theme.UpcPocketTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp", application = Application::class)
class HomeAnniversaryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedDate = LocalDate.of(2026, 11, 24)

    @Test
    fun `hidden saved values are restored and saved back trimmed`() {
        var saved: HomeAnniversarySettings? = null
        var back = false
        rule.setContent {
            UpcPocketTheme {
                HomeAnniversaryScreen(
                    settings = HomeAnniversarySettings(
                        title = "入学纪念日",
                        date = savedDate,
                        mode = HomeAnniversaryMode.COUNT_UP,
                        visible = false,
                    ),
                    onSave = { saved = it },
                    onBack = { back = true },
                )
            }
        }

        rule.onNodeWithTag("anniversary_title")
            .performScrollTo()
            .assertTextContains("入学纪念日")
        rule.onNodeWithTag("anniversary_date")
            .performScrollTo()
            .assertTextContains("2026年11月24日")
        rule.onNodeWithTag("anniversary_mode_count_up").assertIsSelected()
        rule.onNodeWithTag("anniversary_visible").assertIsOff()

        rule.onNodeWithTag("anniversary_title").performTextReplacement("  校庆  ")
        rule.onNodeWithTag("anniversary_visible").performClick()
        rule.onNodeWithTag("anniversary_save").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { saved != null }

        assertEquals(
            HomeAnniversarySettings(
                title = "校庆",
                date = savedDate,
                mode = HomeAnniversaryMode.COUNT_UP,
                visible = true,
            ),
            saved,
        )
        assertTrue(back)
    }

    @Test
    fun `save failure keeps the draft and allows a retry`() {
        var attempts = 0
        var back = false
        rule.setContent {
            UpcPocketTheme {
                HomeAnniversaryScreen(
                    settings = HomeAnniversarySettings(
                        title = "入学纪念日",
                        date = savedDate,
                        mode = HomeAnniversaryMode.COUNT_DOWN,
                        visible = true,
                    ),
                    onSave = {
                        attempts++
                        throw IllegalStateException("synthetic storage failure")
                    },
                    onBack = { back = true },
                )
            }
        }

        rule.onNodeWithTag("anniversary_title")
            .performScrollTo()
            .performTextReplacement("改过的标题")
        rule.onNodeWithTag("anniversary_save").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { attempts == 1 }

        rule.onNodeWithText("保存失败，请重试。").assertExists()
        rule.onNodeWithTag("anniversary_title").assertTextContains("改过的标题")
        rule.onNodeWithTag("anniversary_save").assertIsEnabled().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { attempts == 2 }
        assertFalse(back)
    }

    @Test
    fun `form stays unavailable before the preference is read`() {
        rule.setContent {
            UpcPocketTheme {
                HomeAnniversaryScreen(
                    settings = null,
                    onSave = {},
                    onBack = {},
                )
            }
        }

        rule.onNodeWithText("正在读取纪念日设置").assertExists()
        rule.onNodeWithTag("anniversary_title").assertDoesNotExist()
        rule.onNodeWithTag("anniversary_save").assertDoesNotExist()
    }

    @Test
    fun `unconfigured form cannot be saved without a title and date`() {
        rule.setContent {
            UpcPocketTheme {
                HomeAnniversaryScreen(
                    settings = HomeAnniversarySettings(),
                    onSave = {},
                    onBack = {},
                )
            }
        }

        rule.onNodeWithTag("anniversary_save")
            .performScrollTo()
            .assertIsNotEnabled()
    }
}
