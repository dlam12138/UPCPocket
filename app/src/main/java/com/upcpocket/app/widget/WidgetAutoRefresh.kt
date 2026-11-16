package com.upcpocket.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.upcpocket.app.core.AppGraph
import java.util.concurrent.TimeUnit

/** Only placed widgets retain work. KEEP preserves the cadence across redraws. */
object WidgetAutoRefresh {
    const val CARD_WORK_NAME = "widget-auto-card"
    const val TIMETABLE_WORK_NAME = "widget-auto-timetable-local"
    internal const val LOCAL_RECOVERY_WORK_NAME = "widget-local-recovery"
    internal const val KEY_AUTOMATIC = "widget_automatic"
    const val INTERVAL_MINUTES = 30L

    fun hasWidgets(context: Context, kind: WidgetKind): Boolean =
        AppWidgetManager.getInstance(context.applicationContext)
            .getAppWidgetIds(ComponentName(context, kind.providerClass)).isNotEmpty()

    fun reconcile(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val work = WorkManager.getInstance(appContext)
            if (hasWidgets(appContext, WidgetKind.CARD) && AppGraph.cardServiceOrNull != null) {
                // PeriodicWork's first run is immediate when connected, so adding a
                // widget does not also enqueue a duplicate one-shot balance request.
                work.enqueueUniquePeriodicWork(
                    CARD_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<WidgetRefreshWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInputData(workDataOf(WidgetWork.KEY_KIND to WidgetKind.CARD.key, KEY_AUTOMATIC to true))
                        .build(),
                )
            } else {
                work.cancelUniqueWork(CARD_WORK_NAME)
            }
            if (hasWidgets(appContext, WidgetKind.TIMETABLE) && AppGraph.timetableServiceOrNull != null) {
                work.enqueueUniquePeriodicWork(
                    TIMETABLE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<TimetableWidgetTickWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                        .setInputData(workDataOf(TimetableWidgetTicker.KEY_BOUNDARY to false))
                        .build(),
                )
                TimetableWidgetTicker.schedule(appContext)
            } else {
                work.cancelUniqueWork(TIMETABLE_WORK_NAME)
                TimetableWidgetTicker.cancel(appContext)
            }
        }
    }

    /** System clock/package events only queue local work; they do not read a balance. */
    fun requestLocalRecovery(context: Context) {
        if (!hasWidgets(context, WidgetKind.TIMETABLE)) return
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                LOCAL_RECOVERY_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TimetableWidgetTickWorker>()
                    .setInputData(workDataOf(TimetableWidgetTicker.KEY_BOUNDARY to false))
                    .build(),
            )
        }
    }

    fun cancel(context: Context, kind: WidgetKind) {
        runCatching {
            val work = WorkManager.getInstance(context.applicationContext)
            when (kind) {
                WidgetKind.CARD -> work.cancelUniqueWork(CARD_WORK_NAME)
                WidgetKind.TIMETABLE -> {
                    work.cancelUniqueWork(TIMETABLE_WORK_NAME)
                    work.cancelUniqueWork(LOCAL_RECOVERY_WORK_NAME)
                    TimetableWidgetTicker.cancel(context)
                }
                WidgetKind.POWER -> Unit
            }
        }
    }
}
