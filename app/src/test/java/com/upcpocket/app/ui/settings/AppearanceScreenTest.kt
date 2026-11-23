package com.upcpocket.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.upcpocket.app.data.prefs.UiPrefs
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp", application = Application::class)
class AppearanceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
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

    @Test
    fun colorConversionAndHexParsingWorkAccurately() {
        val seed = 0xFF1A73E8.toInt()
        val hsv = argbToHsv(seed)
        val roundTrip = hsvToArgb(hsv.hue, hsv.saturation, hsv.value)
        assertEquals(seed, roundTrip)
        assertEquals("#1A73E8", formatHexColor(seed))
        assertEquals(seed, parseHexColor("#1a73e8"))
        assertEquals(seed, parseHexColor("1A73E8"))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#ZZZZZZ"))
    }

    @Test
    fun appearanceScreenSupportsCustomBackgroundActionsAndColorPickerMode() {
        var pickCount = 0
        var clearCount = 0
        var appliedSeed: Int? = null
        val previewBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(AndroidColor.rgb(46, 125, 50))
        }.asImageBitmap()

        composeRule.setContent {
            var uiState by remember {
                mutableStateOf(
                    AppearanceUiState(
                        prefs = UiPrefs(
                            themePreset = ThemePreset.BLUE,
                            customThemeSeedArgb = 0xFF1A73E8.toInt(),
                            customBackgroundVersion = 1L,
                            customBackgroundOpacity = 0.60f,
                        ),
                        backgroundPreview = previewBitmap,
                        extractedBackgroundSeeds = listOf(0xFF2E7D32.toInt()),
                    ),
                )
            }
            UpcPocketTheme(
                darkMode = uiState.prefs.darkMode,
                preset = uiState.prefs.themePreset,
                customSeedArgb = uiState.prefs.customThemeSeedArgb,
            ) {
                AppearanceScreenContent(
                    uiState = uiState,
                    onBack = {},
                    onUiStyleChange = {},
                    onDarkModeChange = {},
                    onPresetChange = { preset ->
                        uiState = uiState.copy(prefs = uiState.prefs.copy(themePreset = preset))
                    },
                    onCustomSeedChange = { argb ->
                        appliedSeed = argb
                        uiState = uiState.copy(
                            prefs = uiState.prefs.copy(
                                themePreset = ThemePreset.CUSTOM,
                                customThemeSeedArgb = argb,
                            ),
                        )
                    },
                    onPickBackground = { pickCount++ },
                    onClearBackground = {
                        clearCount++
                        uiState = uiState.copy(
                            prefs = uiState.prefs.copy(customBackgroundVersion = 0L),
                            backgroundPreview = null,
                            extractedBackgroundSeeds = emptyList(),
                        )
                    },
                    onBackgroundOpacityChange = {},
                )
            }
        }

        // 自定义背景区：预览、可见度、更换/恢复默认
        composeRule.onNodeWithTag("custom_background_preview").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("custom_background_opacity_label").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("custom_background_upload_button").performScrollTo().performClick()
        assertEquals(1, pickCount)

        // 切换到取色卡模式
        composeRule.onNodeWithTag("theme_preset_custom").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("color_picker_panel").performScrollTo().assertIsDisplayed()

        // 点击背景图提取色
        composeRule.onNodeWithTag("background_seed_0").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0xFF2E7D32.toInt(), appliedSeed)

        // 非法十六进制不修改种子色并提示错误
        composeRule.onNodeWithTag("color_picker_hex_input").performScrollTo().performTextReplacement("#123")
        composeRule.onNodeWithTag("color_picker_hex_apply").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0xFF2E7D32.toInt(), appliedSeed)
        composeRule.onNodeWithText("请输入 6 位色值，例如 #1A73E8").performScrollTo().assertIsDisplayed()

        // 合法十六进制立即生效
        composeRule.onNodeWithTag("color_picker_hex_input").performScrollTo().performTextReplacement("#E8710A")
        composeRule.onNodeWithTag("color_picker_hex_apply").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0xFFE8710A.toInt(), appliedSeed)

        // 恢复默认背景
        composeRule.onNodeWithTag("custom_background_clear_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, clearCount)
        composeRule.onNodeWithText("上传图片").performScrollTo().assertIsDisplayed()
    }
}
