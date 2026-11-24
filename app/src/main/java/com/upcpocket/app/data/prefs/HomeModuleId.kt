package com.upcpocket.app.data.prefs

import com.upcpocket.app.ui.components.normalizeModuleOrder

/**
 * 首页可长按拖动排序的功能模块标识。
 *
 * 仅控制首页区块展示顺序，不改变各插件的数据、路由或账号归属；
 * 缺键或未知枚举名安全回退到 [DEFAULT_ORDER]。
 */
enum class HomeModuleId(val label: String) {
    TIMETABLE("今日课程"),
    TODO("待办"),
    SERVICES("常用服务"),
    CARD("校园卡");

    companion object {
        val DEFAULT_ORDER: List<HomeModuleId> = listOf(TIMETABLE, TODO, SERVICES, CARD)

        fun fromStoredValue(raw: String?): List<HomeModuleId> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val parsed = raw.split(',')
                .mapNotNull { token -> entries.firstOrNull { it.name == token.trim() } }
            return normalizeModuleOrder(parsed, DEFAULT_ORDER)
        }

        fun toStoredValue(order: List<HomeModuleId>): String =
            normalizeModuleOrder(order, DEFAULT_ORDER).joinToString(",") { it.name }
    }
}
