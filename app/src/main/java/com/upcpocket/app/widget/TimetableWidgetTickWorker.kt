package com.upcpocket.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Local-only timetable widget redraw. No account restore, no network, no CAS.
 */
class TimetableWidgetTickWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        WidgetUpdates.updateKindNow(applicationContext, WidgetKind.TIMETABLE)
        return Result.success()
    }
}
