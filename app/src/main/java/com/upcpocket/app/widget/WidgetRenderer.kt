package com.upcpocket.app.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.upcpocket.app.R
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.data.power.PowerSnapshot
import com.upcpocket.app.data.prefs.SchoolSessionStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 把两份成功快照渲染成极简 RemoteViews。
 *
 * RemoteViews 不申请截屏/壁纸权限，也不拿大位图硬模拟模糊：用半透明渐变底 + 细描边
 * 做“玻璃”观感，所有文字用高对比色。亮/暗资源由 values-night 提供。
 */
object WidgetRenderer {

    fun views(
        context: Context,
        kind: WidgetKind,
        widgetId: Int,
        card: DirectCardSnapshot?,
        power: PowerSnapshot?,
        refreshing: Boolean,
        failed: Boolean,
    ): RemoteViews = when (kind) {
        WidgetKind.CARD -> cardViews(context, widgetId, card, refreshing, failed)
        WidgetKind.POWER -> powerViews(context, widgetId, power, refreshing, failed)
    }

    fun cardViews(
        context: Context,
        widgetId: Int,
        snapshot: DirectCardSnapshot?,
        refreshing: Boolean,
        failed: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_card_balance)
        views.setOnClickPendingIntent(
            R.id.widget_card_root,
            WidgetIntents.openApp(context, WidgetLaunchTarget.CARD_BALANCE, widgetId),
        )

        if (snapshot == null) {
            views.setTextViewText(
                R.id.widget_card_account,
                context.getString(R.string.widget_card_account_none),
            )
            views.setTextViewText(
                R.id.widget_card_balance,
                context.getString(R.string.widget_empty_value),
            )
            views.setViewVisibility(R.id.widget_card_transition, View.GONE)
            showStatus(
                context,
                views,
                R.id.widget_card_status,
                context.getString(R.string.widget_empty_hint),
                R.color.widget_accent,
            )
            views.setTextViewText(
                R.id.widget_card_updated,
                context.getString(R.string.widget_no_record),
            )
            views.setOnClickPendingIntent(
                R.id.widget_card_refresh,
                WidgetIntents.openApp(context, WidgetLaunchTarget.CARD_BALANCE, widgetId),
            )
            views.setContentDescription(
                R.id.widget_card_refresh,
                context.getString(R.string.widget_open_card),
            )
            views.setFloat(R.id.widget_card_refresh, "setAlpha", 1f)
            return views
        }

        views.setTextViewText(
            R.id.widget_card_account,
            context.getString(
                R.string.widget_card_account,
                SchoolSessionStore.maskAccount(snapshot.studentId),
            ),
        )
        views.setTextViewText(
            R.id.widget_card_balance,
            context.getString(R.string.widget_balance_value, snapshot.balanceText),
        )
        views.setViewVisibility(R.id.widget_card_transition, View.VISIBLE)
        views.setTextViewText(
            R.id.widget_card_transition,
            context.getString(R.string.widget_transition, snapshot.transitionText),
        )
        views.setTextViewText(
            R.id.widget_card_updated,
            context.getString(R.string.widget_updated, relativeTime(snapshot.fetchedAt)),
        )

        if (refreshing) {
            showStatus(
                context,
                views,
                R.id.widget_card_status,
                context.getString(R.string.widget_refreshing),
                R.color.widget_accent,
            )
        } else if (failed) {
            showStatus(
                context,
                views,
                R.id.widget_card_status,
                context.getString(R.string.widget_refresh_failed),
                R.color.widget_error,
            )
        } else {
            views.setViewVisibility(R.id.widget_card_status, View.GONE)
        }

        views.setOnClickPendingIntent(
            R.id.widget_card_refresh,
            WidgetIntents.refresh(context, WidgetKind.CARD, widgetId),
        )
        views.setContentDescription(
            R.id.widget_card_refresh,
            context.getString(R.string.widget_refresh_card),
        )
        views.setFloat(R.id.widget_card_refresh, "setAlpha", if (refreshing) 0.55f else 1f)
        return views
    }

    fun powerViews(
        context: Context,
        widgetId: Int,
        snapshot: PowerSnapshot?,
        refreshing: Boolean,
        failed: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_power_balance)
        views.setOnClickPendingIntent(
            R.id.widget_power_root,
            WidgetIntents.openApp(context, WidgetLaunchTarget.POWER_TANGDAOWAN, widgetId),
        )

        if (snapshot == null) {
            views.setTextViewText(
                R.id.widget_power_room,
                context.getString(R.string.widget_room_none),
            )
            views.setTextViewText(
                R.id.widget_power_value,
                context.getString(R.string.widget_empty_value),
            )
            showStatus(
                context,
                views,
                R.id.widget_power_status,
                context.getString(R.string.widget_empty_hint),
                R.color.widget_accent,
            )
            views.setTextViewText(
                R.id.widget_power_updated,
                context.getString(R.string.widget_no_record),
            )
            views.setOnClickPendingIntent(
                R.id.widget_power_refresh,
                WidgetIntents.openApp(context, WidgetLaunchTarget.POWER_TANGDAOWAN, widgetId),
            )
            views.setContentDescription(
                R.id.widget_power_refresh,
                context.getString(R.string.widget_open_power),
            )
            views.setFloat(R.id.widget_power_refresh, "setAlpha", 1f)
            return views
        }

        views.setTextViewText(
            R.id.widget_power_room,
            context.getString(R.string.widget_room, snapshot.building, snapshot.room),
        )
        views.setTextViewText(
            R.id.widget_power_value,
            context.getString(R.string.widget_kwh_value, snapshot.kwh),
        )
        views.setTextViewText(
            R.id.widget_power_updated,
            context.getString(R.string.widget_updated, relativeTime(snapshot.fetchedAt)),
        )

        if (refreshing) {
            showStatus(
                context,
                views,
                R.id.widget_power_status,
                context.getString(R.string.widget_refreshing),
                R.color.widget_accent,
            )
        } else if (failed) {
            showStatus(
                context,
                views,
                R.id.widget_power_status,
                context.getString(R.string.widget_refresh_failed),
                R.color.widget_error,
            )
        } else {
            views.setViewVisibility(R.id.widget_power_status, View.GONE)
        }

        views.setOnClickPendingIntent(
            R.id.widget_power_refresh,
            WidgetIntents.refresh(context, WidgetKind.POWER, widgetId),
        )
        views.setContentDescription(
            R.id.widget_power_refresh,
            context.getString(R.string.widget_refresh_power),
        )
        views.setFloat(R.id.widget_power_refresh, "setAlpha", if (refreshing) 0.55f else 1f)
        return views
    }

    private fun showStatus(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        text: String,
        colorRes: Int,
    ) {
        views.setViewVisibility(viewId, View.VISIBLE)
        views.setTextViewText(viewId, text)
        views.setTextColor(viewId, ContextCompat.getColor(context, colorRes))
    }

    private val DATE = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    internal fun relativeTime(fetchedAt: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - fetchedAt).coerceAtLeast(0L)
        val minutes = diff / 60_000L
        val hours = diff / 3_600_000L
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            hours < 24 -> "$hours 小时前"
            else -> DATE.withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(fetchedAt))
        }
    }
}
