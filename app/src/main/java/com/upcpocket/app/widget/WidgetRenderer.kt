package com.upcpocket.app.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
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
 *
 * 时间必须是**绝对查询时间**。组件没有周期重绘，写“刚刚 / 15 分钟前”会在桌面上
 * 永久停留成过期文案；这里只在用户查询成功时记录时间，失败不覆盖它。
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
            setMeta(
                views = views,
                viewId = R.id.widget_card_updated,
                text = context.getString(R.string.widget_empty_hint) + "\n" +
                    context.getString(R.string.widget_no_record),
                colorRes = R.color.widget_accent,
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
        // 2×2 只突出主余额；过渡余额仍可在应用里查看。
        views.setViewVisibility(R.id.widget_card_transition, View.GONE)
        views.setTextViewText(
            R.id.widget_card_transition,
            context.getString(R.string.widget_transition, snapshot.transitionText),
        )
        setMeta(
            views = views,
            viewId = R.id.widget_card_updated,
            text = metaText(context, snapshot.fetchedAt, refreshing, failed),
            colorRes = metaColor(refreshing, failed),
        )

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
                R.id.widget_power_building,
                context.getString(R.string.widget_power_building_none),
            )
            views.setTextViewText(
                R.id.widget_power_room,
                context.getString(R.string.widget_power_room_none),
            )
            views.setTextViewText(
                R.id.widget_power_value,
                context.getString(R.string.widget_empty_value),
            )
            setMeta(
                views = views,
                viewId = R.id.widget_power_updated,
                text = context.getString(R.string.widget_empty_hint) + "\n" +
                    context.getString(R.string.widget_no_record),
                colorRes = R.color.widget_accent,
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

        // 楼号与房号独立渲染；紧凑行优先给房号留出空间。
        views.setTextViewText(R.id.widget_power_building, snapshot.building)
        views.setTextViewText(R.id.widget_power_room, snapshot.room)
        views.setTextViewText(
            R.id.widget_power_value,
            context.getString(R.string.widget_kwh_value, snapshot.kwh),
        )
        setMeta(
            views = views,
            viewId = R.id.widget_power_updated,
            text = metaText(context, snapshot.fetchedAt, refreshing, failed),
            colorRes = metaColor(refreshing, failed),
        )

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

    /**
     * 正常状态只显示绝对时间；刷新中/失败时增加一行状态。
     * 时间始终来自上次成功查询，不能改成失败发生的时间。
     */
    private fun metaText(
        context: Context,
        fetchedAt: Long,
        refreshing: Boolean,
        failed: Boolean,
    ): String {
        val label = when {
            refreshing -> context.getString(R.string.widget_refreshing)
            failed -> context.getString(R.string.widget_refresh_failed)
            else -> context.getString(R.string.widget_query_label)
        }
        return if (!refreshing && !failed) queryTime(fetchedAt)
        else label + "\n" + queryTime(fetchedAt)
    }

    private fun metaColor(refreshing: Boolean, failed: Boolean): Int = when {
        refreshing -> R.color.widget_accent
        failed -> R.color.widget_error
        else -> R.color.widget_secondary
    }

    private fun setMeta(
        views: RemoteViews,
        viewId: Int,
        text: String,
        colorRes: Int,
    ) {
        views.setTextViewText(viewId, text)
        // Resolve colors in the launcher host, including when night mode changes.
        views.setColorStateList(viewId, "setTextColor", colorRes)
    }

    private val SAME_YEAR_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val CROSS_YEAR_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 本地时区的绝对查询时间。同年用 `MM-dd HH:mm`，跨年补全年份。
     * 参数 `now` / `zone` 仅用于可重复测试。
     */
    internal fun queryTime(
        fetchedAt: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val at = Instant.ofEpochMilli(fetchedAt).atZone(zone)
        val current = Instant.ofEpochMilli(now).atZone(zone)
        val formatter = if (at.year == current.year) SAME_YEAR_TIME else CROSS_YEAR_TIME
        return formatter.format(at)
    }
}
