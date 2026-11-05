package com.upcpocket.app.debug

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.upcpocket.app.timetable.TimetableWidgetCourse
import com.upcpocket.app.timetable.TimetableWidgetRenderer
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
import com.upcpocket.app.widget.WidgetIntents
import com.upcpocket.app.widget.WidgetLaunchTarget
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * DEBUG-ONLY Launcher fixture for the real [TimetableWidgetRenderer].
 *
 * It never touches AppGraph, account state, the timetable service, or its cache.
 * The release APK does not contain this provider.
 */
class SyntheticTimetableWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateAll(context, SyntheticTimetableWidgetProvider::class.java)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            cycle(context, javaClass)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_REFRESH = "com.upcpocket.app.debug.REFRESH_SYNTHETIC_TIMETABLE"
        private const val PREFS = "synthetic_timetable_widget"
        private const val KEY_SCENARIO = "scenario"
        private const val SCENARIO_COUNT = 4
        private val ZONE = ZoneId.of("Asia/Shanghai")

        fun cycle(context: Context, providerClass: Class<*>) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = (prefs.getInt(KEY_SCENARIO, 0) + 1) % SCENARIO_COUNT
            prefs.edit().putInt(KEY_SCENARIO, next).apply()
            updateAll(context, providerClass)
        }

        fun updateAll(context: Context, providerClass: Class<*>) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, providerClass)
            manager.getAppWidgetIds(component).forEach { id ->
                manager.updateAppWidget(id, render(context, providerClass, id))
            }
        }

        private fun render(
            context: Context,
            providerClass: Class<*>,
            widgetId: Int,
        ) = TimetableWidgetRenderer.render(
            context = context,
            snapshot = snapshot(context),
            showSecondary = true,
            refreshing = false,
            failed = false,
            showDate = providerClass == SyntheticTimetableWideWidgetProvider::class.java ||
                context.resources.configuration.fontScale < 1.3f,
            openIntent = WidgetIntents.openApp(
                context,
                WidgetLaunchTarget.TIMETABLE,
                widgetId,
            ),
            refreshIntent = PendingIntent.getBroadcast(
                context,
                widgetId,
                Intent(context, providerClass).apply {
                    action = ACTION_REFRESH
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        private fun snapshot(context: Context): TimetableWidgetSnapshot {
            val scenario = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_SCENARIO, 0)
            val now = LocalTime.now(ZONE)
            val nowMinute = now.hour * 60 + now.minute
            val courses = when (scenario % SCENARIO_COUNT) {
                0 -> currentAndNext(nowMinute, long = false)
                1 -> nextOnly(nowMinute, long = false)
                2 -> ended(nowMinute)
                else -> currentAndNext(nowMinute, long = true)
            }
            return TimetableWidgetSnapshot(
                status = TimetableWidgetStatus.OK,
                date = LocalDate.now(ZONE),
                courses = courses,
                fetchedAtEpochMillis = System.currentTimeMillis(),
            )
        }

        private fun currentAndNext(now: Int, long: Boolean): List<TimetableWidgetCourse> {
            val start = (now - 10).coerceAtLeast(1)
            val end = (now + 50).coerceAtMost(23 * 60 + 59)
            val nextStart = (end + 10).coerceAtMost(23 * 60 + 58)
            val nextEnd = (nextStart + 45).coerceAtMost(23 * 60 + 59)
            return listOf(
                course(
                    name = if (long) "化工原理与传递过程综合实验（合成）" else "化工原理（2–2）",
                    location = if (long) "卓越教学楼 B205 西侧联合实验区" else "卓越 B205",
                    start = start,
                    end = end,
                    period = "第3–4节",
                    slot = 2,
                ),
                course(
                    name = if (long) "数据处理与实验设计方法研讨（合成）" else "数据处理与实验设计",
                    location = if (long) "古镇口校区图书馆三层研讨空间" else "卓越 B325",
                    start = nextStart,
                    end = nextEnd,
                    period = "第5–6节",
                    slot = 5,
                ),
            )
        }

        private fun nextOnly(now: Int, long: Boolean): List<TimetableWidgetCourse> {
            val start = (now + 60).coerceAtMost(23 * 60 + 58)
            val end = (start + 45).coerceAtMost(23 * 60 + 59)
            val secondStart = (end + 10).coerceAtMost(23 * 60 + 58)
            val secondEnd = (secondStart + 45).coerceAtMost(23 * 60 + 59)
            return listOf(
                course(
                    name = if (long) "高等数学（下）习题与讨论（合成）" else "高等数学（下）",
                    location = if (long) "基础实验楼 A 区 301 阶梯教室" else "A301",
                    start = start,
                    end = end,
                    period = "第7–8节",
                    slot = 7,
                ),
                course(
                    name = "大学物理实验（合成）",
                    location = "B214",
                    start = secondStart,
                    end = secondEnd,
                    period = "第9–10节",
                    slot = 9,
                ),
            )
        }

        private fun ended(now: Int): List<TimetableWidgetCourse> {
            val end = (now - 30).coerceAtLeast(1)
            val start = (end - 45).coerceAtLeast(0)
            return listOf(
                course(
                    name = "中国近现代史纲要（合成）",
                    location = "文理楼 C102",
                    start = start,
                    end = end,
                    period = "第1–2节",
                    slot = 11,
                ),
            )
        }

        private fun course(
            name: String,
            location: String,
            start: Int,
            end: Int,
            period: String,
            slot: Int,
        ) = TimetableWidgetCourse(
            name = name,
            location = location,
            timeLabel = clock(start) + "–" + clock(end),
            startMinute = start,
            endMinute = end,
            periodLabel = period,
            colorSlot = slot,
        )

        private fun clock(minute: Int): String =
            String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
    }
}

/** Same synthetic data, but pinned as a four-column fixture for the 4×2 layout check. */
class SyntheticTimetableWideWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        SyntheticTimetableWidgetProvider.updateAll(context, javaClass)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SyntheticTimetableWidgetProvider.ACTION_REFRESH) {
            SyntheticTimetableWidgetProvider.cycle(context, javaClass)
            return
        }
        super.onReceive(context, intent)
    }
}
