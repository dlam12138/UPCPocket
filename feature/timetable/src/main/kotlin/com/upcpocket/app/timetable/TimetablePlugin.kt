package com.upcpocket.app.timetable

import android.content.Context
import com.upcpocket.app.account.api.SchoolAccountReferenceReader
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginBottomNav
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginSection
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface

object TimetableCapabilities {
    val week = capabilityKey<TimetableService>("timetable.week")
}

val TIMETABLE_PLUGIN_ID: PluginId = PluginId.parse("timetable")
val TIMETABLE_ROUTE: PluginRouteId = PluginRouteId.parse("timetable.week")

/** 宿主提交本次地点选择并返回已激活的地图页面。 */
fun interface TimetableCampusMap {
    fun select(location: String): PluginRouteId
}

/**
 * 只读课表插件。
 *
 * 构造只装配后端只读 source 与本地缓存，不联网、不读取账号、不预取整学期。
 * Pocket 身份凭证由宿主适配持有，feature 只获得课表结果。
 */
class TimetablePlugin(
    context: Context,
    account: SchoolAccountReferenceReader,
    backend: TimetableBackendSource,
    private val resolveCampusMap: () -> TimetableCampusMap? = { null },
) : Plugin, PluginUiContributor {

    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = TIMETABLE_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(TimetableCapabilities.week.id),
    )

    val service: TimetableService = TimetableService(
        account = account,
        backend = backend,
        sessionStore = TimetableSessionStore(context.applicationContext),
        cacheStore = TimetableCacheStore(context.applicationContext),
        colorStore = TimetableColorStore(context.applicationContext),
    )

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) =
        TimetableUi(service, resolveCampusMap).contribute(registry)

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(TimetableCapabilities.week, service))
}

internal class TimetableUi(
    private val service: TimetableService,
    private val resolveCampusMap: () -> TimetableCampusMap? = { null },
) : PluginUiContributor {

    override val pluginId = TIMETABLE_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(TIMETABLE_ROUTE, TIMETABLE_PLUGIN_ID) { host ->
                val campusMap = resolveCampusMap()
                TimetableScreen(
                    service = service,
                    onOpenAccountSettings = host::openSchoolAccountSettings,
                    onOpenCampusLocation = campusMap?.let { map -> { location -> host.open(map.select(location)) } },
                )
            },
        )
        registry.addSection(
            PluginSection(
                entryId = UiEntryId.parse("timetable.today"),
                pluginId = TIMETABLE_PLUGIN_ID,
                surface = UiSurface.HOME_TIMETABLE,
                order = 0,
            ) { host ->
                val campusMap = resolveCampusMap()
                TimetableHomeSection(
                    service = service,
                    onOpenTimetable = { host.open(TIMETABLE_ROUTE) },
                    onOpenAccountSettings = host::openSchoolAccountSettings,
                    onOpenCampusLocation = campusMap?.let { map -> { location -> host.open(map.select(location)) } },
                )
            },
        )
        registry.addBottomNav(
            PluginBottomNav(
                entryId = UiEntryId.parse("timetable.tab"),
                pluginId = TIMETABLE_PLUGIN_ID,
                order = 5,
                label = "课表",
                icon = UiIcon.TIMETABLE,
                route = TIMETABLE_ROUTE,
            ),
        )
    }
}
