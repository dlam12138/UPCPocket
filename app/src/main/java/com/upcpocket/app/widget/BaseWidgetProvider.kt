package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager as WidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.upcpocket.app.core.AppGraph

/**
 * 桌面组件的共同入口。
 *
 * 安全：
 *  - manifest 里 `exported=false`，自定义刷新广播只接受本应用显式 PendingIntent；
 *  - 刷新前用 [WidgetIntents.isOwnedBy] 校验 appWidgetId 确实属于当前 provider；
 *  - 广播里只有 appWidgetId，学号/房间号从不进入 Intent。
 *
 * 刷新语义：
 *  - onUpdate / onEnabled / resize / 应用启动只渲染缓存；
 *  - 有实例时保留校园卡周期取数与课表周期本地重绘，电量保持手动刷新；
 *  - 旧版本已经发出的刷新 PendingIntent 保留兼容，目标仍由当前绑定决定。
 */
abstract class BaseWidgetProvider : AppWidgetProvider() {

    protected abstract val variant: WidgetProviderVariant

    protected val kind: WidgetKind get() = variant.kind

    override fun onUpdate(
        context: Context,
        appWidgetManager: WidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdates.updateKindNow(context, kind)
        WidgetAutoRefresh.reconcile(context)
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
        WidgetAutoRefresh.reconcile(context)
    }

    final override fun onDisabled(context: Context) {
        reconcileAfterRemoval(context)
    }

    final override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        reconcileAfterRemoval(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (kind == WidgetKind.TODO) {
            super.onReceive(context, intent)
            return
        }
        if (intent.action == kind.refreshAction) {
            val widgetId = intent.getIntExtra(
                WidgetManager.EXTRA_APPWIDGET_ID,
                WidgetManager.INVALID_APPWIDGET_ID,
            )
            if (!WidgetIntents.isOwnedBy(context, variant, widgetId)) return
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
        val token = if (kind == WidgetKind.CARD) {
            AppGraph.cardServiceOrNull?.bindingToken()
        } else {
            null
        }
        if (!WidgetWork.enqueue(context, kind, token)) {
            state.clearRefreshing(kind)
            state.setError(kind, "无法启动刷新任务")
            WidgetUpdates.updateKindNow(context, kind)
        }
    }

    private fun reconcileAfterRemoval(context: Context) {
        if (hasWidgetInstances(context, kind)) {
            WidgetAutoRefresh.reconcile(context)
            WidgetUpdates.updateKindNow(context, kind)
            return
        }
        WidgetWork.cancel(context, kind)
        WidgetAutoRefresh.cancel(context, kind)
        WidgetStateStore(context).clear(kind)
    }

    /** 仅供调试/测试确认当前 provider 的实例身份。 */
    internal fun ids(context: Context): IntArray =
        WidgetManager.getInstance(context)
            .getAppWidgetIds(android.content.ComponentName(context, variant.providerClass))
}
