package com.upcpocket.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.ui.components.CampusButton
import com.upcpocket.app.ui.components.CampusFilterChip
import com.upcpocket.app.ui.components.CampusIconButton
import com.upcpocket.app.ui.components.CampusSegmentedTabs
import com.upcpocket.app.ui.components.CampusSwitch
import com.upcpocket.app.ui.services.ServicesScreen
import com.upcpocket.app.ui.theme.UpcPocketTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AppearanceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(sdk = [35])
    fun material3ControlsRespondToClicksAndUpdateSelection() {
        var buttonClicks = 0

        composeRule.setContent {
            UpcPocketTheme(darkMode = DarkMode.DARK, preset = ThemePreset.BLUE) {
                var selectedIndex by remember { mutableIntStateOf(0) }
                var checked by remember { mutableStateOf(false) }
                Column {
                    CampusButton(onClick = { buttonClicks++ }) { Text("确认操作") }
                    CampusSegmentedTabs(
                        tabs = listOf("今日", "全部"),
                        selectedIndex = selectedIndex,
                        onSelect = { selectedIndex = it },
                        tabStyle = true,
                    )
                    Text("选中页 $selectedIndex")
                    CampusFilterChip(
                        selected = checked,
                        onClick = { checked = !checked },
                        label = { Text("筛选项") },
                    )
                    CampusSwitch(
                        checked = checked,
                        onCheckedChange = { checked = it },
                    )
                    Text("筛选状态 ${if (checked) "开" else "关"}")
                    CampusIconButton(onClick = { buttonClicks++ }) {
                        Icon(Icons.Filled.Home, contentDescription = "首页")
                    }
                }
            }
        }

        composeRule.onNodeWithText("确认操作").performClick()
        composeRule.onNodeWithText("全部").performClick()
        composeRule.onNodeWithText("选中页 1").assertIsDisplayed()
        composeRule.onNodeWithText("筛选项").performClick()
        composeRule.onNodeWithText("筛选状态 开").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("首页").performClick()
        assertEquals(2, buttonClicks)
    }

    @Test
    @Config(sdk = [35])
    fun servicesScreenSearchDisplaysPlaceholderAndFilters() {
        composeRule.setContent {
            UpcPocketTheme {
                CompositionLocalProvider(LocalDockClearance provides 0.dp) {
                    ServicesScreen(
                        entries = emptyList(),
                        pinnedServiceIds = emptySet(),
                        onTogglePin = {},
                        onOpenRoute = {},
                        onOpenOfficialWeb = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("校园服务").assertIsDisplayed()
        composeRule.onNodeWithText("搜索服务").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("不存在的服务关键词")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("没有匹配的服务").assertIsDisplayed()
        composeRule.onNodeWithText("清空搜索").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("搜索服务").assertIsDisplayed()
    }
}
