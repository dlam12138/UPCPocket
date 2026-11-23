package com.upcpocket.app.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferencesTest {
    @Test
    fun themePresetNamesRemainStableForDataStore() {
        assertEquals(ThemePreset.DYNAMIC, ThemePreset.valueOf("DYNAMIC"))
        assertEquals(ThemePreset.PURPLE, ThemePreset.valueOf("PURPLE"))
        assertEquals(ThemePreset.BLUE, ThemePreset.valueOf("BLUE"))
        assertEquals(ThemePreset.TEAL, ThemePreset.valueOf("TEAL"))
        assertEquals(ThemePreset.GREEN, ThemePreset.valueOf("GREEN"))
        assertEquals(ThemePreset.AMBER, ThemePreset.valueOf("AMBER"))
        assertEquals(ThemePreset.PINK, ThemePreset.valueOf("PINK"))
        assertEquals(ThemePreset.GRAPHITE, ThemePreset.valueOf("GRAPHITE"))
        assertEquals(ThemePreset.CUSTOM, ThemePreset.valueOf("CUSTOM"))
        assertEquals(9, ThemePreset.entries.size)
    }

    @Test
    fun seedNormalizationAndOpacitySanitizationClampSafely() {
        assertEquals(0xFF1A73E8.toInt(), normalizeOpaqueSeedArgb(0x001A73E8))
        assertEquals(DEFAULT_CUSTOM_BACKGROUND_OPACITY, sanitizeCustomBackgroundOpacity(null), 0.0001f)
        assertEquals(DEFAULT_CUSTOM_BACKGROUND_OPACITY, sanitizeCustomBackgroundOpacity(Float.NaN), 0.0001f)
        assertEquals(MIN_CUSTOM_BACKGROUND_OPACITY, sanitizeCustomBackgroundOpacity(0.01f), 0.0001f)
        assertEquals(MAX_CUSTOM_BACKGROUND_OPACITY, sanitizeCustomBackgroundOpacity(1.5f), 0.0001f)
        assertEquals(0.62f, sanitizeCustomBackgroundOpacity(0.62f), 0.0001f)
    }
}
