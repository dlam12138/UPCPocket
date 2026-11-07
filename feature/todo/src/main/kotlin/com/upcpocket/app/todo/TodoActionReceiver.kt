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

/**
 * 通知“完成/稍后”动作。
 *
 * 所有动作带 taskId + revision；revision 不匹配时数据库条件更新返回 0，动作被忽略。
 * 旧通知仍会被取消，避免同一任务残留两个通知。
 */
class TodoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(TodoIntents.EXTRA_TASK_ID) ?: return
        if (!isUuid(taskId)) return
        val revision = intent.getIntExtra(TodoIntents.EXTRA_REVISION, -1)
        if (revision < 1) return
        val service = TodoRuntime.serviceOrNull() ?: return
        val action = intent.action ?: return
        if (action != TodoIntents.ACTION_COMPLETE && action != TodoIntents.ACTION_SNOOZE) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                when (action) {
                    TodoIntents.ACTION_COMPLETE -> service.onActionComplete(taskId, revision)
                    TodoIntents.ACTION_SNOOZE -> service.onActionSnooze(taskId, revision)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value) }.isSuccess
}
