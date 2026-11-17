package com.upcpocket.app.card

import com.upcpocket.app.data.cardrecharge.CardRechargeService
import com.upcpocket.app.ui.ecard.CardRechargeScreen
import com.upcpocket.app.data.cardhistory.CardHistoryClient
import com.upcpocket.app.ui.ecard.CardHistoryScreen
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
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
    private val history: CardHistoryClient? = null,
    private val recharge: CardRechargeService? = null,
) : PluginUiContributor {

    override val pluginId = CARD_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        val rechargeRoute = PluginRouteId.parse("card.recharge")
        recharge?.let { client ->
            registry.addPage(PluginPage(rechargeRoute, CARD_PLUGIN_ID) { host ->
                CardRechargeScreen(client, host::back, host::openSchoolAccountSettings)
            })
            registry.addEntry(
                PluginEntry(
                    entryId = UiEntryId.parse("card.recharge"),
                    pluginId = CARD_PLUGIN_ID,
                    surface = UiSurface.SERVICE_DIRECTORY,
                    order = 10,
                    title = "校园卡充值",
                    subtitle = "微信充值",
                    icon = UiIcon.RECHARGE,
                    route = rechargeRoute,
                    category = UiCategory.LIFE,
                    keywords = "校园卡 充值 微信 饭卡 一卡通",
                ),
            )
        }
        val route = PluginRouteId.parse("card.history")
        history?.let { client ->
            registry.addPage(PluginPage(route, CARD_PLUGIN_ID) { host ->
                CardHistoryScreen(client, host::back, host::openSchoolAccountSettings)
            })
            registry.addEntry(
                PluginEntry(
                    entryId = UiEntryId.parse("card.history"),
                    pluginId = CARD_PLUGIN_ID,
                    surface = UiSurface.SERVICE_DIRECTORY,
                    order = 11,
                    title = "校园卡流水",
                    subtitle = "收支与账单",
                    icon = UiIcon.WALLET,
                    route = route,
                    category = UiCategory.LIFE,
                    keywords = "校园卡 流水 消费 记录 账单 收支",
                ),
            )
        }
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
                    onOpenHistory = history?.let { { host.open(route) } },
                    onOpenRecharge = recharge?.let { { host.open(rechargeRoute) } },
                )
            },
        )
    }
}
