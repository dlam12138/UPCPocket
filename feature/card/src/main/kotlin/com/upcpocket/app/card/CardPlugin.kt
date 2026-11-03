package com.upcpocket.app.card

import android.content.Context
import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import java.io.File

/** 余额插件公开能力的稳定键。 */
object CardCapabilities {
    val balance = capabilityKey<CardBalanceService>("card.balance")
}

/** 稳定插件 ID；不能随 Kotlin 类名或页面标题变化。 */
val CARD_PLUGIN_ID: PluginId = PluginId.parse("card")

/**
 * 校园卡余额插件。
 *
 * 构造只创建仓库对象，不读网络、不写文件；真正的查询由用户点击触发。
 * 快照文件沿用 `card-balance-v1.json`，与重构前完全一致。
 */
class CardPlugin(
    context: Context,
    inputStore: CardInputStore,
    onSnapshotChanged: () -> Unit,
) : Plugin, PluginUiContributor {

    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = CARD_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(CardCapabilities.balance.id),
    )

    val service: CardBalanceService = CardBalanceService(
        repositoryProvider = {
            DirectCardBalanceRepository(
                DirectCardBalanceStore(File(context.applicationContext.noBackupFilesDir, SNAPSHOT_FILE)),
            )
        },
        input = inputStore,
        onSnapshotChanged = onSnapshotChanged,
    )

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) = CardUi(service).contribute(registry)

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(CardCapabilities.balance, service))

    private companion object {
        const val SNAPSHOT_FILE = "card-balance-v1.json"
    }
}
