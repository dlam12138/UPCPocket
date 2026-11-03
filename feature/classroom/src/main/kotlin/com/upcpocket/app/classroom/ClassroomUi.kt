package com.upcpocket.app.classroom

import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import com.upcpocket.app.ui.classroom.FreeClassroomScreen

val CLASSROOM_ROUTE: PluginRouteId = PluginRouteId.parse("classroom.free")

/** 空教室插件贡献的首页入口与查询页；页面状态仍由原 ViewModel 持有。 */
internal class ClassroomUi(
    private val repository: FreeClassroomRepository,
) : PluginUiContributor {

    override val pluginId = CLASSROOM_PLUGIN_ID

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(CLASSROOM_ROUTE, CLASSROOM_PLUGIN_ID) { host ->
                FreeClassroomScreen(
                    repository = repository,
                    onBack = host::back,
                )
            },
        )
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse("classroom.free"),
                pluginId = CLASSROOM_PLUGIN_ID,
                surface = UiSurface.HOME_SERVICES,
                order = 1,
                title = "空闲教室",
                subtitle = "按校区、日期、楼宇和节次查询",
                icon = UiIcon.CLASSROOM,
                route = CLASSROOM_ROUTE,
            ),
        )
    }
}
