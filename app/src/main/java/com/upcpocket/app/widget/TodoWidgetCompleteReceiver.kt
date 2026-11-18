package com.upcpocket.app.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.upcpocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Private, bounded write entry for the mutable checkbox response PendingIntent. */
class TodoWidgetCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val request = parseRequest(appContext, intent)
        if (request == null || !WidgetIntents.isOwnedBy(appContext, WidgetKind.TODO, request.widgetId)) {
            WidgetUpdates.requestTodo(appContext)
            return
        }

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val store = AppGraph.todoStoreOrNull
                if (store != null && WidgetIntents.isOwnedBy(appContext, WidgetKind.TODO, request.widgetId)) {
                    withTimeout(COMPLETION_BUDGET_MS) {
                        store.setCompletedIfRevision(
                            id = request.taskId,
                            revision = request.revision,
                            completed = true,
                        )
                    }
                }
            } catch (_: Exception) {
                // The row may have been removed or changed since this RemoteViews snapshot.
                // A fresh read below is the user-visible result; do not show optimistic success.
            } finally {
                WidgetUpdates.requestTodo(appContext)
                pendingResult.finish()
            }
        }
    }

    internal data class CompletionRequest(
        val widgetId: Int,
        val taskId: String,
        val revision: Int,
    )

    internal fun parseRequest(context: Context, intent: Intent): CompletionRequest? {
        if (!WidgetIntents.isTodoCompletionAction(intent.action)) return null
        if (intent.`package` != context.packageName) return null
        if (intent.component != ComponentName(context, TodoWidgetCompleteReceiver::class.java)) return null
        if (!intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false)) return null

        val uri = intent.data ?: return null
        if (uri.scheme != URI_SCHEME || uri.authority != URI_AUTHORITY ||
            uri.query != null || uri.fragment != null
        ) return null
        val segments = uri.pathSegments
        if (segments.size != 5 || segments[0] != "complete" || segments[4] != "true") return null
        val widgetId = segments[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (widgetId.toString() != segments[1]) return null
        val taskId = segments[2].takeIf(WidgetIntents::isFullUuid) ?: return null
        val revision = segments[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (revision.toString() != segments[3]) return null
        return CompletionRequest(widgetId, taskId, revision)
    }

    private companion object {
        const val COMPLETION_BUDGET_MS = 5_000L
        const val URI_SCHEME = "upcpocket"
        const val URI_AUTHORITY = "todo-widget"
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
