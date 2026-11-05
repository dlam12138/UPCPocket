package com.upcpocket.app.library

import com.upcpocket.app.data.chaoxing.ChaoxingSeatKind
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
import com.upcpocket.app.ui.library.ChaoxingSeatScreen
import com.upcpocket.app.ui.library.LibraryHubScreen
import com.upcpocket.app.ui.library.LibrarySeatLoginScreen
import com.upcpocket.app.ui.library.LibrarySeatScreen

val LIBRARY_HOME_ROUTE: PluginRouteId = PluginRouteId.parse("library.home")
val LIBRARY_SEAT_ROUTE: PluginRouteId = PluginRouteId.parse("library.seat")
val LIBRARY_LOGIN_ROUTE: PluginRouteId = PluginRouteId.parse("library.login")
val LIBRARY_CHAOXING_SEAT_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.chaoxing.seat")
val LIBRARY_CHAOXING_SEATENGINE_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.chaoxing.seatengine")
val LIBRARY_CHAOXING_LOGIN_ROUTE: PluginRouteId =
    PluginRouteId.parse("library.chaoxing.login")

/**
 * 已撤下的研讨间路由只做兼容落点，不注册任何房间/预约页面或接口。
 *
 * 旧版本可能把这些 route id 留在返回栈/深链里；统一落到图书馆首页，避免
 * 未知路由崩溃，同时不重新暴露研讨间能力。
 */
private val LEGACY_DISCUSSION_ROUTES = listOf(
    "library.nspace",
    "library.nspace.login",
    "library.nspace.reservations",
    "library.chaoxing.reserve",
    "library.chaoxing.reserve.qiushi",
).map(PluginRouteId::parse)

/**
 * 图书馆插件的统一入口和三个座位页面，以及设置里的 libseat 登录区。
 *
 * 研讨间/空间预约已撤下；平台实现只剩 LibraryService 与 ChaoxingService，
 * 两者不共享 token/Cookie。
 */
internal class LibraryUi(
    private val service: LibraryService,
    private val chaoxingService: ChaoxingService,
) : PluginUiContributor {

    override val pluginId = LIBRARY_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        registry.addLibraryHubPage(LIBRARY_HOME_ROUTE, service, chaoxingService)
        LEGACY_DISCUSSION_ROUTES.forEach { route ->
            registry.addLibraryHubPage(route, service, chaoxingService)
        }

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
                subtitle = "三馆座位",
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

private fun PluginUiRegistry.addLibraryHubPage(
    route: PluginRouteId,
    service: LibraryService,
    chaoxingService: ChaoxingService,
) {
    addPage(
        PluginPage(route, LIBRARY_PLUGIN_ID) { host ->
            LibraryHubScreen(
                libraryService = service,
                chaoxingService = chaoxingService,
                onBack = host::back,
                onOpenSeat = { host.open(LIBRARY_SEAT_ROUTE) },
                onOpenChaoxingSeat = { kind ->
                    host.open(
                        if (kind == ChaoxingSeatKind.SEATENGINE) {
                            LIBRARY_CHAOXING_SEATENGINE_ROUTE
                        } else {
                            LIBRARY_CHAOXING_SEAT_ROUTE
                        },
                    )
                },
                onOpenAccount = host::openSchoolAccountSettings,
            )
        },
    )
}
