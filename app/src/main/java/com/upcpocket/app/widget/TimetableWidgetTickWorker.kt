package com.upcpocket.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.upcpocket.app.core.AppGraph
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local-only timetable widget redraw. No account restore, no network, no CAS.
 */
class TimetableWidgetTickWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 只从本机 DataStore 恢复账号绑定，不联网、不打开 Activity。恢复完成后才能
        // 区分“无账号/清理中”和真实缓存；超时则仍重绘当前可见状态并排下一次边界。
        withTimeoutOrNull(ACCOUNT_RESTORE_BUDGET_MS) {
            AppGraph.account.restore()
        }
        WidgetUpdates.updateKindNow(applicationContext, WidgetKind.TIMETABLE)
        return Result.success()
    }

    private companion object {
        const val ACCOUNT_RESTORE_BUDGET_MS = 5_000L
    }
}
