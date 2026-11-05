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
 * 宿主只决定区域位置、标题与“前往学校账号”的导航动作；查询目标、状态和清除
 * 余额缓存都留在插件内。
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
            ) { host ->
                DirectCardBalanceSection(
                    service = service,
                    onOpenAccountSettings = host::openSchoolAccountSettings,
                )
            },
        )
    }
}
