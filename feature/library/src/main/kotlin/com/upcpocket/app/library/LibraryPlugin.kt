package com.upcpocket.app.library

import android.content.Context
import com.upcpocket.app.account.api.SchoolAccountGate
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry

/** 图书馆插件公开能力的稳定键。 */
object LibraryCapabilities {
    val seat = capabilityKey<LibraryService>("library.seat")
}

val LIBRARY_PLUGIN_ID: PluginId = PluginId.parse("library")

/**
 * 图书馆座位插件。
 *
 * 登录仍走学校官方 WebView 页面；插件只持有内存会话与加密持久副本，
 * 构造时不登录、不联网、不发预约请求。
 */
class LibraryPlugin(
    context: Context,
    accountSettings: LibraryAccountSettings,
    accountGate: SchoolAccountGate,
) : Plugin, PluginUiContributor {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = LIBRARY_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(LibraryCapabilities.seat.id),
    )

    val service: LibraryService = LibraryService(
        repository = LibrarySeatRepository(
            sessionAllowed = { !accountGate.state.value.blocksPersonalSessions },
        ),
        sessionStore = SchoolSessionStore(context.applicationContext),
        settings = accountSettings,
        accountGate = accountGate,
        clearCookies = SchoolWebCookies::clearAllSchoolCookies,
    )

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(LibraryCapabilities.seat, service))

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) = LibraryUi(service).contribute(registry)
}
