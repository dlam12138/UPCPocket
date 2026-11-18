package com.upcpocket.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Non-exported receiver for protected system events; no caller data is consumed. */
class WidgetTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!accepts(intent.action)) return
        // WorkManager delay uses elapsed time. Recompute local deadline/midnight
        // boundaries after wall-clock or time-zone changes.
        TimetableWidgetTicker.reschedule(context)
        WidgetAutoRefresh.reconcile(context)
        WidgetAutoRefresh.requestLocalRecovery(context)
        TodoWidgetTicker.requestLocalRecovery(context)
    }

    companion object {
        internal fun accepts(action: String?): Boolean = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED || action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_BOOT_COMPLETED
    }
}
