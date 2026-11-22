package com.upcpocket.app.ui.services

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.ui.learning.LearningPlatformCatalog
import com.upcpocket.app.ui.theme.UpcPocketTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 服务页宿主聚合入口：全部/学习与空间可见，官方网页分类不重复，
 * 平台别名可检索，图钉写入稳定 ID，点击交宿主二级页。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp", application = Application::class)
class LearningPlatformServicesUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `learning platform card is searchable and pins with the stable id`() {
        var pinned by mutableStateOf(emptySet<String>())
        var opened = false

        rule.setContent {
            UpcPocketTheme {
                CompositionLocalProvider(LocalDockClearance provides 0.dp) {
                    ServicesScreen(
                        entries = emptyList(),
                        pinnedServiceIds = pinned,
                        preferredCampus = null,
                        onTogglePin = { id ->
                            pinned = if (id in pinned) pinned - id else pinned + id
                        },
                        onOpenRoute = {},
                        onOpenOfficialWeb = {},
                        onOpenLearningPlatforms = { opened = true },
                    )
                }
            }
        }

        // 「全部」即使没有插件贡献也显示宿主卡片。
        rule.onNodeWithText("学习平台").assertIsDisplayed()

        // 「官方网页」只展示网页目录，不重复放聚合入口。
        rule.onNodeWithText("官方网页").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("学习平台").assertDoesNotExist()

        // 切回「学习与空间」后可用平台别名检索到唯一卡片。
        rule.onNodeWithText("学习与空间").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("智慧树")
        rule.waitForIdle()
        rule.onNodeWithText("学习平台").assertIsDisplayed()

        rule.onNodeWithContentDescription("固定到首页", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(setOf(LearningPlatformCatalog.SERVICE_ID), pinned)

        rule.onNodeWithContentDescription("取消首页固定", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(pinned.isEmpty())

        rule.onNodeWithText("学习平台").performClick()
        rule.runOnIdle { assertTrue(opened) }
    }
}
