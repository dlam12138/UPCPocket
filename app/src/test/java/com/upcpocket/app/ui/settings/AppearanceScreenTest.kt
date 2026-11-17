package com.upcpocket.app.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.data.prefs.UiPrefsStore
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.liquid.LiquidBottomTabs
import com.upcpocket.app.ui.liquid.LiquidButton
import com.upcpocket.app.ui.liquid.LiquidDockDestination
import com.upcpocket.app.ui.liquid.LiquidFilterChip
import com.upcpocket.app.ui.liquid.LiquidFloatingActionButton
import com.upcpocket.app.ui.liquid.LiquidIconButton
import com.upcpocket.app.ui.liquid.LiquidSegmentedTabs
import com.upcpocket.app.ui.liquid.LiquidTextButton
import com.upcpocket.app.ui.liquid.LiquidToggle
import com.upcpocket.app.ui.liquid.LiquidTopAppBar
import com.upcpocket.app.ui.liquid.LocalGlassBackdrop
import com.upcpocket.app.ui.theme.UpcPocketTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    @Config(sdk = [31])
    fun liquidGlassRendersGracefullyOnAndroid12() {
        composeRule.setContent {
            UpcPocketTheme(
                darkMode = DarkMode.LIGHT,
                preset = ThemePreset.BLUE,
                uiStyle = UiStyle.LIQUID_GLASS,
            ) {
                val backdrop = LocalGlassBackdrop.current!!
                Column(Modifier.padding(16.dp)) {
                    LiquidTopAppBar(title = { Text("外观测试") })
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            LiquidButton(onClick = {}) { Text("玻璃按钮") }
                            LiquidToggle(checked = true, onCheckedChange = {})
                            LiquidFilterChip(selected = true, onClick = {}, label = { Text("已选") })
                        }
                    }
                    LiquidBottomTabs(
                        destinations = listOf(
                            LiquidDockDestination(
                                route = "home",
                                label = "首页",
                                icon = Icons.Outlined.Home,
                                selectedIcon = Icons.Filled.Home,
                            ),
                        ),
                        selectedIndex = 0,
                        onSelect = {},
                        backdrop = backdrop,
                    )
                }
            }
        }
        composeRule.onNodeWithText("外观测试").assertIsDisplayed()
        composeRule.onNodeWithText("玻璃按钮").assertIsDisplayed()
        composeRule.onNodeWithText("首页").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35])
    fun material3FallbackAndLiquidGlassBothRenderOnApi35() {
        composeRule.setContent {
            UpcPocketTheme(
                darkMode = DarkMode.DARK,
                preset = ThemePreset.TEAL,
                uiStyle = UiStyle.MATERIAL_3,
            ) {
                GlassCard(Modifier.fillMaxWidth()) {
                    LiquidButton(onClick = {}) { Text("原生按钮") }
                }
            }
        }
        composeRule.onNodeWithText("原生按钮").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], application = Application::class)
    fun uiPrefsStorePersistsUiStyle() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.filesDir, "ui_style_test.preferences_pb").apply { delete() }
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store = UiPrefsStore(dataStore)
            assertEquals(UiStyle.LIQUID_GLASS, store.flow.first().uiStyle)

            store.setUiStyle(UiStyle.MATERIAL_3)
            assertEquals(UiStyle.MATERIAL_3, store.flow.first().uiStyle)

            store.setUiStyle(UiStyle.LIQUID_GLASS)
            assertEquals(UiStyle.LIQUID_GLASS, store.flow.first().uiStyle)
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    @Config(sdk = [35])
    fun liquidGlassInteractiveComponentsRespondToClicks() {
        var cardClicked = false
        var buttonClicked = false
        var chipClicked = false
        var toggled = false
        var tabSelected = -1
        composeRule.setContent {
            UpcPocketTheme(uiStyle = UiStyle.LIQUID_GLASS) {
                val backdrop = LocalGlassBackdrop.current!!
                Column(Modifier.padding(8.dp)) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Surface(
                            onClick = { cardClicked = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("点击测试", Modifier.padding(16.dp))
                        }
                    }
                    LiquidButton(onClick = { buttonClicked = true }) {
                        Text("按钮点击测试")
                    }
                    LiquidFilterChip(
                        selected = false,
                        onClick = { chipClicked = true },
                        label = { Text("标签点击测试") },
                    )
                    LiquidToggle(
                        checked = false,
                        onCheckedChange = { toggled = it },
                        modifier = Modifier.testTag("liquid_toggle_test"),
                    )
                    LiquidBottomTabs(
                        destinations = listOf(
                            LiquidDockDestination("home", "首页", Icons.Outlined.Home, Icons.Filled.Home),
                            LiquidDockDestination("services", "服务", Icons.Outlined.Home, Icons.Filled.Home),
                        ),
                        selectedIndex = 0,
                        onSelect = { tabSelected = it },
                        backdrop = backdrop,
                    )
                }
            }
        }
        composeRule.onNodeWithText("点击测试").performClick()
        composeRule.onNodeWithText("按钮点击测试").performClick()
        composeRule.onNodeWithText("标签点击测试").performClick()
        composeRule.onNodeWithTag("liquid_toggle_test").performTouchInput { swipeRight() }
        composeRule.onNodeWithText("服务").performClick()
        assertEquals(true, cardClicked)
        assertEquals(true, buttonClicked)
        assertEquals(true, chipClicked)
        assertEquals(true, toggled)
        assertEquals(1, tabSelected)
    }

    @Test
    @Config(sdk = [35])
    fun liquidBottomTabsAndToggleRespondToHorizontalSwipe() {
        var latestChecked = false
        var latestTab = 0
        composeRule.setContent {
            var checked by remember { mutableStateOf(false) }
            var selectedTab by remember { mutableIntStateOf(0) }
            UpcPocketTheme(uiStyle = UiStyle.LIQUID_GLASS) {
                val backdrop = LocalGlassBackdrop.current!!
                Column(Modifier.padding(16.dp)) {
                    LiquidToggle(
                        checked = checked,
                        onCheckedChange = {
                            checked = it
                            latestChecked = it
                        },
                        modifier = Modifier.testTag("swipe_toggle"),
                    )
                    LiquidBottomTabs(
                        destinations = listOf(
                            LiquidDockDestination("home", "首页", Icons.Outlined.Home, Icons.Filled.Home),
                            LiquidDockDestination("schedule", "课表", Icons.Outlined.Home, Icons.Filled.Home),
                            LiquidDockDestination("services", "服务", Icons.Outlined.Home, Icons.Filled.Home),
                        ),
                        selectedIndex = selectedTab,
                        onSelect = {
                            selectedTab = it
                            latestTab = it
                        },
                        backdrop = backdrop,
                        modifier = Modifier.testTag("swipe_dock"),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("swipe_toggle").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertEquals(true, latestChecked)
        composeRule.onNodeWithTag("swipe_toggle").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(false, latestChecked)

        composeRule.onNodeWithTag("swipe_dock").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertEquals(true, latestTab > 0)
    }

    @Test
    @Config(sdk = [35])
    fun liquidTextButtonIconFabAndSegmentedTabsWorkInBothStyles() {
        var textClicked = false
        var iconClicked = false
        var fabClicked = false
        var selectedSegment = 0
        composeRule.setContent {
            var style by remember { mutableStateOf(UiStyle.LIQUID_GLASS) }
            UpcPocketTheme(uiStyle = style) {
                Column(Modifier.padding(16.dp)) {
                    LiquidTextButton(onClick = { textClicked = true }) {
                        Text("文本操作")
                    }
                    LiquidIconButton(
                        onClick = { iconClicked = true },
                        tonal = true,
                        modifier = Modifier.testTag("liquid_icon_btn"),
                    ) {
                        Icon(Icons.Filled.Home, contentDescription = "图标按钮")
                    }
                    LiquidFloatingActionButton(
                        onClick = {
                            fabClicked = true
                            style = UiStyle.MATERIAL_3
                        },
                        modifier = Modifier.testTag("liquid_fab_btn"),
                    ) {
                        Icon(Icons.Outlined.Home, contentDescription = "悬浮按钮")
                    }
                    LiquidSegmentedTabs(
                        tabs = listOf("活动", "我的"),
                        selectedIndex = selectedSegment,
                        onSelect = { selectedSegment = it },
                        tabStyle = true,
                    )
                }
            }
        }
        composeRule.onNodeWithText("文本操作").performClick()
        composeRule.onNodeWithTag("liquid_icon_btn").performClick()
        composeRule.onNodeWithText("我的").performClick()
        composeRule.onNodeWithTag("liquid_fab_btn").performClick()
        composeRule.waitForIdle()
        assertEquals(true, textClicked)
        assertEquals(true, iconClicked)
        assertEquals(1, selectedSegment)
        assertEquals(true, fabClicked)
        composeRule.onNodeWithText("文本操作").assertIsDisplayed()
        composeRule.onNodeWithText("我的").assertIsDisplayed()
    }
}
