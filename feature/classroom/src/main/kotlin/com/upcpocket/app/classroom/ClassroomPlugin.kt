package com.upcpocket.app.classroom

import com.upcpocket.app.data.classroom.FreeClassroomCampusStore
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.NoOpFreeClassroomCampusStore
import com.upcpocket.app.data.classroom.PublicFreeClassroomRepository
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry

/** 空教室插件公开能力的稳定键。 */
object ClassroomCapabilities {
    val free = capabilityKey<FreeClassroomRepository>("classroom.free")
}

val CLASSROOM_PLUGIN_ID: PluginId = PluginId.parse("classroom")

/**
 * 空闲教室插件。
 *
 * 查询是公开只读页面，没有账号或后台任务；插件提供同一份 Repository，并注入
 * 只保存上次校区的偏好存储。日期、节次与查询结果不跨进程保存。
 */
class ClassroomPlugin(
    private val campusStore: FreeClassroomCampusStore = NoOpFreeClassroomCampusStore,
    private val repository: FreeClassroomRepository = PublicFreeClassroomRepository(),
) : Plugin, PluginUiContributor {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = CLASSROOM_PLUGIN_ID,
        version = "1.0.0",
        capabilities = setOf(ClassroomCapabilities.free.id),
    )

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(ClassroomCapabilities.free, repository))

    override val pluginId get() = descriptor.id

    override fun contribute(registry: PluginUiRegistry) =
        ClassroomUi(repository, campusStore).contribute(registry)
}
