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
import com.upcpocket.app.ui.library.LibrarySeatLoginScreen
import com.upcpocket.app.ui.library.LibrarySeatScreen

val LIBRARY_SEAT_ROUTE: PluginRouteId = PluginRouteId.parse("library.seat")
val LIBRARY_LOGIN_ROUTE: PluginRouteId = PluginRouteId.parse("library.login")

/**
 * 图书馆插件贡献的首页入口、座位/登录页面和“更多”里的学校登录区。
 *
 * 页面内部仍由原 Repository/ViewModel 负责请求与会话；插件只把宿主提供的
 * 导航、偏好和 Cookie 清理适配器接起来。
 */
internal class LibraryUi(
    private val service: LibraryService,
) : PluginUiContributor {

    override val pluginId = LIBRARY_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
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
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse("library.seat"),
                pluginId = LIBRARY_PLUGIN_ID,
                surface = UiSurface.HOME_SERVICES,
                order = 2,
                title = "图书馆座位预约",
                subtitle = "学校统一认证登录后选馆区、座位与时间",
                icon = UiIcon.LIBRARY,
                route = LIBRARY_SEAT_ROUTE,
            ),
        )
        registry.addSection(
            PluginSection(
                entryId = UiEntryId.parse("library.account.section"),
                pluginId = LIBRARY_PLUGIN_ID,
                surface = UiSurface.MORE_SCHOOL,
                order = 0,
            ) {
                LibraryAccountSection(service = service)
            },
        )
    }
}
