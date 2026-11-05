package com.upcpocket.app.plugin.api

/**
 * 当前宿主插件 API 版本。
 *
 * 只有插件契约发生不兼容变更时才递增；新增可选能力不递增。插件在
 * [PluginDescriptor.hostApi] 中声明自己支持的区间，运行时按区间校验。
 */
const val HOST_PLUGIN_API_VERSION: Int = 1

/** 插件对另一个插件的依赖。 */
data class PluginDependency(
    val id: PluginId,
    /** `true` 时缺失即拒绝启动；`false` 时存在才参与排序，缺失不算错误。 */
    val required: Boolean = true,
)

/**
 * 插件的静态声明。
 *
 * 注册阶段不联网、不启动采样、不读写业务数据；描述符只允许包含可公开的
 * 版本与标识信息。`capabilities` 必须与 [Plugin.capabilities] 完全一致。
 */
data class PluginDescriptor(
    val id: PluginId,
    /** 插件自身版本，仅用于诊断与依赖说明，不参与比较。 */
    val version: String,
    /** 兼容的宿主 API 区间，闭区间。默认只支持当前版本。 */
    val hostApi: IntRange = HOST_PLUGIN_API_VERSION..HOST_PLUGIN_API_VERSION,
    val dependencies: List<PluginDependency> = emptyList(),
    val capabilities: Set<String> = emptySet(),
) {
    init {
        require(version.isNotBlank()) { "插件版本不能为空" }
        require(!hostApi.isEmpty()) { "宿主 API 区间不能为空" }
        require(hostApi.first >= 1) { "宿主 API 版本必须从 1 开始" }
        capabilities.forEach { CapabilityId.parse(it) }
        require(dependencies.map { it.id }.distinct().size == dependencies.size) {
            "插件 ${id.value} 重复声明了依赖"
        }
        require(dependencies.none { it.id == id }) {
            "插件 ${id.value} 不能依赖自身"
        }
    }
}

/** 一个插件提供的类型化能力；构造时立即校验值与键类型一致。 */
class PluginCapability(
    val key: CapabilityKey<*>,
    val value: Any,
) {
    init {
        require(key.type.isInstance(value)) {
            "能力 ${key.id} 的值类型不匹配：期望 ${key.type.name}"
        }
    }

    val id: String get() = key.id
}

/**
 * 随 APK 编译的内置插件。
 *
 * 插件只暴露描述符与能力；页面、卡片与设置项由 Android 侧 UI 契约另行贡献。
 * 实现必须是纯内存装配：构造时不做网络、数据库或文件写入。
 */
interface Plugin {
    val descriptor: PluginDescriptor

    fun capabilities(): List<PluginCapability> = emptyList()
}

/** 显式创建插件实例；由宿主在启动装配时调用，不通过反射扫描。 */
fun interface PluginFactory {
    fun create(): Plugin
}
