package com.upcpocket.app.ui.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.ui.theme.UpcPocketTheme

/**
 * Debug-only visual acceptance screen for the three library seat flows and the
 * new time-search / hidden-seat states.
 *
 * All rooms, seats, coordinates and times are synthetic. This Activity has
 * no account, session, network, cache or production write path. It exists only
 * in debug builds to capture light/dark/large-font review screenshots.
 *
 * adb:
 *   am start -n com.upcpocket.app/com.upcpocket.app.ui.library.LibrarySeatPreviewActivity \
 *     --es page lib-timesearch --ez dark false --ef font 1.0
 */
class LibrarySeatPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = intent.getStringExtra("page") ?: "lib-area"
        val dark = intent.getBooleanExtra("dark", false)
        val font = intent.getFloatExtra("font", 1f).coerceIn(0.85f, 2f)
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, base.fontScale * font),
            ) {
                UpcPocketTheme(
                    darkMode = if (dark) DarkMode.DARK else DarkMode.LIGHT,
                    preset = ThemePreset.BLUE,
                ) {
                    LibrarySeatPreviewPage(page = page)
                }
            }
        }
    }
}
