package com.upcpocket.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Bounded local restore and redraw; never reads school services or credentials. */
class TodoWidgetTickWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!TodoWidgetTicker.hasWidgets(applicationContext)) return Result.success()

        WidgetUpdates.updateTodoSnapshot(
            context = applicationContext,
            scheduleTicker = false,
            restoreIfNotRestored = true,
        )
        if (!isStopped) TodoWidgetTicker.scheduleAfterWork(applicationContext)
        return Result.success()
    }
}
