package com.upcpocket.app.widget

/** 课表 RemoteViews 组件；不迁移 Glance，布局和渲染在 :feature:timetable。 */
class TimetableWidgetProvider : BaseWidgetProvider() {
    override val kind: WidgetKind = WidgetKind.TIMETABLE
}
