package com.upcpocket.app.card

import android.content.Context
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.data.cardhistory.CardHistoryClient
import com.upcpocket.app.data.cardrecharge.CardRechargeService
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry

object CardCapabilities {
    val balance = capabilityKey<CardBalanceService>("card.balance")
    val history = capabilityKey<CardHistoryClient>("card.history")
    /** Retained key for host cleanup compatibility; no recharge writer is registered. */
    val recharge = capabilityKey<CardRechargeService>("card.recharge")
}

val CARD_PLUGIN_ID: PluginId = PluginId.parse("card")

/** Read only campus card features; auth tokens are supplied only through host adapters. */
class CardPlugin(
    context: Context,
    account: SchoolAccountReferenceReader,
    backend: CardBackendDataSource,
    onSnapshotChanged: () -> Unit,
    private val onOpenOfficialPortal: (() -> Unit)? = null,
) : Plugin, PluginUiContributor {

    override val descriptor = PluginDescriptor(
        id = CARD_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(CardCapabilities.balance.id, CardCapabilities.history.id),
    )

    val service = CardBalanceService(context, account, backend, onSnapshotChanged)
    val history = CardHistoryClient(account, backend)

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) =
        CardUi(service, history, onOpenOfficialPortal).contribute(registry)

    override fun capabilities(): List<PluginCapability> = listOf(
        PluginCapability(CardCapabilities.balance, service),
        PluginCapability(CardCapabilities.history, history),
    )
}
