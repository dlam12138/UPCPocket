package com.upcpocket.app.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

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
    internal const val KEY_BOUNDARY = "timetable_local_boundary"
    private val ZONE = ZoneId.of("Asia/Shanghai")
    private val lock = Any()
    private val plan = TimetableTickSchedule()

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            runCatching { enqueueNext(appContext, ExistingWorkPolicy.REPLACE) }
        }
    }

    fun cancel(context: Context) {
        synchronized(lock) {
            plan.clear()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(UNIQUE_WORK_NAME)
            }
        }
    }

    fun reschedule(context: Context) {
        // Keep invalidation and replacement atomic relative to an old Worker's begin.
        synchronized(lock) {
            cancel(context)
            runCatching { enqueueNext(context.applicationContext, ExistingWorkPolicy.REPLACE) }
        }
    }

    /** A running boundary owns continuation; account restore callbacks only redraw it. */
    internal fun begin(context: Context, id: UUID): Boolean = synchronized(lock) {
        hasWidgets(context) && plan.begin(id)
    }

    internal fun finish(context: Context, id: UUID, stopped: Boolean) {
        synchronized(lock) {
            if (!plan.finish(id)) return
            if (!stopped) {
                // REPLACE would cancel this still-running Worker. Append the successor
                // after its success; a deleted/replaced job cannot append a stale one.
                runCatching { enqueueNext(context.applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE) }
            }
        }
    }

    private fun hasWidgets(context: Context): Boolean =
        hasWidgetInstances(context, WidgetKind.TIMETABLE)

    private fun enqueueNext(context: Context, policy: ExistingWorkPolicy) {
        if (!hasWidgets(context) || AppGraph.timetableServiceOrNull == null) {
            cancel(context)
            return
        }
        val snapshot = WidgetUpdates.timetableSnapshotForSchedule(context)
        val now = ZonedDateTime.now(ZONE)
        val boundary = nextBoundary(snapshot, now)
        val at = boundary.toInstant().toEpochMilli()
        if (!plan.shouldSchedule(at)) return
        val request = OneTimeWorkRequestBuilder<TimetableWidgetTickWorker>()
            .setInitialDelay(
                Duration.between(now, boundary).toMillis().coerceAtLeast(1_000L),
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            .setInputData(androidx.work.workDataOf(KEY_BOUNDARY to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        plan.scheduled(request.id, at)
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

/** In-process arbitration only; WorkManager owns durable requests and reboot recovery. */
internal class TimetableTickSchedule {
    private var pendingId: UUID? = null
    private var boundary: Long? = null
    private var runningId: UUID? = null

    fun shouldSchedule(at: Long): Boolean = runningId == null && boundary != at

    fun scheduled(id: UUID, at: Long) {
        pendingId = id
        boundary = at
    }

    fun begin(id: UUID): Boolean {
        if (pendingId != null && pendingId != id) return false
        pendingId = id
        runningId = id
        return true
    }

    fun finish(id: UUID): Boolean {
        if (pendingId != id || runningId != id) return false
        clear()
        return true
    }

    fun clear() {
        pendingId = null
        boundary = null
        runningId = null
    }
}
