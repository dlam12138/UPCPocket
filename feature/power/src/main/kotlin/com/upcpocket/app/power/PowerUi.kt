package com.upcpocket.app.power

import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.power.ElectricityScreen
import com.upcpocket.app.ui.power.TangdaowanElectricityScreen

/** 稳定路由：桌面组件和插件内二级入口都只认这个 ID。 */
val POWER_ELECTRICITY_ROUTE: PluginRouteId = PluginRouteId.parse("power.electricity")
val POWER_TANGDAOWAN_ROUTE: PluginRouteId = PluginRouteId.parse("power.tangdaowan")

/**
 * 电量插件贡献的首页入口与两个页面。
 *
 * 首页只出现一个“电费”入口，进入后仍按现有页面先选校区；页面内导航由宿主执行，
 * 插件不持有 NavController，也不接受外部 Intent 转发。
 */
internal class PowerUi(
    private val service: PowerService,
) : PluginUiContributor {

    override val pluginId = POWER_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(POWER_ELECTRICITY_ROUTE, POWER_PLUGIN_ID) { host ->
                ElectricityScreen(
                    onBack = host::back,
                    onOpenTangdaowan = { host.open(POWER_TANGDAOWAN_ROUTE) },
                )
            },
        )
        registry.addPage(
            PluginPage(POWER_TANGDAOWAN_ROUTE, POWER_PLUGIN_ID) { host ->
                TangdaowanElectricityScreen(
                    service = service,
                    onBack = host::back,
                )
            },
        )
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse("power.electricity"),
                pluginId = POWER_PLUGIN_ID,
                surface = UiSurface.HOME_SERVICES,
                order = 0,
                title = "电费",
                subtitle = "唐岛湾 / 古镇口",
                icon = UiIcon.ELECTRICITY,
                route = POWER_ELECTRICITY_ROUTE,
            ),
        )
    }
}
