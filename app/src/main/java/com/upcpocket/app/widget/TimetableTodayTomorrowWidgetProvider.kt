package com.upcpocket.app.widget

import android.content.Context
import android.content.Intent

/** 「今明课表」独立入口；沿用课表本地缓存、账号归属与整卡导航。 */
class TimetableTodayTomorrowWidgetProvider : BaseWidgetProvider() {
    override val variant = WidgetProviderVariant.TIMETABLE_TODAY_TOMORROW

    // 旧今日课表的刷新广播仍可到达同 kind 的其它 Provider；今明版不响应，
    // 由同步成功、日期/尺寸/系统时间变化和周期兜底统一重绘。
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetKind.TIMETABLE.refreshAction) return
        super.onReceive(context, intent)
    }
}
