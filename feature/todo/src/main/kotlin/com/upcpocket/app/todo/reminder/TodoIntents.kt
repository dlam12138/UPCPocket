package com.upcpocket.app.todo.reminder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.upcpocket.app.todo.TodoActionReceiver
import com.upcpocket.app.todo.TodoAlarmReceiver

/** 所有 PendingIntent 都绑定本应用显式组件，不接受外部隐式路由。 */
internal object TodoIntents {

    const val ACTION_ALARM = "com.upcpocket.app.todo.action.ALARM"
    const val ACTION_COMPLETE = "com.upcpocket.app.todo.action.COMPLETE"
    const val ACTION_SNOOZE = "com.upcpocket.app.todo.action.SNOOZE"
    const val EXTRA_TASK_ID = "com.upcpocket.app.todo.extra.TASK_ID"
    const val EXTRA_REVISION = "com.upcpocket.app.todo.extra.REVISION"

    fun alarmRequestCode(taskId: String): Int = taskId.hashCode()

    fun completeRequestCode(taskId: String): Int = taskId.hashCode() xor 0x434f4d50

    fun snoozeRequestCode(taskId: String): Int = taskId.hashCode() xor 0x534e4f5a

    fun alarm(context: Context, taskId: String, revision: Int): PendingIntent {
        val intent = Intent(context, TodoAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_REVISION, revision)
        }
        return PendingIntent.getBroadcast(
            context,
            alarmRequestCode(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun action(
        context: Context,
        action: String,
        taskId: String,
        revision: Int,
    ): PendingIntent {
        val requestCode = when (action) {
            ACTION_COMPLETE -> completeRequestCode(taskId)
            ACTION_SNOOZE -> snoozeRequestCode(taskId)
            else -> error("不支持的通知动作")
        }
        val intent = Intent(context, TodoActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_REVISION, revision)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancelAlarm(context: Context, taskId: String) {
        val intent = Intent(context, TodoAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
        }
        val pending = PendingIntent.getBroadcast(
            context,
            alarmRequestCode(taskId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(android.app.AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }
}
