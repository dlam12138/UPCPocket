package com.upcpocket.app.floorplan

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 离线卓越楼平面图插件。
 *
 * 只读取随包矢量资产，不依赖校园地图、定位、账号或网络；地图插件缺失或
 * 未授权定位时仍可独立使用。
 */
public class FloorPlanPlugin(context: Context) : Plugin, PluginUiContributor {
    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    private var cachedCatalog: FloorPlanCatalog? = null

    override val descriptor = PluginDescriptor(
        id = PluginId.parse("floorplan"),
        version = "1.0.0",
    )

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(ROUTE, pluginId) { host ->
                var loadAttempt by remember { mutableIntStateOf(0) }
                val state by produceState<FloorPlanPageState>(FloorPlanPageState.Loading, loadAttempt) {
                    value = FloorPlanPageState.Loading
                    value = try {
                        FloorPlanPageState.Ready(loadCatalog())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        FloorPlanPageState.Failed
                    } catch (_: FloorPlanDataException) {
                        FloorPlanPageState.Failed
                    }
                }
                when (val current = state) {
                    FloorPlanPageState.Loading -> FloorPlanLoadingScreen(onBack = host::back)
                    FloorPlanPageState.Failed -> FloorPlanLoadingScreen(
                        onBack = host::back,
                        onRetry = { loadAttempt++ },
                    )
                    is FloorPlanPageState.Ready -> FloorPlanScreen(
                        catalog = current.catalog,
                        onBack = host::back,
                    )
                }
            },
        )
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse(ROUTE.value),
                pluginId = pluginId,
                surface = UiSurface.SERVICE_DIRECTORY,
                order = 36,
                title = "卓越楼平面图",
                subtitle = "古镇口 · 离线楼层图",
                icon = UiIcon.MAP,
                route = ROUTE,
                category = UiCategory.STUDY,
                keywords = "卓越教学楼 卓越楼 古镇口 平面图 楼层 房号 教室 A233 B328 A B",
            ),
        )
    }

    private suspend fun loadCatalog(): FloorPlanCatalog = loadMutex.withLock {
        cachedCatalog ?: FloorPlanAssets.load(appContext).also { cachedCatalog = it }
    }

    private sealed interface FloorPlanPageState {
        data object Loading : FloorPlanPageState
        data object Failed : FloorPlanPageState
        data class Ready(val catalog: FloorPlanCatalog) : FloorPlanPageState
    }

    public companion object {
        public val ROUTE: PluginRouteId = PluginRouteId.parse("floorplan.home")
    }
}
