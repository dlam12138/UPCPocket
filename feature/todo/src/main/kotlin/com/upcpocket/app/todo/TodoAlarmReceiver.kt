package com.upcpocket.app.todo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.upcpocket.app.todo.reminder.TodoIntents
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Alarm 到点只做一次有界数据库/通知操作；插件未激活时直接返回。 */
class TodoAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TodoIntents.ACTION_ALARM) return
        val taskId = intent.getStringExtra(TodoIntents.EXTRA_TASK_ID) ?: return
        if (!isUuid(taskId)) return
        val revision = intent.getIntExtra(TodoIntents.EXTRA_REVISION, -1)
        if (revision < 1) return
        val service = TodoRuntime.serviceOrNull() ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                service.onAlarm(taskId, revision)
            } finally {
                pending.finish()
            }
        }
    }

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value) }.isSuccess
}
