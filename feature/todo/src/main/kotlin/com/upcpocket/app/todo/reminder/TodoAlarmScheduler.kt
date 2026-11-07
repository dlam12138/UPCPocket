package com.upcpocket.app.todo.reminder

import android.app.AlarmManager
import android.content.Context

/** AlarmManager 只负责一次性提醒；任务/代数仍以 Room 为准。 */
class TodoAlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    fun schedule(taskId: String, revision: Int, atMillis: Long, exact: Boolean) {
        val manager = alarmManager ?: return
        val pending = TodoIntents.alarm(context, taskId, revision)
        // 同一任务的 PendingIntent 身份固定；新 revision 通过 FLAG_UPDATE_CURRENT 覆盖旧 extras。
        manager.cancel(pending)
        val canExact = manager.canScheduleExactAlarms()
        try {
            if (exact && canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        } catch (_: SecurityException) {
            // 授权在检查后被撤回：退回不精确调度，不伪装准点。
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    fun cancel(taskId: String) {
        TodoIntents.cancelAlarm(context, taskId)
    }
}
