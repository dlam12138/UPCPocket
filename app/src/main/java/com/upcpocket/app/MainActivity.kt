package com.upcpocket.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.ui.nav.AppNav
import com.upcpocket.app.ui.theme.UpcPocketTheme
import com.upcpocket.app.widget.WidgetLaunchTarget

class MainActivity : ComponentActivity() {

    /**
     * 桌面组件带来的固定跳转目标。
     *
     * 只从 [WidgetLaunchTarget.fromIntent] 的白名单枚举取值；不读取、也不转发任意
     * Intent/URL。onCreate 与 onNewIntent 走同一套解析，冷启动和热启动行为一致。
     */
    private val widgetTarget = mutableStateOf<WidgetLaunchTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 只在真正新建时消费组件入口。配置变更/旋转时 savedInstanceState 非空，
        // 不再从旧 Intent 重新导航，避免把用户已切走的页面又弹回来。
        widgetTarget.value = if (savedInstanceState == null) {
            WidgetLaunchTarget.fromIntent(intent)
        } else {
            null
        }

        setContent {
            // 配色与深浅色是全局设置，直接在这里喂给主题（改完立刻全应用生效）
            val prefs by AppGraph.uiPrefsStore.flow
                .collectAsStateWithLifecycle(initialValue = UiPrefs())
            UpcPocketTheme(
                darkMode = prefs.darkMode,
                preset = prefs.themePreset,
            ) {
                AppNav(
                    launchTarget = widgetTarget.value,
                    onLaunchConsumed = { widgetTarget.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 保持 getIntent() 与最新投递一致；解析规则与 onCreate 完全相同。
        setIntent(intent)
        WidgetLaunchTarget.fromIntent(intent)?.let { widgetTarget.value = it }
    }
}
