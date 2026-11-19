package com.upcpocket.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.platform.diagnostics.DiagnosticAction
import com.upcpocket.app.platform.diagnostics.DiagnosticModule
import com.upcpocket.app.platform.diagnostics.DiagnosticReason
import com.upcpocket.app.platform.diagnostics.DiagnosticResult
import com.upcpocket.app.platform.diagnostics.Diagnostics
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local-only timetable redraw, including bounded local account restore. No network or CAS.
 */
class TimetableWidgetTickWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Older persisted one-shot requests have no flag and remain boundaries.
        val boundary = inputData.getBoolean(TimetableWidgetTicker.KEY_BOUNDARY, true)
        if (boundary && !TimetableWidgetTicker.begin(applicationContext, id)) return Result.success()
        var completed = false
        try {
            return redraw().also { completed = true }
        } finally {
            // A failed/cancelled prerequisite must not leave a doomed successor
            // recorded as pending; the independent local period can recover it.
            if (boundary) TimetableWidgetTicker.finish(applicationContext, id, isStopped || !completed)
        }
    }

    private suspend fun redraw(): Result {
        val started = System.currentTimeMillis()
        Diagnostics.record(
            module = DiagnosticModule.WIDGET,
            action = DiagnosticAction.WORKER_START,
            result = DiagnosticResult.PENDING,
        )
        // 只从本机 DataStore 恢复账号绑定，不联网、不打开 Activity。恢复完成后才能
        // 区分“无账号/清理中”和真实缓存；超时则仍重绘当前可见状态并排下一次边界。
        val restoreStarted = System.currentTimeMillis()
        val restored = withTimeoutOrNull(ACCOUNT_RESTORE_BUDGET_MS) {
            AppGraph.account.restore()
        }
        Diagnostics.record(
            module = DiagnosticModule.WIDGET,
            action = DiagnosticAction.ACCOUNT_RESTORE_COMPLETE,
            result = if (restored == null) DiagnosticResult.TIMEOUT else DiagnosticResult.OK,
            reason = if (restored == null) DiagnosticReason.ACCOUNT else null,
            durationMs = (System.currentTimeMillis() - restoreStarted).coerceAtLeast(0),
        )
        val deferTodayRefreshUntil = inputData.getLong(TimetableWidgetTicker.KEY_DEFER_TODAY_REFRESH_UNTIL, 0L)
        if (System.currentTimeMillis() >= deferTodayRefreshUntil) {
            AppGraph.timetableServiceOrNull?.refreshToday()
        }
        WidgetUpdates.updateKindNow(
            applicationContext,
            WidgetKind.TIMETABLE,
            scheduleTicker = !inputData.getBoolean(TimetableWidgetTicker.KEY_BOUNDARY, true),
        )
        Diagnostics.record(
            module = DiagnosticModule.WIDGET,
            action = DiagnosticAction.WORKER_COMPLETE,
            result = DiagnosticResult.OK,
            durationMs = (System.currentTimeMillis() - started).coerceAtLeast(0),
        )
        return Result.success()
    }

    private companion object {
        const val ACCOUNT_RESTORE_BUDGET_MS = 5_000L
    }
}
