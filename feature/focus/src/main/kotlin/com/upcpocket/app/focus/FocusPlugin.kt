package com.upcpocket.app.focus

import android.app.PendingIntent
import android.content.Context
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginAppLifecycle
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginBottomNav
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.focus.FocusScreen
import com.upcpocket.app.ui.focus.FocusStatsScreen

/** 专注插件公开能力的稳定键。 */
object FocusCapabilities {
    val timer = capabilityKey<FocusService>("focus.timer")
}

val FOCUS_PLUGIN_ID: PluginId = PluginId.parse("focus")

/** 保留迁移前的 `focus` 路由，避免升级后恢复的返回栈找不到 tab 页面。 */
val FOCUS_MAIN_ROUTE: PluginRouteId = PluginRouteId.parse("focus")

val FOCUS_STATS_ROUTE: PluginRouteId = PluginRouteId.parse("focus.stats")

/**
 * 专注插件：Room 数据库、DataStore 快照、计时引擎、通知与前台服务都归本模块。
 *
 * 插件装配不打开数据库；只有插件成功激活后，宿主才会通过 [PluginAppLifecycle]
 * 回调建通知通道并恢复快照。顶层 tab、统计页和入口都由本插件贡献；未注册时
 * 宿主不会启动数据库、服务或留下可点入口。
 */
class FocusPlugin(
    context: Context,
    private val openAppIntent: () -> PendingIntent,
) : Plugin, PluginUiContributor, PluginAppLifecycle {

    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = FOCUS_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(FocusCapabilities.timer.id),
    )

    val service: FocusService = FocusService(
        context = context,
        openAppIntent = openAppIntent,
    )

    override fun capabilities(): List<PluginCapability> {
        // 能力枚举只返回纯内存对象；真正安装到进程内桥要等插件激活后的生命周期回调。
        return listOf(PluginCapability(FocusCapabilities.timer, service))
    }

    override fun onAppCreated() {
        // 只有已激活插件才会走到这里；失败/被隔离的插件不会留下可用的前台服务实例。
        FocusRuntime.install(service)
        service.ensureChannels()
    }

    override suspend fun onAppStart() {
        service.restore()
    }

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(FOCUS_MAIN_ROUTE, FOCUS_PLUGIN_ID) { host ->
                FocusScreen(
                    service = service,
                    onOpenStats = { host.open(FOCUS_STATS_ROUTE) },
                )
            },
        )
        registry.addPage(
            PluginPage(FOCUS_STATS_ROUTE, FOCUS_PLUGIN_ID) { host ->
                FocusStatsScreen(
                    service = service,
                    onBack = host::back,
                )
            },
        )
        registry.addBottomNav(
            PluginBottomNav(
                entryId = UiEntryId.parse("focus.tab"),
                pluginId = FOCUS_PLUGIN_ID,
                order = 10,
                label = "专注",
                icon = UiIcon.TIMER,
                route = FOCUS_MAIN_ROUTE,
            ),
        )
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse("focus.stats"),
                pluginId = FOCUS_PLUGIN_ID,
                surface = UiSurface.MORE_LEARNING,
                order = 0,
                title = "学习统计",
                subtitle = "专注时长、热力图、标签分布",
                icon = UiIcon.INSIGHTS,
                route = FOCUS_STATS_ROUTE,
            ),
        )
    }
}
