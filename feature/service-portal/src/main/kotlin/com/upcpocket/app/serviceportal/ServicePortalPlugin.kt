package com.upcpocket.app.serviceportal

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
import com.upcpocket.app.upcauth.ServicePortalAuthenticator

object ServicePortalCapabilities {
    val read = capabilityKey<ServicePortalService>("serviceportal.read")
}

val SERVICE_PORTAL_PLUGIN_ID = PluginId.parse("serviceportal")
val SERVICE_PORTAL_ROUTE = PluginRouteId.parse("serviceportal.home")

class ServicePortalPlugin(
    account: SchoolAccountReferenceReader,
    authenticator: ServicePortalAuthenticator,
) : Plugin, PluginUiContributor {
    override val descriptor = PluginDescriptor(
        id = SERVICE_PORTAL_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(ServicePortalCapabilities.read.id),
    )
    val service = ServicePortalService(account, authenticator)

    override val pluginId get() = descriptor.id
    override fun capabilities() = listOf(PluginCapability(ServicePortalCapabilities.read, service))

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(PluginPage(SERVICE_PORTAL_ROUTE, pluginId) { host ->
            ServicePortalScreen(service, host::back, host::openSchoolAccountSettings)
        })
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse("serviceportal.home"),
                pluginId = pluginId,
                surface = UiSurface.HOME_SERVICES,
                order = 50,
                title = "e站通",
                subtitle = "办事服务",
                icon = UiIcon.TODO,
                route = SERVICE_PORTAL_ROUTE,
            ),
        )
    }
}
