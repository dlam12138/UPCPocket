package com.upcpocket.app.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
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
        composeRule.onNodeWithText("服务").performClick()
        assertEquals(true, cardClicked)
        assertEquals(true, buttonClicked)
        assertEquals(true, chipClicked)
        assertEquals(1, tabSelected)
    }
}
