package com.upcpocket.app

import android.app.Application
import android.webkit.WebView
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.widget.WidgetAutoRefresh
import com.upcpocket.app.widget.WidgetUpdates
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
 * 桌面存在组件时，WorkManager 保留校园卡只读余额自动更新与课表本地重绘。
 * 不恢复上游宿舍电量采样，不周期登录 CAS 或拉取课表。
 */
class UpcPocketApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // Take over the old password store synchronously and offline before any restore path.
        // The persisted marker blocks account restore across a crash; clear widgets before the
        // legacy ciphertext and aliases are removed.
        val legacyUpgradePending = runCatching { AppGraph.account.beginLegacyCredentialUpgrade() }
            .getOrDefault(true)
        if (legacyUpgradePending) {
            WidgetAutoRefresh.cancel(this, com.upcpocket.app.widget.WidgetKind.CARD)
            WidgetAutoRefresh.cancel(this, com.upcpocket.app.widget.WidgetKind.TIMETABLE)
            runCatching {
                WidgetUpdates.updateKindNow(
                    this,
                    com.upcpocket.app.widget.WidgetKind.CARD,
                    scheduleTicker = false,
                )
                WidgetUpdates.updateKindNow(
                    this,
                    com.upcpocket.app.widget.WidgetKind.TIMETABLE,
                    scheduleTicker = false,
                )
            }
            runCatching { AppGraph.account.purgeLegacyPasswordMaterial() }
        }
        // 诊断 sink 在所有 feature 之前安装；release 也启用，不依赖 BuildConfig.DEBUG。
        AppGraph.installDiagnostics()
        AppGraph.startPlugins()
        AppGraph.notifyPluginsCreated()

        // 仅调试包开启 WebView 远程调试，便于核对图书馆的官方登录页；
        // release 包不会开启，也不暴露任何页面内容。
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        // Finish legacy-data deletion and cookie/cache invalidation before restoring account or
        // plugins. This path is offline; failed cleanup remains gated for an explicit retry.
        appScope.launch {
            if (legacyUpgradePending) {
                if (AppGraph.account.finishLegacyCredentialUpgrade() !=
                    com.upcpocket.app.account.api.SchoolAccountWriteResult.Success
                ) return@launch
            }
            AppGraph.account.restore()
            AppGraph.restorePlugins()
            WidgetUpdates.requestTodo(this@UpcPocketApp)
            WidgetAutoRefresh.reconcile(this@UpcPocketApp)
        }

        // 诊断开关是用户偏好；DataStore 读取完成前使用默认开启，之后立即同步。
        appScope.launch {
            var lastWidgetAppearance: com.upcpocket.app.designsystem.WidgetAppearance? = null
            AppGraph.uiPrefsStore.flow.collect { prefs ->
                AppGraph.diagnostics.setEnabled(prefs.diagnosticsEnabled)
                if (lastWidgetAppearance != prefs.widgetAppearance) {
                    lastWidgetAppearance = prefs.widgetAppearance
                    WidgetUpdates.requestAppearanceRedraw(this@UpcPocketApp)
                }
            }
        }

        // 账号状态变化时只迁移本机余额缓存并重绘组件；清理中/LOADING 会让
        // 组件立即撤下旧身份数值，不等待新账号查询成功。
        appScope.launch {
            AppGraph.account.state.collect { state ->
                runCatching { AppGraph.prepareCardForAccount() }
                AppGraph.requestCardWidgetUpdate()
                AppGraph.requestTimetableWidgetUpdate()
                WidgetAutoRefresh.reconcile(this@UpcPocketApp)
            }
        }

        // 课表查询成功/缓存落地后只重绘桌面组件，不额外联网。自动更新也只读本地。
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
