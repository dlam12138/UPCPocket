package com.upcpocket.app.plugin.ui

import androidx.compose.runtime.Composable
import com.upcpocket.app.plugin.api.PluginId

/** 稳定入口 ID，例如 `power.tangdaowan`。展示顺序变化不改变它。 */
@JvmInline
value class UiEntryId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+")

        fun parse(raw: String): UiEntryId {
            require(raw.matches(PATTERN)) { "非法入口 ID：需要小写点分名，例如 power.tangdaowan" }
            return UiEntryId(raw)
        }
    }
}

/** 页面路由 ID 由插件声明、宿主注册，不暴露给外部 Intent。 */
@JvmInline
value class PluginRouteId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+")

        fun parse(raw: String): PluginRouteId {
            require(raw.matches(PATTERN)) { "非法路由 ID：需要小写点分名" }
            return PluginRouteId(raw)
        }
    }
}

/** 宿主决定入口最终出现在哪个栏目；插件只声明稳定归属。 */
enum class UiSurface {
    HOME_SERVICES,
    HOME_CARD,
    MORE_LEARNING,
    MORE_SCHOOL,
}

/** 图标是展示元数据，不把 Compose ImageVector 放进注册契约。 */
enum class UiIcon {
    ELECTRICITY,
    CLASSROOM,
    LIBRARY,
    INSIGHTS,
}

/** 页面只能通过宿主导航，不能拿 NavController 或对外 Intent。 */
interface PluginPageHost {
    fun open(route: PluginRouteId)

    fun back()
}

/**
 * 一个功能入口。
 *
 * `order` 只用于同一栏目内的稳定排序；M0 迁移时沿用现有顺序，M5 再统一调整。
 */
data class PluginEntry(
    val entryId: UiEntryId,
    val pluginId: PluginId,
    val surface: UiSurface,
    val order: Int,
    val title: String,
    val subtitle: String,
    val icon: UiIcon,
    val route: PluginRouteId?,
) {
    init {
        require(title.isNotBlank()) { "入口标题不能为空" }
        require(subtitle.isNotBlank()) { "入口副标题不能为空，避免出现空白卡片" }
    }
}

/** 插件自己拥有的页面；内容只通过 [PluginPageHost] 与宿主交互。 */
class PluginPage(
    val routeId: PluginRouteId,
    val pluginId: PluginId,
    val content: @Composable (PluginPageHost) -> Unit,
)

/** 嵌在宿主页面中的区域（首页余额卡、更多里的学校登录区）。 */
class PluginSection(
    val entryId: UiEntryId,
    val pluginId: PluginId,
    val surface: UiSurface,
    val order: Int,
    val content: @Composable () -> Unit,
)

class PluginUiException(message: String) : IllegalArgumentException(message)

/**
 * UI 贡献注册器。
 *
 * 只暂存，不渲染；[build] 统一校验重复入口/路由与缺失页面，全部通过才返回
 * 不可变快照。任何冲突都不会覆盖已有入口。
 */
class PluginUiRegistry {
    private val entries = LinkedHashMap<String, PluginEntry>()
    private val pages = LinkedHashMap<String, PluginPage>()
    private val sections = LinkedHashMap<String, PluginSection>()

    fun addEntry(entry: PluginEntry): PluginUiRegistry {
        val key = entry.entryId.value
        if (entries.containsKey(key) || sections.containsKey(key)) {
            throw PluginUiException("重复的入口 ID：$key")
        }
        entries[key] = entry
        return this
    }

    fun addPage(page: PluginPage): PluginUiRegistry {
        val key = page.routeId.value
        if (pages.containsKey(key)) throw PluginUiException("重复的页面路由：$key")
        pages[key] = page
        return this
    }

    fun addSection(section: PluginSection): PluginUiRegistry {
        val key = section.entryId.value
        if (sections.containsKey(key) || entries.containsKey(key)) {
            throw PluginUiException("重复的入口 ID：$key")
        }
        sections[key] = section
        return this
    }

    fun build(): PluginUiSnapshot {
        val diagnostics = mutableListOf<String>()
        entries.values.forEach { entry ->
            val route = entry.route
            if (route != null && !pages.containsKey(route.value)) {
                diagnostics += "入口 ${entry.entryId} 指向未注册页面 ${route.value}"
            }
        }
        val duplicateRoutes = pages.values.groupBy { it.routeId.value }.filterValues { it.size > 1 }
        duplicateRoutes.keys.forEach { diagnostics += "重复的页面路由：$it" }
        if (diagnostics.isNotEmpty()) {
            throw PluginUiException(diagnostics.sorted().joinToString("；"))
        }

        fun <T> sortByOrder(values: Collection<T>, order: (T) -> Int, id: (T) -> String): List<T> =
            values.sortedWith(compareBy(order, id))

        return PluginUiSnapshot(
            entries = sortByOrder(entries.values, { it.order }, { it.entryId.value }),
            pages = pages.values.toList(),
            sections = sortByOrder(sections.values, { it.order }, { it.entryId.value }),
        )
    }
}

/** 校验通过的不可变 UI 贡献。宿主只读取它，不再接触插件实现。 */
class PluginUiSnapshot internal constructor(
    val entries: List<PluginEntry>,
    val pages: List<PluginPage>,
    val sections: List<PluginSection>,
) {
    fun entriesFor(surface: UiSurface): List<PluginEntry> = entries.filter { it.surface == surface }

    fun sectionsFor(surface: UiSurface): List<PluginSection> = sections.filter { it.surface == surface }

    fun page(routeId: PluginRouteId): PluginPage? = pages.firstOrNull { it.routeId == routeId }
}
