package com.upcpocket.app.data.prefs

/**
 * 常用校区：功能页进入时默认展示的校区。
 *
 * 只影响本机默认展示，不改变学校接口、请求参数或登录范围。缺失键与未知值都按
 * [ALL] 处理；[ALL] 保持各页面原有的多校区/上次校区行为。页内临时切换不写回
 * 这里，重新进入时按保存值恢复。
 */
enum class PreferredCampus(val label: String) {
    ALL("全部校区"),
    TANGDAOWAN("唐岛湾"),
    GUZHENKOU("古镇口");

    companion object {
        /** DataStore 只保存枚举名；旧安装缺键或未来未知值都回退到全部校区。 */
        fun fromStoredValue(raw: String?): PreferredCampus =
            entries.firstOrNull { it.name == raw } ?: ALL
    }
}
