package com.upcpocket.app.library

import android.content.Context
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.chaoxing.ChaoxingRepository
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.prefs.SchoolSessionStore
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry

/** 图书馆插件公开能力的稳定键；只保留 libseat 与 Chaoxing 座位。 */
object LibraryCapabilities {
    val seat = capabilityKey<LibraryService>("library.seat")
    val chaoxing = capabilityKey<ChaoxingService>("library.chaoxing")
}

val LIBRARY_PLUGIN_ID: PluginId = PluginId.parse("library")

/**
 * 图书馆插件。
 *
 * - 只保留 libseat 与 Chaoxing seat/seatengine 座位；
 * - 研讨间/空间预约已按用户决定撤下，不再装配对应页面、协议或会话；
 * - 构造时不登录、不联网、不发起预约；需要用户主动进入页面/点击登录。
 */
class LibraryPlugin(
    context: Context,
    accountSettings: LibraryAccountSettings,
    account: SchoolAccountReferenceReader,
    identityVerifier: SchoolAccountIdentityVerifier,
    authenticator: LibraryAccountAuthenticator,
) : Plugin, PluginUiContributor {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = LIBRARY_PLUGIN_ID,
        version = "2.1.0",
        capabilities = setOf(
            LibraryCapabilities.seat.id,
            LibraryCapabilities.chaoxing.id,
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

    /**
     * Chaoxing 是独立 Cookie 会话：只接受应用内受限 WebView 的官方登录，
     * 与 libseat seat token 不共享。
     */
    val chaoxingService: ChaoxingService = ChaoxingService(
        repository = ChaoxingRepository(),
        accountGate = account,
        accountReference = account,
        identityVerifier = identityVerifier,
    )

    override fun capabilities(): List<PluginCapability> = listOf(
        PluginCapability(LibraryCapabilities.seat, service),
        PluginCapability(LibraryCapabilities.chaoxing, chaoxingService),
    )

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) =
        LibraryUi(service, chaoxingService).contribute(registry)
}
