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

/** 注册阶段立即失败，避免把错误拖到启动后才暴露。 */
class PluginRegistrationException(message: String) : IllegalArgumentException(message)

/** 启动计划：按依赖顺序排列的插件与全部诊断。 */
data class PluginPlan(
    val ordered: List<PluginId>,
    val diagnostics: List<PluginDiagnostic>,
) {
    val isValid: Boolean get() = diagnostics.isEmpty()
}

sealed interface PluginStartResult {
    data class Active(val plugins: ActivePlugins) : PluginStartResult

    data class Invalid(val diagnostics: List<PluginDiagnostic>) : PluginStartResult
}

/**
 * 已激活的插件集合。
 *
 * 只有全部插件创建并校验通过后才会产生该对象；失败时调用方拿不到任何
 * 半注册的能力，因此旧入口只能看到明确的“不可用”，不会误用部分状态。
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
 *  - 启动时先校验宿主 API、必需依赖、重复能力与依赖环，再按依赖顺序创建；
 *  - 任一插件创建失败则整体失败，不发布半注册结果；
 *  - 同一实例不可重复启动；失败后保持失败态，便于开发构建明确暴露配置问题。
 */
class PluginRuntime(
    private val hostApiVersion: Int = HOST_PLUGIN_API_VERSION,
) {
    private data class Registration(
        val descriptor: PluginDescriptor,
        val factory: PluginFactory,
    )

    private val registrations = sortedMapOf<String, Registration>()
    private var active: ActivePlugins? = null
    private var failure: List<PluginDiagnostic>? = null

    @Synchronized
    fun register(descriptor: PluginDescriptor, factory: PluginFactory): PluginRuntime {
        check(active == null && failure == null) { "插件运行时已启动或已失败，不能再注册" }
        val key = descriptor.id.value
        if (registrations.containsKey(key)) {
            throw PluginRegistrationException("重复的插件 ID：$key")
        }
        registrations[key] = Registration(descriptor, factory)
        return this
    }

    @Synchronized
    fun plan(): PluginPlan {
        val diagnostics = mutableListOf<PluginDiagnostic>()
        registrations.values.forEach { registration ->
            val descriptor = registration.descriptor
            if (hostApiVersion !in descriptor.hostApi) {
                diagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.HOST_API_MISMATCH,
                    pluginId = descriptor.id.value,
                    detail = "插件支持 ${descriptor.hostApi}，宿主为 $hostApiVersion",
                )
            }
            descriptor.dependencies.forEach { dependency ->
                val present = registrations.containsKey(dependency.id.value)
                if (!present && dependency.required) {
                    diagnostics += PluginDiagnostic(
                        code = PluginDiagnosticCode.MISSING_DEPENDENCY,
                        pluginId = descriptor.id.value,
                        detail = "缺少必需插件 ${dependency.id.value}",
                    )
                }
            }
        }

        val ordered = topologicalOrder(diagnostics)
        diagnostics += duplicateCapabilityDiagnostics()
        return PluginPlan(
            ordered = ordered.map { PluginId.parse(it) },
            diagnostics = diagnostics.distinct().sortedWith(
                compareBy({ it.pluginId.orEmpty() }, { it.code.name }, { it.detail }),
            ),
        )
    }

    @Synchronized
    fun start(): PluginStartResult {
        active?.let { return PluginStartResult.Active(it) }
        failure?.let { return PluginStartResult.Invalid(it) }

        val plan = plan()
        if (!plan.isValid) {
            failure = plan.diagnostics
            return PluginStartResult.Invalid(plan.diagnostics)
        }

        val created = mutableListOf<Plugin>()
        val capabilities = mutableMapOf<String, Any>()
        val byPlugin = mutableMapOf<String, Set<String>>()
        val creationDiagnostics = mutableListOf<PluginDiagnostic>()

        for (id in plan.ordered) {
            val registration = registrations.getValue(id.value)
            val plugin = try {
                registration.factory.create()
            } catch (_: Throwable) {
                // 不把异常原文写进诊断：构造异常可能含路径或业务数据。
                creationDiagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.CREATION_FAILED,
                    pluginId = id.value,
                    detail = "插件创建失败",
                )
                break
            }
            if (plugin.descriptor.id != registration.descriptor.id) {
                creationDiagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.INVALID_PLUGIN,
                    pluginId = id.value,
                    detail = "实例声明的插件 ID 与注册描述符不一致",
                )
                break
            }
            val declared = registration.descriptor.capabilities
            val provided = plugin.capabilities().map { it.id }.toSet()
            if (declared != provided) {
                creationDiagnostics += PluginDiagnostic(
                    code = PluginDiagnosticCode.CAPABILITY_MISMATCH,
                    pluginId = id.value,
                    detail = "声明能力 ${declared.sorted()}，实际提供 ${provided.sorted()}",
                )
                break
            }
            plugin.capabilities().forEach { capability ->
                if (capabilities.putIfAbsent(capability.id, capability.value) != null) {
                    creationDiagnostics += PluginDiagnostic(
                        code = PluginDiagnosticCode.DUPLICATE_CAPABILITY,
                        pluginId = id.value,
                        detail = "能力 ${capability.id} 已被其他插件提供",
                    )
                }
            }
            if (creationDiagnostics.isNotEmpty()) break
            byPlugin[id.value] = provided
            created += plugin
        }

        if (creationDiagnostics.isNotEmpty()) {
            // created 中已构造的实例不再对外暴露；contract 要求构造无副作用。
            failure = creationDiagnostics.distinct().sortedWith(
                compareBy({ it.pluginId.orEmpty() }, { it.code.name }, { it.detail }),
            )
            return PluginStartResult.Invalid(failure!!)
        }

        val snapshot = ActivePlugins(
            ordered = created,
            capabilitiesById = capabilities.toMap(),
            capabilitiesByPlugin = byPlugin.toMap(),
        )
        active = snapshot
        return PluginStartResult.Active(snapshot)
    }

    private fun duplicateCapabilityDiagnostics(): List<PluginDiagnostic> {
        val owners = mutableMapOf<String, String>()
        val diagnostics = mutableListOf<PluginDiagnostic>()
        registrations.values.sortedBy { it.descriptor.id.value }.forEach { registration ->
            registration.descriptor.capabilities.sorted().forEach { capability ->
                val owner = owners.putIfAbsent(capability, registration.descriptor.id.value)
                if (owner != null) {
                    diagnostics += PluginDiagnostic(
                        code = PluginDiagnosticCode.DUPLICATE_CAPABILITY,
                        pluginId = registration.descriptor.id.value,
                        detail = "能力 $capability 已由 $owner 声明",
                    )
                }
            }
        }
        return diagnostics
    }

    /**
     * Kahn 拓扑排序；队列按插件 ID 排序，保证同一装配每次顺序一致。
     * 剩余节点即存在环或依赖环内节点，统一报告一次依赖环诊断。
     */
    private fun topologicalOrder(diagnostics: MutableList<PluginDiagnostic>): List<String> {
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

        if (ordered.size != ids.size) {
            val blocked = ids.filterNot(ordered::contains)
            diagnostics += PluginDiagnostic(
                code = PluginDiagnosticCode.DEPENDENCY_CYCLE,
                pluginId = blocked.firstOrNull(),
                detail = "依赖环涉及：${blocked.joinToString()}",
            )
        }
        return ordered
    }
}
