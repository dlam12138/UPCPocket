package com.upcpocket.app.library

import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
import com.upcpocket.app.data.chaoxing.ChaoxingTenant
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.library.ChaoxingLoginScreen
import com.upcpocket.app.ui.library.ChaoxingReserveScreen
import com.upcpocket.app.ui.library.ChaoxingSeatScreen
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
val LIBRARY_CHAOXING_SEAT_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.chaoxing.seat")
val LIBRARY_CHAOXING_SEATENGINE_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.chaoxing.seatengine")
val LIBRARY_CHAOXING_RESERVE_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.chaoxing.reserve")
val LIBRARY_CHAOXING_RESERVE_QIUSHI_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.chaoxing.reserve.qiushi")
val LIBRARY_CHAOXING_LOGIN_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.chaoxing.login")

/**
 * 图书馆插件的统一入口、libseat/nspace/Chaoxing 页面和设置里的登录区。
 *
 * 平台实现仍分别由 LibraryService/NspaceService/ChaoxingService 持有；UI 不共享
 * token/Cookie，也不把官方页面包装成原生能力。
 */
internal class LibraryUi(
    private val service: LibraryService,
    private val nspaceService: NspaceService,
    private val chaoxingService: ChaoxingService,
) : PluginUiContributor {

    override val pluginId = LIBRARY_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(LIBRARY_HOME_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                LibraryHubScreen(
                    libraryService = service,
                    nspaceService = nspaceService,
                    chaoxingService = chaoxingService,
                    onBack = host::back,
                    onOpenSeat = { host.open(LIBRARY_SEAT_ROUTE) },
                    onOpenNspace = { host.open(LIBRARY_NSPACE_ROUTE) },
                    onOpenChaoxingSeat = { kind ->
                        host.open(
                            if (kind == ChaoxingSeatKind.SEATENGINE) {
                                LIBRARY_CHAOXING_SEATENGINE_ROUTE
                            } else {
                                LIBRARY_CHAOXING_SEAT_ROUTE
                            },
                        )
                    },
                    onOpenChaoxingReserve = { tenant ->
                        host.open(
                            if (tenant == ChaoxingTenant.QIUSHI) {
                                LIBRARY_CHAOXING_RESERVE_QIUSHI_ROUTE
                            } else {
                                LIBRARY_CHAOXING_RESERVE_ROUTE
                            },
                        )
                    },
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
        registry.addPage(
            PluginPage(LIBRARY_CHAOXING_SEAT_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                ChaoxingSeatScreen(
                    service = chaoxingService,
                    initialKind = ChaoxingSeatKind.SEAT,
                    onBack = host::back,
                    onOpenLogin = { host.open(LIBRARY_CHAOXING_LOGIN_ROUTE) },
                )
            },
        )
        registry.addPage(
            PluginPage(LIBRARY_CHAOXING_SEATENGINE_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                ChaoxingSeatScreen(
                    service = chaoxingService,
                    initialKind = ChaoxingSeatKind.SEATENGINE,
                    onBack = host::back,
                    onOpenLogin = { host.open(LIBRARY_CHAOXING_LOGIN_ROUTE) },
                )
            },
        )
        registry.addPage(
            PluginPage(LIBRARY_CHAOXING_RESERVE_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                ChaoxingReserveScreen(
                    service = chaoxingService,
                    initialTenant = ChaoxingTenant.GUZHENKOU,
                    onBack = host::back,
                    onOpenLogin = { host.open(LIBRARY_CHAOXING_LOGIN_ROUTE) },
                )
            },
        )
        registry.addPage(
            PluginPage(LIBRARY_CHAOXING_RESERVE_QIUSHI_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                ChaoxingReserveScreen(
                    service = chaoxingService,
                    initialTenant = ChaoxingTenant.QIUSHI,
                    onBack = host::back,
                    onOpenLogin = { host.open(LIBRARY_CHAOXING_LOGIN_ROUTE) },
                )
            },
        )
        registry.addPage(
            PluginPage(LIBRARY_CHAOXING_LOGIN_ROUTE, LIBRARY_PLUGIN_ID) { host ->
                ChaoxingLoginScreen(
                    service = chaoxingService,
                    onBack = host::back,
                    onLoggedIn = host::back,
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
