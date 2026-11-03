package com.upcpocket.app.classroom

import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.PublicFreeClassroomRepository
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginCapability
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginId
import com.upcpocket.app.plugin.api.capabilityKey

/** 空教室插件公开能力的稳定键。 */
object ClassroomCapabilities {
    val free = capabilityKey<FreeClassroomRepository>("classroom.free")
}

/**
 * 空闲教室插件。
 *
 * 查询是公开只读页面，没有账号、缓存或后台任务；插件只负责提供同一份
 * Repository 实现，页面自行管理筛选与请求状态。
 */
class ClassroomPlugin : Plugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        id = PluginId.parse("classroom"),
        version = "1.0.0",
        capabilities = setOf(ClassroomCapabilities.free.id),
    )

    private val repository: FreeClassroomRepository = PublicFreeClassroomRepository()

    override fun capabilities(): List<PluginCapability> =
        listOf(PluginCapability(ClassroomCapabilities.free, repository))
}
