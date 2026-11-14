package com.upcpocket.app.card

import android.content.Context
import com.upcpocket.app.data.cardhistory.CardHistoryClient
import com.upcpocket.app.data.cardhistory.CardHistoryAuthenticator
import com.upcpocket.app.data.cardhistory.CardHistoryInteractiveAuthenticator
import com.upcpocket.app.account.api.SchoolAccountStudentIdentityReader
import com.upcpocket.app.data.ecard.DirectCardBalanceRepository
import com.upcpocket.app.data.ecard.DirectCardBalanceStore
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry

/** 余额插件公开能力的稳定键。 */
object CardCapabilities {
    val balance = capabilityKey<CardBalanceService>("card.balance")
    val history = capabilityKey<CardHistoryClient>("card.history")
}

/** 稳定插件 ID；不能随 Kotlin 类名或页面标题变化。 */
val CARD_PLUGIN_ID: PluginId = PluginId.parse("card")

/**
 * 校园卡余额插件。
 *
 * 构造只创建仓库对象，不读网络、不写文件；真正的查询由用户点击触发。
 * 查询目标只来自宿主传入的受控账号身份面；插件不接触密码或凭据解密。
 */
class CardPlugin(
    context: Context,
    account: SchoolAccountStudentIdentityReader,
    inputStore: CardInputStore,
    onSnapshotChanged: () -> Unit,
    historyAuthenticator: CardHistoryAuthenticator? = null,
    interactiveHistoryAuthenticator: CardHistoryInteractiveAuthenticator? = null,
) : Plugin, PluginUiContributor {

    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = CARD_PLUGIN_ID,
        version = "1.0.0",
        capabilities = buildSet {
            add(CardCapabilities.balance.id)
            if (historyAuthenticator != null) add(CardCapabilities.history.id)
        },
    )

    val service: CardBalanceService = CardBalanceService(
        repositoryProvider = {
            DirectCardBalanceRepository(
                store = DirectCardBalanceStore(context.applicationContext.noBackupFilesDir),
                account = account,
            )
        },
        account = account,
        legacyInput = inputStore,
        onSnapshotChanged = onSnapshotChanged,
    )

    val history: CardHistoryClient? = historyAuthenticator?.let {
        CardHistoryClient(account, it, interactiveAuthenticator = interactiveHistoryAuthenticator)
    }

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) = CardUi(service, history).contribute(registry)

    override fun capabilities(): List<PluginCapability> =
        buildList {
            add(PluginCapability(CardCapabilities.balance, service))
            history?.let { add(PluginCapability(CardCapabilities.history, it)) }
        }
}
