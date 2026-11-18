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
    const val ACTION_CREATE = "com.upcpocket.app.todo.action.CREATE"
    const val EXTRA_TASK_ID = "com.upcpocket.app.todo.extra.OPEN_TASK_ID"
    const val EXTRA_CREATE_REQUEST_ID = "com.upcpocket.app.todo.extra.CREATE_REQUEST_ID"

    private val UUID_PATTERN = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )

    fun taskId(intent: Intent?): String? {
        if (intent?.action != ACTION_OPEN) return null
        val raw = intent.getStringExtra(EXTRA_TASK_ID) ?: return null
        return canonicalUuid(raw)
    }

    fun createRequestId(intent: Intent?): String? {
        if (intent?.action != ACTION_CREATE) return null
        val raw = intent.getStringExtra(EXTRA_CREATE_REQUEST_ID) ?: return null
        return canonicalUuid(raw)
    }

    fun createRequestIntent(requestId: String): Intent {
        val canonical = requireNotNull(canonicalUuid(requestId))
        return Intent(ACTION_CREATE).putExtra(EXTRA_CREATE_REQUEST_ID, canonical)
    }

    internal fun canonicalUuid(value: String): String? {
        if (!UUID_PATTERN.matches(value)) return null
        return runCatching { UUID.fromString(value).toString() }.getOrNull()
    }
}
