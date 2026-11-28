@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.upcpocket.app.ui.components

import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogProperties
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.designsystem.UiStyle
import com.upcpocket.app.ui.theme.LocalUiStyle
import com.upcpocket.app.ui.theme.UpcPocketTheme
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w412dp-h915dp")
class GlassDatePickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun glassContainerColorIsOpaqueInLightAndDarkThemes() {
        val darkTheme = mutableStateOf(false)
        val renderedColor = AtomicReference(Color.Unspecified)

        composeRule.setContent {
            UpcPocketTheme(
                darkMode = if (darkTheme.value) DarkMode.DARK else DarkMode.LIGHT,
                preset = ThemePreset.BLUE,
            ) {
                val pickerColors = glassDatePickerColors(
                    colors = DatePickerDefaults.colors(containerColor = Color.Transparent),
                    glass = true,
                )
                SideEffect { renderedColor.set(pickerColors.containerColor) }
            }
        }

        composeRule.waitForIdle()
        val lightColor = renderedColor.get()
        assertEquals(1f, lightColor.alpha, 0f)
        assertNotEquals(Color.Transparent, lightColor)

        composeRule.runOnIdle { darkTheme.value = true }
        composeRule.waitForIdle()
        val darkColor = renderedColor.get()
        assertEquals(1f, darkColor.alpha, 0f)
        assertNotEquals(Color.Transparent, darkColor)
        assertNotEquals(lightColor, darkColor)
    }

    @Test
    fun materialStylePassesNativeDatePickerColorsThroughUnchanged() {
        val originalColors = AtomicReference<DatePickerColors?>()
        val effectiveColors = AtomicReference<DatePickerColors?>()

        composeRule.setContent {
            UpcPocketTheme(darkMode = DarkMode.LIGHT, preset = ThemePreset.BLUE) {
                val colors = DatePickerDefaults.colors()
                val effective = glassDatePickerColors(colors = colors, glass = false)
                SideEffect {
                    originalColors.set(colors)
                    effectiveColors.set(effective)
                }
            }
        }

        composeRule.waitForIdle()
        assertSame(originalColors.get(), effectiveColors.get())
        assertEquals(1f, checkNotNull(effectiveColors.get()).containerColor.alpha, 0f)
    }

    @Test
    fun yearPickerRendersInLightGlassDarkGlassAndMaterialStyles() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val darkTheme = mutableStateOf(false)
            val style = mutableStateOf(UiStyle.LIQUID_GLASS)
            val initialDateMillis = LocalDate.of(2024, 1, 15)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

            composeRule.setContent {
                UpcPocketTheme(
                    darkMode = if (darkTheme.value) DarkMode.DARK else DarkMode.LIGHT,
                    preset = ThemePreset.BLUE,
                ) {
                    CompositionLocalProvider(LocalUiStyle provides style.value) {
                        val state = rememberDatePickerState(
                            initialSelectedDateMillis = initialDateMillis,
                        )
                        GlassDatePickerDialog(
                            onDismissRequest = {},
                            confirmButton = {
                                TextButton(onClick = {}) { Text("确定") }
                            },
                            dismissButton = {
                                TextButton(onClick = {}) { Text("取消") }
                            },
                            properties = DialogProperties(usePlatformDefaultWidth = false),
                        ) {
                            GlassDatePicker(
                                state = state,
                                colors = if (style.value == UiStyle.LIQUID_GLASS) {
                                    DatePickerDefaults.colors(containerColor = Color.Transparent)
                                } else {
                                    DatePickerDefaults.colors()
                                },
                            )
                        }
                    }
                }
            }

            composeRule.waitForIdle()
            openYearPickerAndSelectVisibleYear(displayedYear = 2024, selectedYear = 2023)

            composeRule.runOnIdle { darkTheme.value = true }
            composeRule.waitForIdle()
            openYearPickerAndSelectVisibleYear(displayedYear = 2023, selectedYear = 2024)

            composeRule.runOnIdle { style.value = UiStyle.MATERIAL3 }
            composeRule.waitForIdle()
            openYearPickerAndSelectVisibleYear(displayedYear = 2024, selectedYear = 2023)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun openYearPickerAndSelectVisibleYear(displayedYear: Int, selectedYear: Int) {
        composeRule.onNode(
            hasClickAction() and hasText("January $displayedYear", substring = true),
        ).assertExists().assertHasClickAction().performClick()
        composeRule.waitForIdle()

        val yearNode = composeRule.onNode(
            hasClickAction() and hasText(selectedYear.toString(), substring = true),
        )
        yearNode
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.waitForIdle()
    }
}
