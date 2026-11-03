package com.upcpocket.app.ui.nav

/**
 * 宿主自有导航路由。
 *
 * 业务页面路由由已激活插件通过 `PluginUiSnapshot.pages` 贡献，不在这里重复登记；
 * 这里只保留宿主固定的首页/更多 tab 与「更多」中的宿主设置页。
 */
object AppRoutes {

    /** 宿主固定 tab；插件 tab 由 `PluginUiSnapshot.bottomNav` 提供。 */
    const val HOME = "home"
    const val MORE = "more"

    /** 宿主设置/信息页（不是 tab，进去之后底部导航栏会隐藏） */
    const val APPEARANCE = "appearance"
    const val ABOUT = "about"
}
