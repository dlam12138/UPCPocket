package com.upcpocket.app.campusmap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.upcpocket.app.plugin.ui.UiCategory
import com.upcpocket.app.plugin.ui.UiEntryId
import com.upcpocket.app.plugin.ui.UiIcon
import com.upcpocket.app.plugin.ui.UiSurface
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

internal sealed interface CampusMapPlaceRequest {
    data class TimetableText(val name: String) : CampusMapPlaceRequest
    data class Exact(val campus: CampusId, val placeId: String) : CampusMapPlaceRequest
}

public class CampusMapPlaceRequests {
    private val pending = AtomicReference<CampusMapPlaceRequest?>(null)

    public fun offer(name: String) {
        val text = name.trim().take(160)
        pending.set(text.takeIf { it.isNotEmpty() }?.let { CampusMapPlaceRequest.TimetableText(it) })
    }

    /** Open a mapped place in the specified campus using its stable asset ID. */
    public fun offer(campus: CampusId, placeId: String) {
        pending.set(CampusMapPlaceRequest.Exact(campus, placeId))
    }

    internal fun take(): CampusMapPlaceRequest? = pending.getAndSet(null)
}

public object CampusMapCapabilities {
    public val PLACE_REQUESTS = capabilityKey<CampusMapPlaceRequests>("campusmap.place-requests")
}

public class CampusMapPlugin(
    private val initialCampus: suspend () -> CampusId,
) : Plugin, PluginUiContributor {
    private val placeRequests = CampusMapPlaceRequests()

    override val descriptor = PluginDescriptor(
        id = PluginId.parse("campusmap"),
        version = "1.0.0",
        capabilities = setOf(CampusMapCapabilities.PLACE_REQUESTS.id),
    )

    override val pluginId get() = descriptor.id

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(CampusMapCapabilities.PLACE_REQUESTS, placeRequests))

    override fun contribute(registry: PluginUiRegistry) {
        registry.addPage(
            PluginPage(ROUTE, pluginId) { host ->
                val entry by produceState<MapEntry?>(initialValue = null) {
                    val requestedPlace = placeRequests.take()
                    val campus = try {
                        initialCampus()
                    } catch (_: IOException) {
                        CampusId.TDW
                    }
                    value = MapEntry(campus, requestedPlace)
                }
                val current = entry
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    CampusMapScreen(current.campus, current.requestedPlace, host::back)
                }
            },
        )
        registry.addEntry(
            PluginEntry(
                entryId = UiEntryId.parse("campusmap.home"),
                pluginId = pluginId,
                surface = UiSurface.SERVICE_DIRECTORY,
                order = 4,
                title = "校园地图",
                subtitle = "校内步行与骑行导航",
                icon = UiIcon.MAP,
                route = ROUTE,
                category = UiCategory.CAMPUS,
                keywords = "地图 导航 寻路 校园 唐岛湾 古镇口 步行 骑行 楼栋",
            ),
        )
    }

    private data class MapEntry(val campus: CampusId, val requestedPlace: CampusMapPlaceRequest?)

    public companion object {
        public val ROUTE = PluginRouteId.parse("campusmap.home")
    }
}
