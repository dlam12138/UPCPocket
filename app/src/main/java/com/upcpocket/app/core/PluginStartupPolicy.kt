package com.upcpocket.app.core

import com.upcpocket.app.plugin.runtime.PluginDiagnostic

/**
 * 插件启动失败策略。
 *
 * - 开发/测试构建：出现任何插件/UI 失败直接 fail-fast，避免把装配错误静默带进功能。
 * - 发行构建：只停用失败插件及其传递依赖，其余健康插件继续可用；日志仅输出
 *   固定错误码与插件 ID，不输出异常原文或业务数据。
 */
object PluginStartupPolicy {

    fun shouldFailFast(debug: Boolean, hasFailures: Boolean): Boolean = debug && hasFailures

    fun summary(
        pluginDiagnostics: List<PluginDiagnostic>,
        uiDiagnostics: List<HostUiDiagnostic>,
    ): String = buildList {
        pluginDiagnostics.forEach { add("plugin:${it.code}:${it.pluginId.orEmpty()}") }
        uiDiagnostics.forEach { add("ui:${it.code}:${it.pluginId}") }
    }.sorted().joinToString(",")

    fun failFastMessage(summary: String): String =
        if (summary.isBlank()) "插件装配失败" else "插件装配失败：$summary"
}
