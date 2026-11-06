package com.upcpocket.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.ui.nav.AppNav
import com.upcpocket.app.ui.theme.UpcPocketTheme
import com.upcpocket.app.widget.WidgetLaunchTarget
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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

        // 更新提示只做短 Toast，不弹 Dialog、不阻断登录/预约；是否每天检查由控制器门闩决定。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.updates.notices.collect { message ->
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
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

    override fun onStart() {
        super.onStart()
        // 前台每天最多一次；控制器在发请求前写日期门闩。只取清单/签名元数据。
        AppGraph.onAppForeground()
    }

    override fun onResume() {
        super.onResume()
        // 系统安装页/未知来源设置返回后只查实际包版本，不依据 resultCode 猜测成功。
        AppGraph.updates.onInstallerReturn()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 保持 getIntent() 与最新投递一致；解析规则与 onCreate 完全相同。
        setIntent(intent)
        WidgetLaunchTarget.fromIntent(intent)?.let { widgetTarget.value = it }
    }
}
