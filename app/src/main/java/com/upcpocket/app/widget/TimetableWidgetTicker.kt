package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
import com.upcpocket.app.timetable.minuteRange
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Local-only redraw scheduler for the timetable widget.
 *
 * It never reads the network, credentials, or a course service. It schedules one
 * one-shot WorkManager job for the next relevant local wall-clock boundary
 * (next class start/end, the next whole minute while today's class is active, or
 * local midnight); the worker only re-renders the cached snapshot. WorkManager is
 * persisted across ordinary process death, but Android
 * may defer background work because of Doze/app-standby/battery policy. This is
 * therefore a best-effort local redraw, not a minute-accurate alarm.
 */
object TimetableWidgetTicker {

    const val UNIQUE_WORK_NAME = "timetable-widget-local-redraw"
    internal const val KEY_BOUNDARY = "timetable_local_boundary"
    internal const val KEY_DEFER_TODAY_REFRESH_UNTIL = "timetable_defer_today_refresh_until"
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

    /**
     * 只有原今日课表两版有课时/分钟/倒计时语义；今明版和本周版都只随上海午夜
     * 换日，不应触发今日快照读取或 refreshToday。显式白名单避免新增课表变体后
     * 被“非周版即今日版”的旧过滤误判。
     */
    internal fun hasTodayWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context.applicationContext)
        return WidgetProviderVariant.forKind(WidgetKind.TIMETABLE)
            .filter { it.isTimetableTodayVariant }
            .any { variant ->
                manager.getAppWidgetIds(
                    ComponentName(context.applicationContext, variant.providerClass),
                ).isNotEmpty()
            }
    }

    private fun enqueueNext(context: Context, policy: ExistingWorkPolicy) {
        if (!hasWidgets(context) || AppGraph.timetableServiceOrNull == null) {
            cancel(context)
            return
        }
        val hasToday = hasTodayWidgets(context)
        val snapshot = if (hasToday) {
            WidgetUpdates.timetableSnapshotForSchedule(context)
        } else {
            null
        }
        val now = ZonedDateTime.now(ZONE)
        val redraw = planRedraw(snapshot, now, hasTodayInstances = hasToday)
        val at = redraw.at.toInstant().toEpochMilli()
        if (!plan.shouldSchedule(at)) return
        val request = OneTimeWorkRequestBuilder<TimetableWidgetTickWorker>()
            .setInitialDelay(
                Duration.between(now, redraw.at).toMillis().coerceAtLeast(1_000L),
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            .setInputData(
                androidx.work.workDataOf(
                    KEY_BOUNDARY to true,
                    KEY_DEFER_TODAY_REFRESH_UNTIL to redraw.deferTodayRefreshUntilEpochMillis,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        plan.scheduled(request.id, at)
    }

    /**
     * Pure boundary calculation, exposed for unit tests. Returns the next local
     * midnight, the next class start/end strictly after [now], or the next whole
     * minute while a valid course is active today and that minute is strictly
     * earlier than the ordinary boundary (countdown-only redraw).
     */
    fun nextBoundary(
        snapshot: TimetableWidgetSnapshot?,
        now: ZonedDateTime,
    ): ZonedDateTime = planRedraw(snapshot, now).at

    internal fun planRedraw(
        snapshot: TimetableWidgetSnapshot?,
        now: ZonedDateTime,
        hasTodayInstances: Boolean = true,
    ): TimetableRedrawPlan {
        val localNow = now.withZoneSameInstant(ZONE)
        val today = localNow.toLocalDate()
        val dayStart = today.atStartOfDay(ZONE)
        val midnight = dayStart.plusDays(1)
        if (!hasTodayInstances ||
            snapshot == null ||
            snapshot.status != TimetableWidgetStatus.OK ||
            snapshot.date != today
        ) {
            return TimetableRedrawPlan(midnight)
        }
        val nowMinute = localNow.hour * 60 + localNow.minute
        var ordinary: ZonedDateTime? = null
        var active = false
        for (course in snapshot.courses) {
            val range = course.minuteRange ?: continue
            if (range.contains(nowMinute)) active = true
            if (range.startMinute > nowMinute) {
                val at = dayStart.plusMinutes(range.startMinute.toLong())
                if (ordinary == null || at.isBefore(ordinary)) ordinary = at
            }
            if (range.endMinute > nowMinute) {
                val at = dayStart.plusMinutes(range.endMinute.toLong())
                if (ordinary == null || at.isBefore(ordinary)) ordinary = at
            }
        }
        val boundary = ordinary ?: midnight
        val nextMinute = localNow.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        return if (active && nextMinute.isBefore(boundary)) {
            TimetableRedrawPlan(nextMinute, boundary.toInstant().toEpochMilli())
        } else {
            TimetableRedrawPlan(boundary)
        }
    }
}

internal data class TimetableRedrawPlan(
    val at: ZonedDateTime,
    val deferTodayRefreshUntilEpochMillis: Long = 0L,
)

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
