package com.upcpocket.app.designsystem

import android.content.Context
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 三个桌面组件共用的“更新时间 / 正在更新 / 更新失败”单行状态。
 *
 * 这里只处理通用展示与绝对时间格式，不认识余额、电量或课程模型；具体字段、
 * 文案与 PendingIntent 仍由各 feature 的渲染器负责。时间始终来自上次成功查询，
 * 不会把失败发生的时间伪装成数据时间。
 */
object WidgetRenderSupport {

    private val TODAY_TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val SAME_YEAR_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val CROSS_YEAR_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun metaText(
        context: Context,
        fetchedAt: Long,
        refreshing: Boolean,
        failed: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val time = queryTimeShort(fetchedAt, zone = zone)
        return when {
            refreshing -> context.getString(R.string.widget_refreshing)
            failed -> context.getString(R.string.widget_failed_with_time, time)
            else -> context.getString(R.string.widget_updated, time)
        }
    }

    fun metaColor(refreshing: Boolean, failed: Boolean): Int = when {
        refreshing -> R.color.widget_accent
        failed -> R.color.widget_error
        else -> R.color.widget_tertiary
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
     * 单行更新时间：当天只给 `HH:mm`，同年补月日，跨年补全年份。
     * `now` / `zone` 仅用于可重复测试。
     */
    fun queryTimeShort(
        fetchedAt: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val at = Instant.ofEpochMilli(fetchedAt).atZone(zone)
        val current = Instant.ofEpochMilli(now).atZone(zone)
        val formatter = when {
            at.toLocalDate() == current.toLocalDate() -> TODAY_TIME
            at.year == current.year -> SAME_YEAR_TIME
            else -> CROSS_YEAR_TIME
        }
        return formatter.format(at)
    }
}
