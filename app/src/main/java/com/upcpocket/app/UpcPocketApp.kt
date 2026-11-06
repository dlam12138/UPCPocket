package com.upcpocket.app

import android.app.Application
import android.webkit.WebView
import com.upcpocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 启动只做宿主级工作：装配插件、让已激活插件做轻量初始化、恢复各自可恢复状态。
 * 具体 feature 是否初始化由激活插件决定；未注册/未激活的 feature 不会收到回调。
 *
 * ⚠️ 上游项目在这里排了「每 4 小时采样宿舍电量」的 WorkManager 周期任务。
 * 那个任务已经**随电费功能整体删除**，不再有 EnergySamplingWorker.schedule() 这一类调用 ——
 * 本应用没有任何周期性后台联网任务（WorkManager 依赖也已从构建脚本移除）。
 */
class UpcPocketApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // 诊断 sink 在所有 feature 之前安装；release 也启用，不依赖 BuildConfig.DEBUG。
        AppGraph.installDiagnostics()
        AppGraph.startPlugins()
        AppGraph.notifyPluginsCreated()

        // 仅调试包开启 WebView 远程调试，便于核对图书馆的官方登录页；
        // release 包不会开启，也不暴露任何页面内容。
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        // 先恢复统一账号并完成上次中断的清理，再允许插件恢复自己的业务状态。
        // 账号恢复只读本机密文/状态文件，不联网、不遍历业务。
        appScope.launch {
            AppGraph.account.restore()
            AppGraph.restorePlugins()
        }

        // 诊断开关是用户偏好；DataStore 读取完成前使用默认开启，之后立即同步。
        appScope.launch {
            AppGraph.uiPrefsStore.flow.collect { prefs ->
                AppGraph.diagnostics.setEnabled(prefs.diagnosticsEnabled)
            }
        }

        // 账号状态变化时只迁移本机余额缓存并重绘组件；清理中/LOADING 会让
        // 组件立即撤下旧身份数值，不等待新账号查询成功。
        appScope.launch {
            AppGraph.account.state.collect { state ->
                // 有完整有效学号的统一账号成立后，旧版明文草稿不再是任何数据源；
                // 即使余额插件未激活也在这里删除，避免遗留明文。
                if (state.isCredentialSaved && state.studentIdAvailable) {
                    runCatching { AppGraph.cardInputRepository.clear() }
                }
                runCatching { AppGraph.prepareCardForAccount() }
                AppGraph.requestCardWidgetUpdate()
                AppGraph.requestTimetableWidgetUpdate()
            }
        }

        // 课表查询成功/缓存落地后只重绘桌面组件，不额外联网；组件仍然只在点击刷新时
        // 由 Worker 发起一次受控查询。这里只观察公开的周状态。
        appScope.launch {
            val service = AppGraph.timetableServiceOrNull ?: return@launch
            service.state.collect { state ->
                if (state.weekState == com.upcpocket.app.timetable.TimetableWeekState.READY ||
                    state.weekState == com.upcpocket.app.timetable.TimetableWeekState.CACHED ||
                    state.weekState == com.upcpocket.app.timetable.TimetableWeekState.EMPTY
                ) {
                    AppGraph.requestTimetableWidgetUpdate()
                }
            }
        }
    }
}