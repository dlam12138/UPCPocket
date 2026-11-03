package com.upcpocket.app

import android.app.Application
import android.webkit.WebView
import com.upcpocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    }
}