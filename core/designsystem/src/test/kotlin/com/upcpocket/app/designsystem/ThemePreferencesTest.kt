package com.upcpocket.app.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferencesTest {
    @Test
    fun uiStyleEnumNamesRemainStableForDataStore() {
        assertEquals(UiStyle.LIQUID_GLASS, UiStyle.valueOf("LIQUID_GLASS"))
        assertEquals(UiStyle.MATERIAL_3, UiStyle.valueOf("MATERIAL_3"))
        assertEquals(2, UiStyle.entries.size)
    }
}
