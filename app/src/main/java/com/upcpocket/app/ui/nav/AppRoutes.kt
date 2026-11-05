package com.upcpocket.app.ui.nav

/**
 * 宿主自有导航路由。
 *
 * 业务页面路由由已激活插件通过 `PluginUiSnapshot.pages` 贡献，不在这里重复登记；
 * 这里只保留宿主固定首页、设置入口与宿主设置页。M5 取消“更多”tab，账号、主题、
 * 组件与关于统一收进设置。
 */
object AppRoutes {

    /** 宿主固定 tab；课表/专注 tab 由插件贡献。 */
    const val HOME = "home"

    /** 宿主设置页（不是 tab，进入后底部导航隐藏）。 */
    const val SETTINGS = "settings"
    const val ACCOUNT = "account"
    const val APPEARANCE = "appearance"
    const val WIDGETS = "widgets"
    const val OFFICIAL_WEB = "official-web"
    const val ABOUT = "about"
}
