package com.upcpocket.app.todo.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
data class TodoReminderAccess(
    val notificationsEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
) {
    val hasNotificationIssue: Boolean get() = !notificationsEnabled
    val hasExactAlarmIssue: Boolean get() = notificationsEnabled && !exactAlarmsAllowed
}

/** 每次展示/调度前动态查询，不缓存系统授权。 */
class TodoReminderAccessProvider(private val context: Context) {

    fun current(): TodoReminderAccess {
        val manager = NotificationManagerCompat.from(context)
        val appEnabled = manager.areNotificationsEnabled()
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(TODO_CHANNEL_ID)
        val channelEnabled = channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        val permissionEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val exact = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        return TodoReminderAccess(
            notificationsEnabled = appEnabled && channelEnabled && permissionEnabled,
            exactAlarmsAllowed = exact,
        )
    }
}
