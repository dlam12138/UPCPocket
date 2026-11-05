package com.upcpocket.app.widget

import android.app.PendingIntent
import android.content.Context
import android.util.TypedValue
import android.view.View
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
        val primary = context.getColor(DesignSystemR.color.widget_primary)
        val secondary = context.getColor(DesignSystemR.color.widget_secondary)
        views.setTextColor(R.id.widget_power_building, secondary)
        views.setTextColor(R.id.widget_power_room, primary)
        views.setTextColor(R.id.widget_power_value, primary)

        if (snapshot == null) {
            views.setTextViewText(
                R.id.widget_power_building,
                context.getString(R.string.widget_power_building_none),
            )
            views.setTextViewText(R.id.widget_power_room, "")
            views.setViewVisibility(R.id.widget_power_room, View.GONE)
            views.setTextViewText(
                R.id.widget_power_value,
                context.getString(DesignSystemR.string.widget_empty_value),
            )
            views.setTextViewTextSize(
                R.id.widget_power_value,
                TypedValue.COMPLEX_UNIT_SP,
                HEADLINE_TEXT_SIZE_SP,
            )
            views.setViewVisibility(R.id.widget_power_unit, View.GONE)
            WidgetRenderSupport.setMeta(
                views = views,
                viewId = R.id.widget_power_updated,
                text = when {
                    refreshing -> context.getString(DesignSystemR.string.widget_refreshing)
                    failed -> context.getString(DesignSystemR.string.widget_refresh_failed)
                    else -> context.getString(DesignSystemR.string.widget_empty_hint)
                },
                colorRes = when {
                    refreshing -> DesignSystemR.color.widget_accent
                    failed -> DesignSystemR.color.widget_error
                    else -> DesignSystemR.color.widget_tertiary
                },
            )
            views.setOnClickPendingIntent(R.id.widget_power_refresh, openApp())
            views.setContentDescription(
                R.id.widget_power_refresh,
                context.getString(R.string.widget_open_power),
            )
            views.setFloat(R.id.widget_power_refresh, "setAlpha", if (refreshing) 0.5f else 1f)
            return views
        }

        // 楼号与房号独立渲染；紧凑行优先给房号留出空间。
        views.setTextViewText(R.id.widget_power_building, snapshot.building)
        views.setTextViewText(R.id.widget_power_room, snapshot.room)
        views.setViewVisibility(R.id.widget_power_room, View.VISIBLE)

        val value = snapshot.kwh
        val fontScale = context.resources.configuration.fontScale
        val numberSize = numberTextSize(value, fontScale)
        views.setTextViewText(R.id.widget_power_value, value)
        views.setTextViewTextSize(
            R.id.widget_power_value,
            TypedValue.COMPLEX_UNIT_SP,
            numberSize,
        )
        views.setTextViewTextSize(
            R.id.widget_power_unit,
            TypedValue.COMPLEX_UNIT_SP,
            unitTextSize(numberSize),
        )
        views.setTextColor(R.id.widget_power_unit, secondary)
        views.setViewVisibility(R.id.widget_power_unit, View.VISIBLE)

        WidgetRenderSupport.setMeta(
            views = views,
            viewId = R.id.widget_power_updated,
            text = WidgetRenderSupport.metaText(
                context = context,
                fetchedAt = snapshot.fetchedAt,
                refreshing = refreshing,
                failed = failed,
            ),
            colorRes = WidgetRenderSupport.metaColor(refreshing, failed),
        )

        views.setOnClickPendingIntent(R.id.widget_power_refresh, refresh())
        views.setContentDescription(
            R.id.widget_power_refresh,
            context.getString(R.string.widget_refresh_power),
        )
        views.setFloat(R.id.widget_power_refresh, "setAlpha", if (refreshing) 0.5f else 1f)
        return views
    }

    /**
     * kWh 比“元”宽，长数值/大字号必须更早让位，否则主数值会被省略号裁掉。
     * 这里按字符数分档 + 1.3× 以上字体再缩一档，不做无限 autoSize。
     */
    internal fun numberTextSize(value: String, fontScale: Float = 1f): Float {
        val base = when {
            value.length <= 6 -> 36f
            value.length <= 8 -> 27f
            value.length <= 10 -> 23f
            else -> 18f
        }
        return if (fontScale < 1.3f) {
            base
        } else {
            when {
                value.length <= 6 -> base * 0.65f
                value.length <= 8 -> base * 0.55f
                else -> base * 0.5f
            }
        }
    }

    internal fun unitTextSize(numberSize: Float): Float =
        (numberSize * 0.42f).coerceIn(11f, 15f)

    private companion object {
        const val HEADLINE_TEXT_SIZE_SP = 20f
    }
}
