package com.upcpocket.app.widget

import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import com.upcpocket.app.data.power.PowerSnapshot
import com.upcpocket.app.designsystem.WidgetRenderSupport
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.feature.power.R

/**
 * 唐岛湾剩余电量的 RemoteViews 渲染器。
 *
 * 布局、楼号/房间号字段、空状态与刷新按钮都在本模块；宿主只负责稳定组件标识和
 * “打开应用 / 刷新”的 PendingIntent。失败时保留上次成功值与时间。
 */
class PowerWidgetRenderer(private val context: Context) {

    fun views(
        snapshot: PowerSnapshot?,
        refreshing: Boolean,
        failed: Boolean,
        openApp: () -> PendingIntent,
        refresh: () -> PendingIntent,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_power_balance)
        views.setOnClickPendingIntent(R.id.widget_power_root, openApp())

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
                context.getString(DesignSystemR.string.widget_empty_value),
            )
            WidgetRenderSupport.setMeta(
                views = views,
                viewId = R.id.widget_power_updated,
                text = context.getString(DesignSystemR.string.widget_empty_hint) + "\n" +
                    context.getString(DesignSystemR.string.widget_no_record),
                colorRes = DesignSystemR.color.widget_accent,
            )
            views.setOnClickPendingIntent(R.id.widget_power_refresh, openApp())
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
        WidgetRenderSupport.setMeta(
            views = views,
            viewId = R.id.widget_power_updated,
            text = WidgetRenderSupport.metaText(context, snapshot.fetchedAt, refreshing, failed),
            colorRes = WidgetRenderSupport.metaColor(refreshing, failed),
        )

        views.setOnClickPendingIntent(R.id.widget_power_refresh, refresh())
        views.setContentDescription(
            R.id.widget_power_refresh,
            context.getString(R.string.widget_refresh_power),
        )
        views.setFloat(R.id.widget_power_refresh, "setAlpha", if (refreshing) 0.55f else 1f)
        return views
    }
}
