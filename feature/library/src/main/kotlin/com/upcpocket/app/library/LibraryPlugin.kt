package com.upcpocket.app.library

import android.content.Context
import com.upcpocket.app.account.api.SchoolAccountIdentityVerifier
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.account.api.blocksPersonalSessions
import com.upcpocket.app.data.chaoxing.ChaoxingRepository
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.prefs.DataStoreLibraryHiddenSeatsStore
import com.upcpocket.app.data.prefs.LibraryHiddenSeatsStore
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
    chaoxingAuthenticator: ChaoxingAccountAuthenticator,
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
     * Chaoxing 是独立 Cookie 会话：优先使用保存的学校账号走公共 CAS 自动登录，
     * 验证码/二次验证才回退受限 WebView；与 libseat seat token 不共享。
     */
    val chaoxingService: ChaoxingService = ChaoxingService(
        repository = ChaoxingRepository(),
        accountGate = account,
        accountReference = account,
        identityVerifier = identityVerifier,
        authenticator = chaoxingAuthenticator,
    )

    /** 本机个人隐藏座位：独立 DataStore，与学校账号/会话无关。 */
    val hiddenSeatsStore: LibraryHiddenSeatsStore =
        DataStoreLibraryHiddenSeatsStore(context.applicationContext)

    override fun capabilities(): List<PluginCapability> = listOf(
        PluginCapability(LibraryCapabilities.seat, service),
        PluginCapability(LibraryCapabilities.chaoxing, chaoxingService),
    )

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) =
        LibraryUi(service, chaoxingService, hiddenSeatsStore).contribute(registry)
}
