package com.upcpocket.app.ui.nav

/**
 * 宿主自有导航路由。
 *
 * 业务页面路由由已激活插件通过 `PluginUiSnapshot.pages` 贡献，不在这里重复登记；
 * 这里只保留宿主固定首页/服务 tab、设置入口与宿主设置页。M5 取消“更多”tab，账号、主题、
 * 组件与关于统一收进设置。
 */
object AppRoutes {

    /** 宿主固定首页与服务 tab；课表/待办/专注 tab 由插件贡献。 */
    const val HOME = "home"
    const val SERVICES = "services"

    /** 宿主设置页（不是 tab，进入后底部导航隐藏）。 */
    const val SETTINGS = "settings"
    const val ACCOUNT = "account"
    const val APPEARANCE = "appearance"
    const val WIDGETS = "widgets"
    const val HOME_ANNIVERSARY = "home-anniversary"
    const val OFFICIAL_WEB = "official-web"

    /** 宿主学习平台聚合页：二级页，不占底部 tab，也不接受外部深链。 */
    const val LEARNING_PLATFORMS = "learning-platforms"
    const val ABOUT = "about"
}
