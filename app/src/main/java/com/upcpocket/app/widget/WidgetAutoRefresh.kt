package com.upcpocket.app.widget

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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Only placed widgets retain periodic or local redraw work. KEEP preserves existing cadence. */
object WidgetAutoRefresh {
    const val CARD_WORK_NAME = "widget-auto-card"
    const val TIMETABLE_WORK_NAME = "widget-auto-timetable-local"
    internal const val LOCAL_RECOVERY_WORK_NAME = "widget-local-recovery"
    internal const val KEY_AUTOMATIC = "widget_automatic"
    const val TIMETABLE_INTERVAL_MINUTES = 30L
    const val CARD_MIN_INTERVAL_MINUTES = 25L
    const val CARD_MAX_INTERVAL_MINUTES = 35L

    fun hasWidgets(context: Context, kind: WidgetKind): Boolean =
        hasWidgetInstances(context, kind)

    fun reconcile(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val work = WorkManager.getInstance(appContext)
            val installationId = AppGraph.installationIdForWidgetCadence()
            if (hasWidgets(appContext, WidgetKind.CARD) &&
                AppGraph.cardServiceOrNull != null &&
                AppGraph.hasUsableManagedSession() &&
                installationId != null
            ) {
                // PeriodicWork's first run is immediate when connected, so adding a
                // one-shot balance request. A stable device-derived delay spreads first runs.
                val cadence = cardCadence(installationId)
                work.enqueueUniquePeriodicWork(
                    CARD_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<WidgetRefreshWorker>(cadence.intervalMinutes, TimeUnit.MINUTES)
                        .setInitialDelay(cadence.initialDelayMinutes, TimeUnit.MINUTES)
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
                    PeriodicWorkRequestBuilder<TimetableWidgetTickWorker>(TIMETABLE_INTERVAL_MINUTES, TimeUnit.MINUTES)
                        .setInputData(workDataOf(TimetableWidgetTicker.KEY_BOUNDARY to false))
                        .build(),
                )
                TimetableWidgetTicker.schedule(appContext)
            } else {
                work.cancelUniqueWork(TIMETABLE_WORK_NAME)
                TimetableWidgetTicker.cancel(appContext)
            }
            if (hasWidgets(appContext, WidgetKind.TODO) && AppGraph.todoStoreOrNull != null) {
                TodoWidgetTicker.schedule(appContext)
            } else {
                TodoWidgetTicker.cancel(appContext)
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
                WidgetKind.TODO -> TodoWidgetTicker.cancel(context)
                WidgetKind.POWER -> Unit
            }
        }
    }

    internal data class CardCadence(val intervalMinutes: Long, val initialDelayMinutes: Long)

    /** Stable per installation, with periods in 25–35 minutes and first runs spread in-range. */
    internal fun cardCadence(installationId: String): CardCadence {
        val digest = MessageDigest.getInstance("SHA-256").digest(installationId.toByteArray(Charsets.UTF_8))
        val intervalBits = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE
        val delayBits = ByteBuffer.wrap(digest, Long.SIZE_BYTES, Long.SIZE_BYTES).long and Long.MAX_VALUE
        val interval = CARD_MIN_INTERVAL_MINUTES + intervalBits %
            (CARD_MAX_INTERVAL_MINUTES - CARD_MIN_INTERVAL_MINUTES + 1)
        val initialDelay = 1L + delayBits % interval
        return CardCadence(intervalMinutes = interval, initialDelayMinutes = initialDelay)
    }
}
