package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager as WidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 两个组件的共同入口。
 *
 * 安全：
 *  - manifest 里 `exported=false`，自定义刷新广播只接受本应用显式 PendingIntent；
 *  - 刷新前用 [WidgetIntents.isOwnedBy] 校验 appWidgetId 确实属于本 provider；
 *  - 广播里只有 appWidgetId，学号/房间号从不进入 Intent。
 *
 * 刷新语义：
 *  - onUpdate / onEnabled / resize / 应用启动只渲染缓存；
 *  - 只有点击刷新才入队一次性 WorkManager 任务；
 *  - 无成功快照时按钮引导打开应用，不发空请求。
 */
abstract class BaseWidgetProvider : AppWidgetProvider() {

    protected abstract val kind: WidgetKind

    override fun onUpdate(
        context: Context,
        appWidgetManager: WidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdates.updateKindNow(context, kind)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: WidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        WidgetUpdates.updateKindNow(context, kind)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdates.updateKindNow(context, kind)
    }

    override fun onDisabled(context: Context) {
        WidgetWork.cancel(context, kind)
        WidgetStateStore(context).clear(kind)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val remaining = WidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, kind.providerClass))
        if (remaining.isEmpty()) {
            WidgetWork.cancel(context, kind)
            WidgetStateStore(context).clear(kind)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == kind.refreshAction) {
            val widgetId = intent.getIntExtra(
                WidgetManager.EXTRA_APPWIDGET_ID,
                WidgetManager.INVALID_APPWIDGET_ID,
            )
            if (!WidgetIntents.isOwnedBy(context, kind, widgetId)) return
            requestRefresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    private fun requestRefresh(context: Context) {
        // 快照可能刚被用户清除：只重绘“待设置”，不发空请求。
        if (!WidgetUpdates.hasTargetNow(kind)) {
            WidgetUpdates.updateKindNow(context, kind)
            return
        }
        val state = WidgetStateStore(context)
        // 同一类型连续点击合并；也避免 WorkManager 入队前的重复广播。
        if (!state.tryBegin(kind)) {
            WidgetUpdates.updateKindNow(context, kind)
            return
        }
        WidgetUpdates.updateKindNow(context, kind)
        if (!WidgetWork.enqueue(context, kind)) {
            state.clearRefreshing(kind)
            state.setError(kind, "无法启动刷新任务")
            WidgetUpdates.updateKindNow(context, kind)
        }
    }

    /** 仅供调试/测试确认 provider 身份；运行时也用于判断是否最后一个小部件。 */
    internal fun ids(context: Context): IntArray =
        WidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, kind.providerClass))
}
