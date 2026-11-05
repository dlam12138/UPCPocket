package com.upcpocket.app.core

import com.upcpocket.app.plugin.api.PluginId
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
 * 插件运行时结果 + UI 解析结果。
 *
 * UI 贡献失败的插件会从活动集合中移除；所有必需依赖它的插件也一并移除，
 * 因而它们的业务能力、生命周期回调与系统组件能力都不会再被宿主使用。
 */
data class HostUiResolution(
    val activePlugins: ActivePlugins,
    val assembly: HostUiAssemblyResult,
)

/**
 * 把已激活插件贡献的页面、入口、底部 tab 与区块组装成宿主可读快照。
 *
 * 每个插件的贡献先写入一个候选 fork，只有 [PluginUiRegistry.build] 通过后才
 * 原子替换当前集合。某个插件的 UI 注册抛异常或与已有贡献冲突时，只丢弃该
 * 插件的整份 UI 贡献，其他插件与宿主页面继续可用，不会残留半套入口。
 */
object HostUiAssembly {

    /**
     * 解析 UI，并在失败时按插件及其传递必需依赖裁剪活动集合。裁剪后重新构建，
     * 直到没有新的 UI 故障；返回值中的 activePlugins 才是宿主最终可用的插件。
     */
    fun resolve(activePlugins: ActivePlugins): HostUiResolution {
        var current = activePlugins
        val diagnostics = mutableListOf<HostUiDiagnostic>()
        while (true) {
            val result = build(current)
            diagnostics += result.diagnostics
            if (result.diagnostics.isEmpty()) {
                return HostUiResolution(
                    current,
                    result.copy(diagnostics = diagnostics.distinct()),
                )
            }
            val failed = result.diagnostics
                .mapTo(mutableSetOf()) { PluginId.parse(it.pluginId) }
            val next = current.withoutPlugins(failed)
            if (next.ordered.size == current.ordered.size) {
                // 防御性结束：诊断里的 ID 不在活动集合时无法继续裁剪，保留已接受 UI。
                return HostUiResolution(
                    current,
                    result.copy(diagnostics = diagnostics.distinct()),
                )
            }
            current = next
        }
    }

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
