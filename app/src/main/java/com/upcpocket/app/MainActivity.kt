package com.upcpocket.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.prefs.UiPrefs
import com.upcpocket.app.todo.TodoLaunchContract
import com.upcpocket.app.ui.components.GlassEnvironment
import com.upcpocket.app.ui.nav.AppNav
import com.upcpocket.app.ui.theme.LocalUiStyle
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

    /** 通知点击要求打开待办 tab；具体条目由 TodoService.pendingOpenTask 传递。 */
    private val openTodo = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 只在真正新建时消费组件入口。配置变更/旋转时 savedInstanceState 非空，
        // 不再从旧 Intent 重新导航，避免把用户已切走的页面又弹回来。
        if (TodoActivityLaunch.shouldConsumeOnCreate(savedInstanceState)) {
            widgetTarget.value = WidgetLaunchTarget.fromIntent(intent)
            consumeTodoIntent(intent)
        } else {
            widgetTarget.value = null
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
            // 界面风格、配色与深浅色由同一份全局偏好驱动，保存后立即生效。
            val prefs by AppGraph.uiPrefsStore.flow
                .collectAsStateWithLifecycle(initialValue = UiPrefs())
            CompositionLocalProvider(LocalUiStyle provides prefs.uiStyle) {
                UpcPocketTheme(
                    darkMode = prefs.darkMode,
                    preset = prefs.themePreset,
                ) {
                    val lightStatusBar = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                    LaunchedEffect(lightStatusBar) {
                        WindowCompat.getInsetsController(window, window.decorView)
                            .isAppearanceLightStatusBars = lightStatusBar
                    }
                    GlassEnvironment {
                        AppNav(
                            launchTarget = widgetTarget.value,
                            onLaunchConsumed = { widgetTarget.value = null },
                            openTodo = openTodo.value,
                            onTodoConsumed = { openTodo.value = false },
                        )
                    }
                }
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
        consumeTodoIntent(intent)
    }

    private fun consumeTodoIntent(intent: Intent?) {
        when (val request = TodoActivityLaunch.fromIntent(intent)) {
            is TodoActivityLaunchRequest.OpenTask -> {
                val store = AppGraph.todoStoreOrNull ?: return
                store.openTask(request.taskId)
                openTodo.value = true
            }
            is TodoActivityLaunchRequest.CreateTask -> {
                if (AppGraph.todoStoreOrNull?.openCreate(request.requestId) == true) {
                    openTodo.value = true
                }
            }
            null -> Unit
        }
    }
}

internal sealed interface TodoActivityLaunchRequest {
    data class OpenTask(val taskId: String) : TodoActivityLaunchRequest
    data class CreateTask(val requestId: String) : TodoActivityLaunchRequest
}

internal object TodoActivityLaunch {
    fun fromIntent(intent: Intent?): TodoActivityLaunchRequest? = when (intent?.action) {
        TodoLaunchContract.ACTION_OPEN -> TodoLaunchContract.taskId(intent)
            ?.let { TodoActivityLaunchRequest.OpenTask(it) }
        TodoLaunchContract.ACTION_CREATE -> TodoLaunchContract.createRequestId(intent)
            ?.let { TodoActivityLaunchRequest.CreateTask(it) }
        else -> null
    }

    fun shouldConsumeOnCreate(savedInstanceState: Bundle?): Boolean = savedInstanceState == null
}
