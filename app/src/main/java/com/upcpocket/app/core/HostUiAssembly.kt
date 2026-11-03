package com.upcpocket.app.core

import com.upcpocket.app.plugin.runtime.ActivePlugins
import com.upcpocket.app.plugin.ui.PluginUiContributor
import com.upcpocket.app.plugin.ui.PluginUiRegistry
import com.upcpocket.app.plugin.ui.PluginUiSnapshot

enum class HostUiDiagnosticCode {
    PLUGIN_ID_MISMATCH,
    UI_CONTRIBUTION_FAILED,
}

/** UI 贡献失败的脱敏诊断：不包含异常原文或入口内容。 */
data class HostUiDiagnostic(
    val code: HostUiDiagnosticCode,
    val pluginId: String,
    val detail: String,
)

data class HostUiAssemblyResult(
    val snapshot: PluginUiSnapshot,
    val diagnostics: List<HostUiDiagnostic> = emptyList(),
)

/**
 * 把已激活插件贡献的页面、入口、底部 tab 与区块组装成宿主可读快照。
 *
 * 每个插件的贡献先写入一个候选 fork，只有 [PluginUiRegistry.build] 通过后才
 * 原子替换当前集合。某个插件的 UI 注册抛异常或与已有贡献冲突时，只丢弃该
 * 插件的整份 UI 贡献，其他插件与宿主页面继续可用，不会残留半套入口。
 */
object HostUiAssembly {

    fun build(activePlugins: ActivePlugins): HostUiAssemblyResult {
        val accepted = PluginUiRegistry()
        val diagnostics = mutableListOf<HostUiDiagnostic>()
        var snapshot = accepted.build()

        activePlugins.ordered.forEach { plugin ->
            val contributor = plugin as? PluginUiContributor ?: return@forEach
            if (contributor.pluginId != plugin.descriptor.id) {
                diagnostics += HostUiDiagnostic(
                    code = HostUiDiagnosticCode.PLUGIN_ID_MISMATCH,
                    pluginId = plugin.descriptor.id.value,
                    detail = "UI 归属 ID 与插件 ID 不一致",
                )
                return@forEach
            }
            val candidate = accepted.fork()
            try {
                contributor.contribute(candidate)
                val next = candidate.build()
                accepted.replaceWith(candidate)
                snapshot = next
            } catch (_: Throwable) {
                // 候选 fork 直接丢弃；不把异常原文写入诊断或日志。
                diagnostics += HostUiDiagnostic(
                    code = HostUiDiagnosticCode.UI_CONTRIBUTION_FAILED,
                    pluginId = plugin.descriptor.id.value,
                    detail = "插件 UI 贡献未通过校验",
                )
            }
        }

        return HostUiAssemblyResult(snapshot = snapshot, diagnostics = diagnostics)
    }
}
