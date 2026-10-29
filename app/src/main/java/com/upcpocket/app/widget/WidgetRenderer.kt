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
                context = context,
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
        views.setViewVisibility(R.id.widget_card_transition, View.VISIBLE)
        views.setTextViewText(
            R.id.widget_card_transition,
            context.getString(R.string.widget_transition, snapshot.transitionText),
        )
        setMeta(
            context = context,
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
                context = context,
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

        // 楼号与房间号拆成两行：2.0× 字号或窄宽时，房号不会再被楼号挤掉。
        views.setTextViewText(R.id.widget_power_building, snapshot.building)
        views.setTextViewText(R.id.widget_power_room, snapshot.room)
        views.setTextViewText(
            R.id.widget_power_value,
            context.getString(R.string.widget_kwh_value, snapshot.kwh),
        )
        setMeta(
            context = context,
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
     * 两行结构：第一行状态（查询于/正在刷新/刷新失败），第二行是**上次成功查询**的
     * 绝对时间。失败和刷新中都不改这个时间。
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
        return label + "\n" + queryTime(fetchedAt)
    }

    private fun metaColor(refreshing: Boolean, failed: Boolean): Int = when {
        refreshing -> R.color.widget_accent
        failed -> R.color.widget_error
        else -> R.color.widget_secondary
    }

    private fun setMeta(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        text: String,
        colorRes: Int,
    ) {
        views.setTextViewText(viewId, text)
        views.setTextColor(viewId, ContextCompat.getColor(context, colorRes))
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
