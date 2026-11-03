package com.upcpocket.app.plugin.runtime

import com.upcpocket.app.plugin.api.CapabilityKey
import com.upcpocket.app.plugin.api.HOST_PLUGIN_API_VERSION
import com.upcpocket.app.plugin.api.Plugin
import com.upcpocket.app.plugin.api.PluginDescriptor
import com.upcpocket.app.plugin.api.PluginFactory
import com.upcpocket.app.plugin.api.PluginId

/** 诊断码是稳定契约：日志、测试与开发构建都按它判断，不解析自然语言。 */
enum class PluginDiagnosticCode {
    DUPLICATE_PLUGIN,
    HOST_API_MISMATCH,
    MISSING_DEPENDENCY,

    /** 必需依赖存在但已停用；与“从未注册”区分，便于宿主定位装配原因。 */
    DEPENDENCY_DISABLED,
    DEPENDENCY_CYCLE,
    DUPLICATE_CAPABILITY,
    CAPABILITY_MISMATCH,
    INVALID_PLUGIN,
    CREATION_FAILED,
}

/**
 * 脱敏诊断：只包含插件 ID、版本与能力 ID，不允许消息里出现账号、学号、
 * 房间号、URL 查询串或异常原文。
 */
data class PluginDiagnostic(
    val code: PluginDiagnosticCode,
    val pluginId: String?,
    val detail: String,
)

/** 注册阶段立即失败，避免把宿主装配代码中的重复 ID 拖到启动后才暴露。 */
class PluginRegistrationException(message: String) : IllegalArgumentException(message)

/**
 * 静态启动计划。它只描述注册表能看出的问题；工厂创建、能力冲突等要等实例化
 * 后才会知道，因此真正是否可用以 [PluginStartResult.Started] 为准。
 */
data class PluginPlan(
    val ordered: List<PluginId>,
    val diagnostics: List<PluginDiagnostic>,
) {
    val isValid: Boolean get() = diagnostics.isEmpty()
}

sealed interface PluginStartResult {
    /**
     * 已启动结果。`plugins` 只含健康插件；`diagnostics` 是被隔离的失败及其
     * 传递依赖。即使全部插件都失败，也返回空集合而不是让宿主无法启动。
     */
    data class Started(
        val plugins: ActivePlugins,
        val diagnostics: List<PluginDiagnostic> = emptyList(),
    ) : PluginStartResult {
        val hasFailures: Boolean get() = diagnostics.isNotEmpty()
    }
}

/**
 * 已激活的插件集合。
 *
 * 每个插件只有在校验与创建全部成功后才提交自己的能力和实例；失败插件不会
 * 留下半注册能力。调用方拿到的集合中不会出现失败插件或其传递依赖。
 */
class ActivePlugins internal constructor(
    val ordered: List<Plugin>,
    private val capabilitiesById: Map<String, Any>,
    private val capabilitiesByPlugin: Map<String, Set<String>>,
) {
    fun plugin(id: PluginId): Plugin? = ordered.firstOrNull { it.descriptor.id == id }

    fun <T : Any> capability(key: CapabilityKey<T>): T? {
        val value = capabilitiesById[key.id] ?: return null
        return key.type.cast(value)
    }

    fun requireCapability(key: CapabilityKey<*>): Any =
        capabilitiesById[key.id]
            ?: throw IllegalStateException("能力 ${key.id} 未提供")

    fun capabilitiesOf(pluginId: PluginId): Set<String> =
        capabilitiesByPlugin[pluginId.value].orEmpty()

    fun capabilityIds(): Set<String> = capabilitiesById.keys
}

/**
 * 内置插件运行时。
 *
 * 约束：
 *  - 注册只做静态校验，不创建实例、不联网、不读写业务数据；
 *  - 启动时先做静态分析，再按确定顺序逐个原子激活；
 *  - 一个插件失败只停用它自己，以及所有（传递）必需依赖它的插件；独立插件继续；
 *  - 每个插件的能力与实例只在自身成功后才提交，不留下半注册状态；
 *  - 诊断只含插件/能力 ID 与固定说明，不把工厂异常原文写进日志；
 *  - 开发构建的 fail-fast 由宿主策略决定；运行时本身始终返回可解释的结果。
 */
