package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Local-only redraw scheduler for the timetable widget.
 *
 * It never reads the network, credentials, or a course service. It schedules one
 * one-shot WorkManager job for the next relevant local wall-clock boundary
 * (next class start/end or local midnight); the worker only re-renders the cached
 * snapshot. WorkManager is persisted across ordinary process death, but Android
 * may defer background work because of Doze/app-standby/battery policy. This is
 * therefore a best-effort local redraw, not a minute-accurate alarm.
 */
object TimetableWidgetTicker {

    const val UNIQUE_WORK_NAME = "timetable-widget-local-redraw"
    private val ZONE = ZoneId.of("Asia/Shanghai")

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(appContext, TimetableWidgetProvider::class.java),
            )
            if (ids.isEmpty()) {
                WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }
            val snapshot = WidgetUpdates.timetableSnapshotForSchedule(appContext)
            val now = ZonedDateTime.now(ZONE)
            val delay = Duration.between(now, nextBoundary(snapshot, now))
                .toMillis()
                .coerceAtLeast(60_000L)
            val request = OneTimeWorkRequestBuilder<TimetableWidgetTickWorker>()
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(Data.EMPTY)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    /**
     * Pure boundary calculation, exposed for unit tests. Returns the next local
     * midnight or the next class start/end strictly after [now].
     */
    fun nextBoundary(
        snapshot: TimetableWidgetSnapshot?,
        now: ZonedDateTime,
    ): ZonedDateTime {
        val localNow = now.withZoneSameInstant(ZONE)
        val today = localNow.toLocalDate()
        val tomorrow = today.plusDays(1)
        val midnight = tomorrow.atStartOfDay(ZONE)
        if (snapshot == null ||
            snapshot.status != TimetableWidgetStatus.OK ||
            snapshot.date != today
        ) {
            return midnight
        }
        val nowMinute = localNow.hour * 60 + localNow.minute
        val candidates = buildList {
            snapshot.courses.forEach { course ->
                course.startMinute?.takeIf { it > nowMinute }?.let {
                    add(atMinute(localNow.toLocalDate(), it))
                }
                course.endMinute?.takeIf { it > nowMinute }?.let {
                    add(atMinute(localNow.toLocalDate(), it))
                }
            }
            add(midnight)
        }
        return candidates.minOrNull() ?: midnight
    }

    private fun atMinute(date: LocalDate, minute: Int): ZonedDateTime =
        ZonedDateTime.of(date, LocalTime.of(minute / 60, minute % 60), ZONE)
}
