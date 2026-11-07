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
import com.upcpocket.app.todo.TodoNotifierApi
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoTask

const val TODO_CHANNEL_ID = "todo_reminders"

internal const val TODO_NOTIFICATION_ID = 1

internal fun notificationTag(taskId: String): String = "todo:$taskId"

/**
 * 到期提醒只在系统通知栏出现一次；标题来自用户待办，不写日志。
 */
class TodoNotifier(
    private val context: Context,
    private val openAppIntent: (String) -> PendingIntent,
) : TodoNotifierApi {

    override fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(TODO_CHANNEL_ID) != null) return
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

    override fun post(task: TodoTask, revision: Int, firedAt: Long): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        // areNotificationsEnabled() 在用户把渠道重要性设为 NONE 时仍可能为 true；
        // 只以系统 notify 无异常判断会把未显示的通知永久标成已发送。
        // 已存在渠道不再 create，避免覆盖用户主动关闭渠道的设置。
        val systemManager = context.getSystemService(NotificationManager::class.java) ?: return false
        var channel = systemManager.getNotificationChannel(TODO_CHANNEL_ID)
        if (channel == null) {
            ensureChannel()
            channel = systemManager.getNotificationChannel(TODO_CHANNEL_ID)
        }
        if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return false
        }
        return runCatching {
            manager.notify(
                notificationTag(task.id),
                TODO_NOTIFICATION_ID,
                build(task, revision, firedAt),
            )
        }.isSuccess
    }

    override fun cancel(taskId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(
                notificationTag(taskId),
                TODO_NOTIFICATION_ID,
            )
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
}
