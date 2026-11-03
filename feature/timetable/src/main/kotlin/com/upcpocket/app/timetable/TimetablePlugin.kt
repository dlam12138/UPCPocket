package com.upcpocket.app.timetable

import android.content.Context
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
import com.upcpocket.app.upcauth.TimetableAuthenticator

object TimetableCapabilities {
    val week = capabilityKey<TimetableService>("timetable.week")
}

val TIMETABLE_PLUGIN_ID: PluginId = PluginId.parse("timetable")
val TIMETABLE_ROUTE: PluginRouteId = PluginRouteId.parse("timetable.week")

/**
 * 只读课表插件。
 *
 * 构造只装配协议、加密会话/缓存与认证适配，不联网、不读取账号、不预取整学期。
 * 认证由 :core:upc-auth 适配完成；插件只拿到业务 Cookie，不接触密码。
 */
class TimetablePlugin(
    context: Context,
    account: SchoolAccountReferenceReader,
    authenticator: TimetableAuthenticator,
) : Plugin, PluginUiContributor {

    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = TIMETABLE_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(TimetableCapabilities.week.id),
    )

    val service: TimetableService = TimetableService(
        account = account,
        authenticator = authenticator,
        api = TimetableApi(),
        sessionStore = TimetableSessionStore(context.applicationContext),
        cacheStore = TimetableCacheStore(context.applicationContext),
    )

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) = TimetableUi(service).contribute(registry)

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(TimetableCapabilities.week, service))
}

internal class TimetableUi(
    private val service: TimetableService,
) : PluginUiContributor {

    override val pluginId = TIMETABLE_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(TIMETABLE_ROUTE, TIMETABLE_PLUGIN_ID) { host ->
                TimetableScreen(
                    service = service,
                    onBack = host::back,
                    onOpenAccountSettings = host::openSchoolAccountSettings,
                )
            },
        )
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse("timetable.week"),
                pluginId = TIMETABLE_PLUGIN_ID,
                surface = UiSurface.MORE_LEARNING,
                order = 10,
                title = "课表",
                subtitle = "学期、教学周、周网格与今日课程（只读）",
                icon = UiIcon.TIMETABLE,
                route = TIMETABLE_ROUTE,
            ),
        )
    }
}
