package com.upcpocket.app.shiguang

import com.upcpocket.app.account.api.SchoolAccountReferenceReader
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
import com.upcpocket.app.upcauth.ShiguangAuthenticator
import com.upcpocket.app.upcauth.ShiguangInteractiveAuthenticator

object ShiguangCapabilities { val read = capabilityKey<ShiguangService>("shiguang.read") }
val SHIGUANG_PLUGIN_ID = PluginId.parse("shiguang")
val SHIGUANG_ROUTE = PluginRouteId.parse("shiguang.home")

class ShiguangPlugin(account: SchoolAccountReferenceReader, authenticator: ShiguangAuthenticator, interactiveAuthenticator: ShiguangInteractiveAuthenticator? = null) : Plugin, PluginUiContributor {
    override val descriptor = PluginDescriptor(id = SHIGUANG_PLUGIN_ID, version = "1.0.0", capabilities = setOf(ShiguangCapabilities.read.id))
    val service = ShiguangService(account, authenticator, interactiveAuthenticator = interactiveAuthenticator)
    override val pluginId get() = descriptor.id
    override fun capabilities() = listOf(PluginCapability(ShiguangCapabilities.read, service))
    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(PluginPage(SHIGUANG_ROUTE, pluginId) { host ->
            ShiguangScreen(service, host::back, host::openSchoolAccountSettings)
        })
        registry.addEntry(PluginEntry(UiEntryId.parse("shiguang.home"), pluginId, UiSurface.HOME_SERVICES,
            40, "石光", "活动与学时", UiIcon.INSIGHTS, SHIGUANG_ROUTE))
    }
}
