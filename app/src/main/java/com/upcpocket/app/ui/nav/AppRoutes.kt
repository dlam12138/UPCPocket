package com.upcpocket.app.ui.nav

/**
 * 导航路由的**唯一来源**。
 *
 * 轻量版移除了课表与桌面组件后，路由表只剩三个 tab 和「更多」下的子页；
 * 不再有组件深链 extra，也没有课表相关页面（网格、总览、显示设置、背景裁剪）。
 */
object AppRoutes {

    /** 底部导航的 tab */
    const val HOME = "home"
    const val FOCUS = "focus"
    const val MORE = "more"

    /** 「更多」里的子页（不是 tab，进去之后底部导航栏会隐藏） */
    const val FOCUS_STATS = "focus_stats"
    const val FREE_CLASSROOMS = "free_classrooms"
    const val LIBRARY_SEAT = "library_seat"
    const val LIBRARY_LOGIN = "library_login"
    const val CAMPUS_CARD = "campus_card"
    const val POWER_TANGDAOWAN = "power_tangdaowan"
    const val APPEARANCE = "appearance"
    const val ABOUT = "about"
}
