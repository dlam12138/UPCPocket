package com.upcpocket.app.core

import com.upcpocket.app.plugin.runtime.ActivePlugins
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.PluginUiSnapshot

/**
 * 把已激活插件贡献的页面、入口与区块组装成宿主可读快照。
 *
 * 宿主装配与 UI 注册表校验分开：插件运行时负责依赖/能力，UI 注册表负责页面和入口。
 * 移除某个插件后，这里只会询问仍激活的实例，因此其入口、页面和设置区一并消失。
 */
object HostUiAssembly {

    fun build(activePlugins: ActivePlugins): PluginUiSnapshot {
        val registry = PluginUiRegistry()
        activePlugins.ordered.forEach { plugin ->
            if (plugin is PluginUiContributor) {
                check(plugin.pluginId == plugin.descriptor.id) {
                    "插件 UI 归属不一致：${plugin.pluginId} != ${plugin.descriptor.id}"
                }
                plugin.contribute(registry)
            }
        }
        return registry.build()
    }
}
