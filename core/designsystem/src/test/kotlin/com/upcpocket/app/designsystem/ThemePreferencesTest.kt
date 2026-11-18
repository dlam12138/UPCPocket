package com.upcpocket.app.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferencesTest {
    @Test
    fun themePresetNamesRemainStableForDataStore() {
        assertEquals(ThemePreset.DYNAMIC, ThemePreset.valueOf("DYNAMIC"))
        assertEquals(ThemePreset.BLUE, ThemePreset.valueOf("BLUE"))
        assertEquals(8, ThemePreset.entries.size)
    }
}
