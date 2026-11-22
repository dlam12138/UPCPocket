package com.upcpocket.app.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.ui.learning.LearningPlatformCatalog
import com.upcpocket.app.ui.theme.UpcPocketTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 学习平台固定项与原生插件/官方网页共用首页快捷区：
 * 未固定时不出现，固定后点击仍打开宿主二级页。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp", application = Application::class)
class LearningPlatformHomeUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `pinned learning platform opens the host page and unpinned stays hidden`() {
        var pinned by mutableStateOf(setOf(LearningPlatformCatalog.SERVICE_ID))
        var opened = false

        rule.setContent {
            UpcPocketTheme {
                CompositionLocalProvider(LocalDockClearance provides 0.dp) {
                    HomeScreen(
                        serviceEntries = emptyList(),
                        timetableSections = emptyList(),
                        cardSections = emptyList(),
                        todoSections = emptyList(),
                        sectionHost = object : PluginSectionHost {
                            override fun openSchoolAccountSettings() = Unit
                        },
                        onOpenRoute = {},
                        onOpenSettings = {},
                        onOpenOfficialWeb = {},
                        pinnedServiceIds = pinned,
                        onOpenLearningPlatforms = { opened = true },
                    )
                }
            }
        }

        rule.onNodeWithText("学习平台").assertIsDisplayed()
        rule.onNodeWithText("学习平台").performClick()
        rule.runOnIdle { assertTrue(opened) }

        rule.runOnIdle { pinned = emptySet() }
        rule.waitForIdle()
        rule.onNodeWithText("学习平台").assertDoesNotExist()
    }
}
