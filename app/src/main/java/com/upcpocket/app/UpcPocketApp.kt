package com.upcpocket.app

import android.app.Application
import com.upcpocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * M1 只做三件事：初始化本地依赖容器、建好番茄钟通知通道、把上一次进程遗留的计时状态接回来。
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
        AppGraph.pomodoroNotifications.ensureChannels()

        // 番茄钟可能在上一轮进程里就被杀了 —— 启动时把状态接回来。
        // 剩余时间是用墙钟算的，所以"接回来"之后不用做任何补偿。
        appScope.launch {
            runCatching { AppGraph.pomodoroEngine.restore() }
        }
    }
}