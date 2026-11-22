package com.upcpocket.app.data.prefs

/**
 * 日程待办在应用内一级页的展示位置。
 *
 * 只决定在哪一页渲染同一份本机待办，不改变待办数据、提醒或学校课表查询。
 * 缺键与未知值回退 [TIMETABLE]，保持现有“日程待办在课表页”的默认。
 */
enum class TodoHostPlacement(val label: String) {
    TIMETABLE("课表页"),
    FOCUS("专注页");

    companion object {
        /** DataStore 只保存枚举名；旧安装缺键或未来未知值都回退到课表页。 */
        fun fromStoredValue(raw: String?): TodoHostPlacement =
            entries.firstOrNull { it.name == raw } ?: TIMETABLE
    }
}
