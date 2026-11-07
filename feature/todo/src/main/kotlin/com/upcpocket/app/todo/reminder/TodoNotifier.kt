package com.upcpocket.app.todo.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.upcpocket.app.feature.todo.R
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoTask

const val TODO_CHANNEL_ID = "todo_reminders"

/**
 * 到期提醒只在系统通知栏出现一次；标题来自用户待办，不写日志。
 */
class TodoNotifier(
    private val context: Context,
    private val openAppIntent: (String) -> PendingIntent,
) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                TODO_CHANNEL_ID,
                "待办提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "一次性待办到期提醒"
                enableVibration(true)
                setShowBadge(true)
            },
        )
    }

    fun post(task: TodoTask, revision: Int, firedAt: Long): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            NotificationManagerCompat.from(context).notify(
                notificationId(task.id),
                build(task, revision, firedAt),
            )
        }.isSuccess
    }

    fun cancel(taskId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(taskId))
        }
    }

    internal fun build(task: TodoTask, revision: Int, firedAt: Long): android.app.Notification {
        val title = task.title.take(120)
        val reminderTime = task.reminderAt ?: firedAt
        val builder = NotificationCompat.Builder(context, TODO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(title)
            .setContentText("提醒时间 ${TodoFormat.shortTime(reminderTime, firedAt)}")
            .setContentIntent(openAppIntent(task.id))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(firedAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .addAction(
                0,
                "完成",
                TodoIntents.action(context, TodoIntents.ACTION_COMPLETE, task.id, revision),
            )
            .addAction(
                0,
                "稍后 10 分钟",
                TodoIntents.action(context, TodoIntents.ACTION_SNOOZE, task.id, revision),
            )
        return builder.build()
    }

    private fun notificationId(taskId: String): Int = taskId.hashCode()
}
