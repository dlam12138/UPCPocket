package com.upcpocket.app.power

import android.content.Context
import com.upcpocket.app.data.power.PowerReadingRepository
import com.upcpocket.app.data.power.PowerSnapshotStore
import com.upcpocket.app.data.power.TangdaowanElectricityService
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import java.io.File

/** 电量插件公开能力的稳定键。 */
object PowerCapabilities {
    val tangdaowan = capabilityKey<PowerService>("power.tangdaowan")
}

val POWER_PLUGIN_ID: PluginId = PluginId.parse("power")

/**
 * 唐岛湾剩余电量插件。
 *
 * 学校页面按“楼号＋房间号”查询，不涉及登录态；插件构造不联网、不读快照文件。
 * 快照文件沿用 `power-reading-v1.json`。
 */
class PowerPlugin(
    context: Context,
    inputStore: PowerInputStore,
    onSnapshotChanged: () -> Unit,
) : Plugin {

    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = POWER_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(PowerCapabilities.tangdaowan.id),
    )

    private val electricity: TangdaowanElectricityService by lazy { TangdaowanElectricityService() }

    val service: PowerService = PowerService(
        repositoryProvider = {
            PowerReadingRepository(
                PowerSnapshotStore(File(context.applicationContext.noBackupFilesDir, SNAPSHOT_FILE)),
            ) { building, room -> electricity.query(building, room) }
        },
        electricity = electricity,
        input = inputStore,
        onSnapshotChanged = onSnapshotChanged,
    )

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(PowerCapabilities.tangdaowan, service))

    private companion object {
        const val SNAPSHOT_FILE = "power-reading-v1.json"
    }
}