class PluginRuntime(
    private val hostApiVersion: Int = HOST_PLUGIN_API_VERSION,
) {
    private data class Registration(
        val descriptor: PluginDescriptor,
        val factory: PluginFactory,
    )

    private data class StaticAnalysis(
        val order: List<String>,
        val failed: Set<String>,
        val diagnostics: List<PluginDiagnostic>,
    )

    private val registrations = sortedMapOf<String, Registration>()
    private var result: PluginStartResult.Started? = null

    @Synchronized
    fun register(descriptor: PluginDescriptor, factory: PluginFactory): PluginRuntime {
        check(result == null) { "插件运行时已启动，不能再注册" }
        val key = descriptor.id.value
        if (registrations.containsKey(key)) {
            throw PluginRegistrationException("重复的插件 ID：$key")
        }
        registrations[key] = Registration(descriptor, factory)
        return this
    }

    @Synchronized
    fun plan(): PluginPlan {
        val analysis = analyze()
        return PluginPlan(
            ordered = analysis.order.map(PluginId::parse),
            diagnostics = analysis.diagnostics,
        )
    }

    @Synchronized
    fun start(): PluginStartResult.Started {
        result?.let { return it }
        return startInternal().also { result = it }
    }

    private fun startInternal(): PluginStartResult.Started {
        val analysis = analyze()
        val diagnostics = analysis.diagnostics.toMutableList()
        val failed = analysis.failed.toMutableSet()

        val activeIds = linkedSetOf<String>()
        val created = mutableListOf<Plugin>()
        val capabilitiesById = mutableMapOf<String, Any>()
        val capabilitiesByPlugin = mutableMapOf<String, Set<String>>()

        for (rawId in analysis.order) {
            if (rawId in failed) continue
            val registration = registrations.getValue(rawId)
            val descriptor = registration.descriptor

            // 静态检查已覆盖“缺失必需依赖”；这里再次核对运行时已被隔离的依赖。
            val unavailableDependency = descriptor.dependencies
                .filter { it.required }
                .map { it.id.value }
                .firstOrNull { it !in activeIds }
            if (unavailableDependency != null) {
                val code = if (unavailableDependency in failed) {
                    PluginDiagnosticCode.DEPENDENCY_DISABLED
                } else {
                    PluginDiagnosticCode.MISSING_DEPENDENCY
                }
                diagnostics += PluginDiagnostic(
                    code = code,
                    pluginId = rawId,
                    detail = "必需依赖 $unavailableDependency 未启用",
                )
                failed += rawId
                continue
            }

            val plugin = try {
                registration.factory.create()
            } catch (_: Throwable) {
                // 不把异常原文写进诊断：构造异常可能含路径或业务数据。
                diagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.CREATION_FAILED,
                    pluginId = rawId,
                    detail = "插件创建失败",
                )
                failed += rawId
                continue
            }

            if (plugin.descriptor.id != descriptor.id) {
                diagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.INVALID_PLUGIN,
                    pluginId = rawId,
                    detail = "实例 ID ${plugin.descriptor.id} 与注册 ID ${descriptor.id} 不一致",
                )
                failed += rawId
                continue
            }

            val provided = try {
                plugin.capabilities()
            } catch (_: Throwable) {
                diagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.CREATION_FAILED,
                    pluginId = rawId,
                    detail = "插件能力枚举失败",
                )
                failed += rawId
                continue
            }
            val providedIds = provided.map { it.id }
            if (providedIds.size != providedIds.toSet().size) {
                diagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.DUPLICATE_CAPABILITY,
                    pluginId = rawId,
                    detail = "插件重复提供同一能力",
                )
                failed += rawId
                continue
            }
            if (providedIds.toSet() != descriptor.capabilities) {
                diagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.CAPABILITY_MISMATCH,
                    pluginId = rawId,
                    detail = "声明 ${descriptor.capabilities.sorted()}，实际 ${providedIds.sorted()}",
                )
                failed += rawId
                continue
            }
            val conflict = provided.firstOrNull { it.id in capabilitiesById }
            if (conflict != null) {
                diagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.DUPLICATE_CAPABILITY,
                    pluginId = rawId,
                    detail = "能力 ${conflict.id} 已由其他插件提供",
                )
                failed += rawId
                continue
            }

            // 原子提交：只有到这里，插件实例、能力与能力归属才加入活动集合。
            provided.forEach { capabilitiesById[it.id] = it.value }
            capabilitiesByPlugin[rawId] = providedIds.toSet()
            activeIds += rawId
            created += plugin
        }

        return PluginStartResult.Started(
            plugins = ActivePlugins(
                ordered = created.toList(),
                capabilitiesById = capabilitiesById.toMap(),
                capabilitiesByPlugin = capabilitiesByPlugin.toMap(),
            ),
            diagnostics = diagnostics.distinct().sortedWith(diagnosticOrder),
        )
    }

    private fun analyze(): StaticAnalysis {
        val ids = registrations.keys.toSortedSet()
        val diagnostics = mutableListOf<PluginDiagnostic>()
        val failed = mutableSetOf<String>()

        registrations.forEach { (rawId, registration) ->
            val descriptor = registration.descriptor
            if (hostApiVersion !in descriptor.hostApi) {
                diagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.HOST_API_MISMATCH,
                    pluginId = rawId,
                    detail = "插件支持 ${descriptor.hostApi}，宿主为 $hostApiVersion",
                )
                failed += rawId
            }
            descriptor.dependencies.filter { it.required }.forEach { dependency ->
                if (!registrations.containsKey(dependency.id.value)) {
                    diagnostics += PluginDiagnostic(
                        code = PluginDiagnosticCode.MISSING_DEPENDENCY,
                        pluginId = rawId,
                        detail = "缺少必需插件 ${dependency.id.value}",
                    )
                    failed += rawId
                }
            }
        }

        val ordered = topologicalOrder()
        val blocked = ids.filterNot(ordered::contains)
        blocked.forEach { rawId ->
            diagnostics += PluginDiagnostic(
                code = PluginDiagnosticCode.DEPENDENCY_CYCLE,
                pluginId = rawId,
                detail = "依赖环或环的下游：${blocked.joinToString()}",
            )
            failed += rawId
        }

        return StaticAnalysis(
            order = ordered + blocked,
            failed = failed,
            diagnostics = diagnostics.distinct().sortedWith(diagnosticOrder),
        )
    }

    /**
     * Kahn 拓扑排序；队列按插件 ID 排序，保证同一装配每次顺序一致。
     * 存在环时返回可排序部分，剩余节点由调用方统一标记为不可启动。
     */
    private fun topologicalOrder(): List<String> {
        val ids = registrations.keys.toSortedSet()
        val dependencies = ids.associateWith { id ->
            registrations.getValue(id).descriptor.dependencies
                .map { it.id.value }
                .filter { registrations.containsKey(it) }
                .toSet()
        }
        val indegree = ids.associateWith { id -> dependencies.getValue(id).size }.toMutableMap()
        val dependents = mutableMapOf<String, MutableList<String>>()
        dependencies.forEach { (id, deps) ->
            deps.forEach { dependency ->
                dependents.getOrPut(dependency) { mutableListOf() } += id
            }
        }
        dependents.values.forEach { it.sort() }

        val ready = java.util.PriorityQueue<String>()
        indegree.filterValues { it == 0 }.keys.forEach(ready::add)
        val ordered = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val id = ready.poll()
            ordered += id
            dependents[id].orEmpty().forEach { dependent ->
                val remaining = indegree.getValue(dependent) - 1
                indegree[dependent] = remaining
                if (remaining == 0) ready += dependent
            }
        }
        return ordered
    }

    private companion object {
        val diagnosticOrder = compareBy<PluginDiagnostic>(
            { it.pluginId.orEmpty() },
            { it.code.name },
            { it.detail },
        )
    }
}
