package com.upcpocket.app.library

import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.library.LibraryHubScreen
import com.upcpocket.app.ui.library.LibrarySeatLoginScreen
import com.upcpocket.app.ui.library.LibrarySeatScreen
import com.upcpocket.app.ui.library.NspaceScreen
import com.upcpocket.app.ui.library.NspaceReservationsScreen
import com.upcpocket.app.ui.library.NspaceWebLoginScreen

val LIBRARY_HOME_ROUTE: PluginRouteId = PluginRouteId.parse("library.home")
val LIBRARY_SEAT_ROUTE: PluginRouteId = PluginRouteId.parse("library.seat")
val LIBRARY_LOGIN_ROUTE: PluginRouteId = PluginRouteId.parse("library.login")
val LIBRARY_NSPACE_ROUTE: PluginRouteId = PluginRouteId.parse("library.nspace")
val LIBRARY_NSPACE_LOGIN_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.nspace.login")
val LIBRARY_NSPACE_RESERVATIONS_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.nspace.reservations")

/**
 * 图书馆插件的统一入口、libseat/nspace 页面、官方页面和设置里的登录区。
 *
 * 平台实现仍分别由 LibraryService/NspaceService 持有；UI 不共享 token，也不把
 * 未验证的 Chaoxing 接口包装成原生查询。
 */
internal class LibraryUi(
    private val service: LibraryService,
    private val nspaceService: NspaceService,
) : PluginUiContributor {

    override val pluginId = LIBRARY_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(LIBRARY_HOME_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                LibraryHubScreen(
                    libraryService = service,
                    nspaceService = nspaceService,
                    onBack = host::back,
                    onOpenSeat = { host.open(LIBRARY_SEAT_ROUTE) },
                    onOpenNspace = { host.open(LIBRARY_NSPACE_ROUTE) },
                    onOpenAccount = host::openSchoolAccountSettings,
                )
            },
        )
        registry.addPage(
            PluginPage(LIBRARY_SEAT_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                LibrarySeatScreen(
                    service = service,
                    onBack = host::back,
                    onLogin = { host.open(LIBRARY_LOGIN_ROUTE) },
                )
            },
        )
        registry.addPage(
            PluginPage(LIBRARY_LOGIN_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                LibrarySeatLoginScreen(
                    service = service,
                    onBack = host::back,
                    onLoggedIn = host::back,
                )
            },
        )
        registry.addPage(
            PluginPage(LIBRARY_NSPACE_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                NspaceScreen(
                    service = nspaceService,
                    onBack = host::back,
                    onOpenAccount = host::openSchoolAccountSettings,
                    onOpenReservations = { host.open(LIBRARY_NSPACE_RESERVATIONS_ROUTE) },
                    onOpenWebLogin = { host.open(LIBRARY_NSPACE_LOGIN_ROUTE) },
                )
            },
        )
        registry.addPage(
            PluginPage(LIBRARY_NSPACE_LOGIN_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                NspaceWebLoginScreen(
                    service = nspaceService,
                    onBack = host::back,
                    onLoggedIn = host::back,
                )
            },
        )
        registry.addPage(
            PluginPage(LIBRARY_NSPACE_RESERVATIONS_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                NspaceReservationsScreen(
                    service = nspaceService,
                    onBack = host::back,
                )
            },
        )
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse("library.home"),
                pluginId = LIBRARY_PLUGIN_ID,
                surface = UiSurface.HOME_SERVICES,
                order = 2,
                title = "图书馆",
                subtitle = "座位 · 研讨间 · 统一入口",
                icon = UiIcon.LIBRARY,
                route = LIBRARY_HOME_ROUTE,
            ),
        )
        registry.addSection(
            PluginSection(
                entryId = UiEntryId.parse("library.account.section"),
                pluginId = LIBRARY_PLUGIN_ID,
                surface = UiSurface.MORE_SCHOOL,
                order = 0,
            ) { _ ->
                LibraryAccountSection(service = service)
            },
        )
    }
}
