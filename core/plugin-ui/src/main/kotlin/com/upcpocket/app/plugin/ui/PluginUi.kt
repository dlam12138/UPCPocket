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
        // 保持迁移前的单段路由（如 "focus"）仍可恢复；新路由仍建议用点分名。
        private val PATTERN = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)*")

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
    TIMER,
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

/**
 * 插件向宿主提交 UI 贡献的显式入口。
 *
 * 只有已经激活的插件才会被宿主询问；贡献本身仍由 [PluginUiRegistry] 统一校验，
 * 因此页面、入口和区块的重复 ID/路由不会覆盖。
 */
interface PluginUiContributor {
    val pluginId: PluginId

    fun contribute(registry: PluginUiRegistry)
}

/**
 * 插件贡献的底部导航 tab。
 *
 * 宿主只接受“已注册页面 + 稳定图标枚举”的 tab；tab 的显示顺序由 [order]
 * 决定，但宿主保底保留首页/更多，因此插件只能插入中间位置，不能替换宿主壳。
 */
class PluginBottomNav(
    val entryId: UiEntryId,
    val pluginId: PluginId,
    val order: Int,
    val label: String,
    val icon: UiIcon,
    val route: PluginRouteId,
) {
    init {
        require(label.isNotBlank()) { "底部导航标签不能为空" }
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
    private val bottomNav = LinkedHashMap<String, PluginBottomNav>()

    fun addEntry(entry: PluginEntry): PluginUiRegistry {
        val key = entry.entryId.value
        requireOwned(entry.pluginId, key)
        if (entries.containsKey(key) || sections.containsKey(key) || bottomNav.containsKey(key)) {
            throw PluginUiException("重复的入口 ID：$key")
        }
        entries[key] = entry
        return this
    }

    fun addPage(page: PluginPage): PluginUiRegistry {
        val key = page.routeId.value
        requireOwned(page.pluginId, key)
        if (pages.containsKey(key)) throw PluginUiException("重复的页面路由：$key")
        pages[key] = page
        return this
    }

    fun addSection(section: PluginSection): PluginUiRegistry {
        val key = section.entryId.value
        requireOwned(section.pluginId, key)
        if (sections.containsKey(key) || entries.containsKey(key) || bottomNav.containsKey(key)) {
            throw PluginUiException("重复的入口 ID：$key")
        }
        sections[key] = section
        return this
    }

    fun addBottomNav(item: PluginBottomNav): PluginUiRegistry {
        val key = item.entryId.value
        requireOwned(item.pluginId, key)
        if (bottomNav.containsKey(key) || entries.containsKey(key) || sections.containsKey(key)) {
            throw PluginUiException("重复的入口 ID：$key")
        }
        bottomNav[key] = item
        return this
    }

    private fun requireOwned(pluginId: PluginId, id: String) {
        if (id != pluginId.value && !id.startsWith("${pluginId.value}.")) {
            throw PluginUiException("ID $id 不属于插件 ${pluginId.value}")
        }
    }

    /**
     * 复制当前注册表，供宿主按插件暂存 UI 贡献。插件贡献先写入 fork，
     * 校验成功后再 [replaceWith] 原子替换，避免失败插件留下半套入口。
     */
    fun fork(): PluginUiRegistry = PluginUiRegistry().also { copy ->
        copy.entries.putAll(entries)
        copy.pages.putAll(pages)
        copy.sections.putAll(sections)
        copy.bottomNav.putAll(bottomNav)
    }

    /** 用已验证的 fork 原子替换当前内容。 */
    fun replaceWith(other: PluginUiRegistry) {
        entries.clear()
        pages.clear()
        sections.clear()
        bottomNav.clear()
        entries.putAll(other.entries)
        pages.putAll(other.pages)
        sections.putAll(other.sections)
        bottomNav.putAll(other.bottomNav)
    }

    fun build(): PluginUiSnapshot {
        val diagnostics = mutableListOf<String>()
        entries.values.forEach { entry ->
            val route = entry.route
            if (route != null) {
                val page = pages[route.value]
                if (page == null) {
                    diagnostics += "入口 ${entry.entryId} 指向未注册页面 ${route.value}"
                } else if (page.pluginId != entry.pluginId) {
                    diagnostics +=
                        "入口 ${entry.entryId} 属于 ${entry.pluginId}，但页面 ${route.value} 属于 ${page.pluginId}"
                }
            }
        }
        bottomNav.values.forEach { item ->
            val page = pages[item.route.value]
            if (page == null) {
                diagnostics += "底部导航 ${item.entryId} 指向未注册页面 ${item.route.value}"
            } else if (page.pluginId != item.pluginId) {
                diagnostics +=
                    "底部导航 ${item.entryId} 属于 ${item.pluginId}，但页面 ${item.route.value} 属于 ${page.pluginId}"
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
            bottomNav = sortByOrder(bottomNav.values, { it.order }, { it.entryId.value }),
        )
    }
}

/** 校验通过的不可变 UI 贡献。宿主只读取它，不再接触插件实现。 */
class PluginUiSnapshot internal constructor(
    val entries: List<PluginEntry>,
    val pages: List<PluginPage>,
    val sections: List<PluginSection>,
    val bottomNav: List<PluginBottomNav>,
) {
    fun entriesFor(surface: UiSurface): List<PluginEntry> = entries.filter { it.surface == surface }

    fun sectionsFor(surface: UiSurface): List<PluginSection> = sections.filter { it.surface == surface }

    fun page(routeId: PluginRouteId): PluginPage? = pages.firstOrNull { it.routeId == routeId }
}
