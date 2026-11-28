package com.upcpocket.app.card

import com.upcpocket.app.data.cardhistory.CardHistoryClient
import com.upcpocket.app.ui.ecard.CardHistoryScreen
import com.upcpocket.app.ui.ecard.CardRechargeGuideScreen
import com.upcpocket.app.ui.ecard.DirectCardBalanceSection
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface

internal class CardUi(
    private val service: CardBalanceService,
    private val history: CardHistoryClient,
    private val onOpenOfficialPortal: (() -> Unit)?,
) : PluginUiContributor {

    override val pluginId = CARD_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        val rechargeRoute = PluginRouteId.parse("card.recharge")
        registry.addPage(PluginPage(rechargeRoute, CARD_PLUGIN_ID) { host ->
            CardRechargeGuideScreen(
                back = host::back,
                openOfficialPortal = onOpenOfficialPortal,
            )
        })
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse("card.recharge"),
                pluginId = CARD_PLUGIN_ID,
                surface = UiSurface.SERVICE_DIRECTORY,
                order = 10,
                title = "校园卡充值说明",
                subtitle = "前往学校官方渠道办理",
                icon = UiIcon.RECHARGE,
                route = rechargeRoute,
                category = UiCategory.LIFE,
                keywords = "校园卡 充值 饭卡 一卡通 官方",
            ),
        )

        val historyRoute = PluginRouteId.parse("card.history")
        registry.addPage(PluginPage(historyRoute, CARD_PLUGIN_ID) { host ->
            CardHistoryScreen(history, host::back, host::openSchoolAccountSettings)
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
                route = historyRoute,
                category = UiCategory.LIFE,
                keywords = "校园卡 流水 消费 记录 账单 收支",
            ),
        )

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
                    onOpenHistory = { host.open(historyRoute) },
                    onOpenRecharge = { host.open(rechargeRoute) },
                )
            },
        )
    }
}
