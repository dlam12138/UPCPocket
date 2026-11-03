package com.upcpocket.app.designsystem

import android.content.Context
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 两个桌面组件共用的“查询于 / 正在刷新 / 刷新失败”时间行。
 *
 * 这里只处理通用展示与绝对时间格式，不认识余额或电量模型；具体字段、文案与
 * PendingIntent 仍由 `:feature:card` / `:feature:power` 各自的渲染器负责。
 */
object WidgetRenderSupport {

    private val SAME_YEAR_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val CROSS_YEAR_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 正常状态只显示绝对时间；刷新中/失败时增加一行状态。
     * 时间始终来自上次成功查询，不能改成失败发生的时间。
     */
    fun metaText(
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

    fun metaColor(refreshing: Boolean, failed: Boolean): Int = when {
        refreshing -> R.color.widget_accent
        failed -> R.color.widget_error
        else -> R.color.widget_secondary
    }

    fun setMeta(
        views: RemoteViews,
        viewId: Int,
        text: String,
        colorRes: Int,
    ) {
        views.setTextViewText(viewId, text)
        // Resolve colors in the launcher host, including when night mode changes.
        views.setColorStateList(viewId, "setTextColor", colorRes)
    }

    /**
     * 本地时区的绝对查询时间。同年用 `MM-dd HH:mm`，跨年补全年份。
     * 参数 `now` / `zone` 仅用于可重复测试。
     */
    fun queryTime(
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
