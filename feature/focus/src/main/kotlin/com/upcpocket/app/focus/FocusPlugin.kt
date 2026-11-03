package com.upcpocket.app.focus

import android.app.PendingIntent
import android.content.Context
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.focus.FocusStatsScreen

/** 专注插件公开能力的稳定键。 */
object FocusCapabilities {
    val timer = capabilityKey<FocusService>("focus.timer")
}

val FOCUS_PLUGIN_ID: PluginId = PluginId.parse("focus")

val FOCUS_STATS_ROUTE: PluginRouteId = PluginRouteId.parse("focus.stats")

/**
 * 专注插件：Room 数据库、DataStore 快照、计时引擎、通知与前台服务都归本模块。
 *
 * 插件装配不打开数据库；`restore()` 仍由 Application 在启动时调用一次，行为与
 * 迁移前一致。前台服务通过 [FocusRuntime] 取已激活实例，不反向依赖宿主 AppGraph。
 */
class FocusPlugin(
    context: Context,
    private val openAppIntent: () -> PendingIntent,
) : Plugin, PluginUiContributor {

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
        // 插件启动成功后再发布给前台服务；构造/注册阶段不做安装或 IO。
        FocusRuntime.install(service)
        return listOf(PluginCapability(FocusCapabilities.timer, service))
    }

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(FOCUS_STATS_ROUTE, FOCUS_PLUGIN_ID) { host ->
                FocusStatsScreen(
                    service = service,
                    onBack = host::back,
                )
            },
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
