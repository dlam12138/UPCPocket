package com.upcpocket.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.upcpocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 仅 debug 构建存在的合成数据/自动化探针；release 源集不包含本文件。
 *
 * 只接受显式组件广播，不注册隐式 action；不读取或输出真实用户待办。
 */
class TodoProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = AppGraph.todoStoreOrNull ?: return
        val op = intent.getStringExtra(EXTRA_OP) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                when (op) {
                    OP_SEED -> {
                        val minutes = intent.getIntExtra(EXTRA_MINUTES, 2)
                        val title = intent.getStringExtra(EXTRA_TITLE) ?: "合成待办"
                        store.add(title, System.currentTimeMillis() + minutes * 60_000L)
                    }
                    OP_SEED_SET -> {
                        val now = System.currentTimeMillis()
                        val overdue = store.add("合成待办 · 逾期", now - 30 * 60_000L)
                        store.add("合成待办 · 今天", now + 45 * 60_000L)
                        store.add("合成待办 · 明天", now + 24 * 60 * 60_000L)
                        val noReminder = store.add("合成待办 · 无提醒", null)
                        store.setCompleted(noReminder.id, true)
                        check(overdue.id.isNotEmpty())
                    }
                    OP_RECONCILE -> store.reconcileAll()
                    OP_DELETE_SYNTHETIC -> {
                        store.tasks.first()
                            .filter { it.title.startsWith("合成待办") }
                            .forEach { store.delete(it.id) }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_OP = "op"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_TITLE = "title"
        const val OP_SEED = "seed"
        const val OP_SEED_SET = "seed_set"
        const val OP_RECONCILE = "reconcile"
        const val OP_DELETE_SYNTHETIC = "delete_synthetic"
    }
}
