package com.upcpocket.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.ui.nav.AppNav
import com.upcpocket.app.ui.theme.UpcPocketTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // 配色与深浅色是全局设置，直接在这里喂给主题（改完立刻全应用生效）
            val prefs by AppGraph.uiPrefsStore.flow
                .collectAsStateWithLifecycle(initialValue = UiPrefs())
            UpcPocketTheme(
                darkMode = prefs.darkMode,
                preset = prefs.themePreset,
            ) {
                AppNav()
            }
        }
    }
}
