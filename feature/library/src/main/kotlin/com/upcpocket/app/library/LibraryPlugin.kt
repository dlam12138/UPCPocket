package com.upcpocket.app.library

import android.content.Context
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.nspace.NspaceRepository
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry

/** 图书馆插件公开能力的稳定键；座位与空间会话是两个独立能力/对象。 */
object LibraryCapabilities {
    val seat = capabilityKey<LibraryService>("library.seat")
    val nspace = capabilityKey<NspaceService>("library.nspace")
}

val LIBRARY_PLUGIN_ID: PluginId = PluginId.parse("library")

/**
 * 图书馆插件。
 *
 * - libseat 保留既有内存会话与可选加密副本；
 * - nspace 使用独立业务 token、独立 `/spa` 模型、独立内存会话，绝不与 seat token 混用；
 * - 构造时不登录、不联网、不发起预约；需要用户主动进入页面/点击登录。
 */
class LibraryPlugin(
    context: Context,
    accountSettings: LibraryAccountSettings,
    account: SchoolAccountReferenceReader,
    identityVerifier: SchoolAccountIdentityVerifier,
    authenticator: LibraryAccountAuthenticator,
    nspaceAuthenticator: NspaceAccountAuthenticator,
    nspaceWebTokenExchanger: NspaceWebTokenExchanger? = null,
) : Plugin, PluginUiContributor {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = LIBRARY_PLUGIN_ID,
        version = "2.0.0",
        capabilities = setOf(
            LibraryCapabilities.seat.id,
            LibraryCapabilities.nspace.id,
        ),
    )

    val service: LibraryService = LibraryService(
        repository = LibrarySeatRepository(
            sessionAllowed = { !account.state.value.blocksPersonalSessions },
        ),
        sessionStore = SchoolSessionStore(context.applicationContext),
        settings = accountSettings,
        accountGate = account,
        accountReference = account,
        identityVerifier = identityVerifier,
        authenticator = authenticator,
        clearCookies = SchoolWebCookies::clearAllSchoolCookies,
    )

    val nspaceService: NspaceService = NspaceService(
        repository = NspaceRepository(),
        accountGate = account,
        accountReference = account,
        identityVerifier = identityVerifier,
        authenticator = nspaceAuthenticator,
        webTokenExchanger = nspaceWebTokenExchanger,
    )

    override fun capabilities(): List<PluginCapability> = listOf(
        PluginCapability(LibraryCapabilities.seat, service),
        PluginCapability(LibraryCapabilities.nspace, nspaceService),
    )

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) =
        LibraryUi(service, nspaceService).contribute(registry)
}
