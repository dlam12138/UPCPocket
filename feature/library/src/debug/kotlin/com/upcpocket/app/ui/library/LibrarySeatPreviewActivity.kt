package com.upcpocket.app.ui.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.upcpocket.app.designsystem.DarkMode
import com.upcpocket.app.designsystem.ThemePreset
import com.upcpocket.app.ui.theme.UpcPocketTheme

/**
 * Debug-only visual acceptance screen for the three library seat flows.
 *
 * All rooms, seats, coordinates and times below are synthetic. This Activity has
 * no account, session, network, cache or production write path. It is visible only
 * in debug builds and exists to capture light/dark/large-font review screenshots.
 */
class LibrarySeatPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = intent.getStringExtra("page") ?: "lib-area"
        val dark = intent.getBooleanExtra("dark", false)
        setContent {
            UpcPocketTheme(
                darkMode = if (dark) DarkMode.DARK else DarkMode.LIGHT,
                preset = ThemePreset.BLUE,
            ) {
                LibrarySeatPreviewPage(page = page)
            }
        }
    }
}
