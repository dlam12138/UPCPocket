package com.upcpocket.app.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.ui.components.CampusPanel
import com.upcpocket.app.ui.components.GlassCard
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ThemeStyleCompositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(sdk = [31])
    fun material3ThemeProvidesOnSurfaceContentColorToRootCardAndPanel() {
        val expectedColor = AtomicReference(Color.Unspecified)
        val rootColor = AtomicReference(Color.Unspecified)
        val cardColor = AtomicReference(Color.Unspecified)
        val panelColor = AtomicReference(Color.Unspecified)

        composeRule.setContent {
            UpcPocketTheme(darkMode = DarkMode.DARK, preset = ThemePreset.BLUE) {
                val onSurface = MaterialTheme.colorScheme.onSurface
                val inherited = LocalContentColor.current
                SideEffect {
                    expectedColor.set(onSurface)
                    rootColor.set(inherited)
                }
                GlassCard { RecordContentColor(cardColor) }
                CampusPanel(modifier = Modifier.size(120.dp)) { RecordContentColor(panelColor) }
                Column { Button(onClick = {}) { Text("Material 3") } }
            }
        }

        composeRule.waitForIdle()
        val expected = expectedColor.get()
        assertNotEquals(Color.Unspecified, expected)
        assertEquals(expected, rootColor.get())
        assertEquals(expected, cardColor.get())
        assertEquals(expected, panelColor.get())
    }
}

@Composable
private fun RecordContentColor(target: AtomicReference<Color>) {
    val contentColor = LocalContentColor.current
    SideEffect { target.set(contentColor) }
}
