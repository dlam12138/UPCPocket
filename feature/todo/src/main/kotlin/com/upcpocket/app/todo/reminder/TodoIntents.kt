package com.upcpocket.app.todo.reminder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.upcpocket.app.todo.TodoActionReceiver
import com.upcpocket.app.todo.TodoAlarmReceiver

/**
 * 所有 PendingIntent 都绑定本应用显式组件，不接受外部隐式路由。
 *
 * - Alarm 身份 = 完整 taskId（同一任务 stable，revision 只放 extras，便于按 taskId 取消）；
 * - 通知动作身份 = 完整 taskId + revision + 动作（旧 token 不会被新 revision 的
 *   FLAG_UPDATE_CURRENT 重绑定）；
 * - 不使用 String.hashCode 作为任务唯一身份，避免合法 UUID 哈希碰撞串任务。
 */
internal object TodoIntents {

    const val ACTION_ALARM = "com.upcpocket.app.todo.action.ALARM"
    const val ACTION_COMPLETE = "com.upcpocket.app.todo.action.COMPLETE"
    const val ACTION_SNOOZE = "com.upcpocket.app.todo.action.SNOOZE"
    const val EXTRA_TASK_ID = "com.upcpocket.app.todo.extra.TASK_ID"
    const val EXTRA_REVISION = "com.upcpocket.app.todo.extra.REVISION"

    private const val SCHEME = "upcpocket"
    private const val HOST = "todo"
    private const val REQUEST_CODE = 0

    internal fun alarmData(taskId: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(HOST).appendPath("alarm").appendPath(taskId).build()

    internal fun actionData(taskId: String, revision: Int, action: String): Uri {
        val suffix = when (action) {
            ACTION_COMPLETE -> "complete"
            ACTION_SNOOZE -> "snooze"
            else -> error("不支持的通知动作")
        }
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendPath("action")
            .appendPath(taskId)
            .appendPath(revision.toString())
            .appendPath(suffix)
            .build()
    }

    fun alarm(context: Context, taskId: String, revision: Int): PendingIntent {
        val intent = Intent(context, TodoAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            data = alarmData(taskId)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_REVISION, revision)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
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
        val intent = Intent(context, TodoActionReceiver::class.java).apply {
            this.action = action
            data = actionData(taskId, revision, action)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_REVISION, revision)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancelAlarm(context: Context, taskId: String) {
        val intent = Intent(context, TodoAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            data = alarmData(taskId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(android.app.AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }
}
