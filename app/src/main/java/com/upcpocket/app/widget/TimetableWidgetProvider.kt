package com.upcpocket.app.widget

/** 课表 RemoteViews 组件；不迁移 Glance，布局和渲染在 :feature:timetable。 */
class TimetableWidgetProvider : BaseWidgetProvider() {
    override val kind: WidgetKind = WidgetKind.TIMETABLE

    override fun onDisabled(context: android.content.Context) {
        super.onDisabled(context)
        TimetableWidgetTicker.cancel(context)
    }

    override fun onDeleted(context: android.content.Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // 还有实例时保留下一次本地边界；最后一个实例删除后 schedule 会取消唯一任务。
        TimetableWidgetTicker.schedule(context)
    }
}
