package com.upcpocket.app.card

import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.ecard.DirectCardBalanceSection

/**
 * 余额插件在宿主首页贡献的内容。
 *
 * 宿主只决定区域位置和标题；输入、查询、清除与状态都留在这里，移除插件后
 * 首页不再出现余额功能区，也不会留下空的业务占位。
 */
internal class CardUi(
    private val service: CardBalanceService,
) : PluginUiContributor {

    override val pluginId = CARD_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        registry.addSection(
            PluginSection(
                entryId = UiEntryId.parse("card.balance.section"),
                pluginId = CARD_PLUGIN_ID,
                surface = UiSurface.HOME_CARD,
                order = 0,
            ) {
                DirectCardBalanceSection(service = service)
            },
        )
    }
}
