package com.upcpocket.app.todo

import android.content.Intent
import java.util.UUID

/**
 * 通知点击进入待办页的固定白名单入口。
 *
 * 只接受一个本地 UUID；外部伪造的任务 ID 不存在时页面不会打开编辑器。
 */
object TodoLaunchContract {
    const val ACTION_OPEN = "com.upcpocket.app.todo.action.OPEN"
    const val EXTRA_TASK_ID = "com.upcpocket.app.todo.extra.OPEN_TASK_ID"

    fun taskId(intent: Intent?): String? {
        if (intent?.action != ACTION_OPEN) return null
        val raw = intent.getStringExtra(EXTRA_TASK_ID) ?: return null
        return raw.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
    }
}
