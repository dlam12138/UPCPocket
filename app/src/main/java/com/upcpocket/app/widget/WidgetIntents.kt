package com.upcpocket.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.upcpocket.app.MainActivity

/** 组件打开应用时只允许这两个固定目标，不接受 URL、路由字符串或任意 Intent。 */
enum class WidgetLaunchTarget(internal val activityRequestBase: Int) {
    CARD_BALANCE(100_000),
    POWER_TANGDAOWAN(200_000),
    ;

    companion object {
        fun fromIntent(intent: Intent?): WidgetLaunchTarget? {
            if (intent == null) return null
            if (intent.action != WidgetIntents.ACTION_OPEN_FROM_WIDGET) return null
            // 组件入口不需要 data/URI；带 URL 的 Intent 直接拒绝，避免被当成转发目标。
            if (intent.data != null) return null
            val raw = intent.getStringExtra(WidgetIntents.EXTRA_WIDGET_TARGET) ?: return null
            return entries.firstOrNull { it.name == raw }
        }
    }
}

/**
 * 组件所有 PendingIntent 的唯一构造点。
 *
 * 安全约束：
 *  - 全部显式组件（MainActivity / 各自的 AppWidgetProvider）；
 *  - 全部 FLAG_IMMUTABLE；
 *  - 刷新广播只带 appWidgetId，绝不携带学号、楼号、房间号或 URL；
 *  - 校园卡与电费使用不同的 requestCode / action / 组件，互不覆盖 extras。
 */
object WidgetIntents {

    const val ACTION_OPEN_FROM_WIDGET = "com.upcpocket.app.action.OPEN_FROM_WIDGET"
    const val EXTRA_WIDGET_TARGET = "com.upcpocket.app.extra.WIDGET_TARGET"

    private const val REFRESH_REQUEST_BASE_CARD = 300_000
    private const val REFRESH_REQUEST_BASE_POWER = 400_000

    internal fun activityRequestCode(target: WidgetLaunchTarget, widgetId: Int): Int =
        target.activityRequestBase + widgetId * 10 + 1

    internal fun refreshRequestCode(kind: WidgetKind, widgetId: Int): Int =
        when (kind) {
            WidgetKind.CARD -> REFRESH_REQUEST_BASE_CARD + widgetId
            WidgetKind.POWER -> REFRESH_REQUEST_BASE_POWER + widgetId
        }

    internal fun activityIntent(
        context: Context,
        target: WidgetLaunchTarget,
        widgetId: Int,
    ): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_FROM_WIDGET
        putExtra(EXTRA_WIDGET_TARGET, target.name)
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }

    internal fun refreshIntent(
        context: Context,
        kind: WidgetKind,
        widgetId: Int,
    ): Intent = Intent(context, kind.providerClass).apply {
        action = kind.refreshAction
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
    }

    fun openApp(
        context: Context,
        target: WidgetLaunchTarget,
        widgetId: Int,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        activityRequestCode(target, widgetId),
        activityIntent(context, target, widgetId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun refresh(
        context: Context,
        kind: WidgetKind,
        widgetId: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        refreshRequestCode(kind, widgetId),
        refreshIntent(context, kind, widgetId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 只接受确实属于该 provider 的实例，防止伪造 appWidgetId 触发别的组件的刷新。 */
    fun isOwnedBy(context: Context, kind: WidgetKind, widgetId: Int): Boolean {
        if (widgetId <= 0) return false
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId) ?: return false
        return info.provider == ComponentName(context, kind.providerClass)
    }
}
