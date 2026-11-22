package com.upcpocket.app.directory

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.ui.PluginEntry
import com.upcpocket.app.plugin.ui.PluginPage
import com.upcpocket.app.plugin.ui.PluginRouteId
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public fun interface DirectoryCampusMap {
    public fun select(location: DirectoryMapTarget): PluginRouteId
}

public class DirectoryPlugin(
    context: Context,
    private val initialCampus: suspend () -> DirectoryCampus?,
    private val resolveCampusMap: () -> DirectoryCampusMap? = { null },
) : Plugin, PluginUiContributor {
    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    private var loadedData: DirectoryData? = null

    override val descriptor = PluginDescriptor(
        id = PluginId.parse("directory"),
        version = "1.0.0",
    )

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(ROUTE, pluginId) { host ->
                var loadAttempt by remember { mutableIntStateOf(0) }
                val entry by produceState<DirectoryEntry>(DirectoryEntry.Loading, loadAttempt) {
                    value = DirectoryEntry.Loading
                    val campus = try {
                        initialCampus()
                    } catch (_: IOException) {
                        null
                    }
                    value = try {
                        DirectoryEntry.Ready(loadData(), campus)
                    } catch (_: IOException) {
                        DirectoryEntry.Failed
                    } catch (_: IllegalArgumentException) {
                        DirectoryEntry.Failed
                    }
                }
                when (val current = entry) {
                    DirectoryEntry.Loading -> DirectoryLoadingScreen(host::back)
                    DirectoryEntry.Failed -> DirectoryLoadingScreen(host::back) { loadAttempt++ }
                    is DirectoryEntry.Ready -> {
                        val campusMap = resolveCampusMap()
                        DirectoryScreen(
                            data = current.data,
                            initialCampus = current.campus,
                            onBack = host::back,
                            onOpenMap = campusMap?.let { map -> { target -> host.open(map.select(target)) } },
                        )
                    }
                }
            },
        )
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse(ROUTE.value),
                pluginId = pluginId,
                surface = UiSurface.SERVICE_DIRECTORY,
                order = 5,
                title = "校园通讯录",
                subtitle = "查询学院、办事部门与联系方式",
                icon = UiIcon.APPS,
                route = ROUTE,
                category = UiCategory.CAMPUS,
                keywords = "通讯录 联系电话 单位 位置 学院 办事 部门 教务 唐岛湾 古镇口",
            ),
        )
    }

    private suspend fun loadData(): DirectoryData = loadMutex.withLock {
        loadedData ?: DirectoryData.load(appContext).also { loadedData = it }
    }

    private sealed interface DirectoryEntry {
        data object Loading : DirectoryEntry
        data object Failed : DirectoryEntry
        data class Ready(val data: DirectoryData, val campus: DirectoryCampus?) : DirectoryEntry
    }

    public companion object {
        public val ROUTE = PluginRouteId.parse("directory.home")
    }
}
