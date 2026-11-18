package com.upcpocket.app.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.upcpocket.app.todo.data.TodoWidgetSnapshotResult
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Local-only deadline and midnight redraws for placed Todo widgets. */
object TodoWidgetTicker {
    const val UNIQUE_WORK_NAME = "todo-widget-local-redraw"
    internal const val LOCAL_RECOVERY_WORK_NAME = "todo-widget-local-recovery"

    fun schedule(context: Context) {
        enqueue(context.applicationContext, ExistingWorkPolicy.REPLACE)
    }

    fun scheduleAfterWork(context: Context) {
        enqueue(context.applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    fun reschedule(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
        schedule(appContext)
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(LOCAL_RECOVERY_WORK_NAME)
        }
    }

    /** Request a short local restore/redraw without doing Room work in BroadcastReceiver.onReceive. */
    fun requestLocalRecovery(context: Context) {
        val appContext = context.applicationContext
        if (!hasWidgets(appContext)) return
        runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                LOCAL_RECOVERY_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TodoWidgetTickWorker>().build(),
            )
        }
    }

    internal fun hasWidgets(context: Context): Boolean =
        hasWidgetInstances(context, WidgetKind.TODO)

    internal fun nextBoundary(
        snapshot: TodoWidgetSnapshotResult?,
        now: ZonedDateTime,
    ): ZonedDateTime {
        val zone = ZoneId.systemDefault()
        val localNow = now.withZoneSameInstant(zone)
        val midnight = localNow.toLocalDate().plusDays(1).atStartOfDay(zone)
        val nextDeadline = (snapshot as? TodoWidgetSnapshotResult.Ready)
            ?.tasks
            .orEmpty()
            .mapNotNull { it.dueAt }
            .filter { it > localNow.toInstant().toEpochMilli() }
            .minOrNull()
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) }
        return listOfNotNull(midnight, nextDeadline).minOrNull() ?: midnight
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        if (!hasWidgets(context) || com.upcpocket.app.core.AppGraph.todoStoreOrNull == null) {
            cancel(context)
            return
        }
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val boundary = nextBoundary(WidgetUpdates.todoSnapshotForSchedule(), now)
        val delay = Duration.between(now, boundary).toMillis().coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<TodoWidgetTickWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        }
    }
}
