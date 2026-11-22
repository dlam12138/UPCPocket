package com.upcpocket.app.widget

import android.content.Context
import android.content.Intent

/**
 * 「本周课表」独立 Provider。
 *
 * 与今日课表共用 [WidgetKind.TIMETABLE] 的本地缓存、账号撤下语义与调度；周版没有
 * 刷新入口，也不接受旧的组件刷新广播，避免把今日课表的联网刷新带到周版。
 */
class TimetableWeekWidgetProvider : BaseWidgetProvider() {

    override val variant: WidgetProviderVariant = WidgetProviderVariant.TIMETABLE_WEEK

    override fun onReceive(context: Context, intent: Intent) {
        // 周版只响应系统组件更新与尺寸变化，不承接旧刷新业务操作。
        if (intent.action == WidgetKind.TIMETABLE.refreshAction) return
        super.onReceive(context, intent)
    }
}
