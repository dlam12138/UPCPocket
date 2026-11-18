package com.upcpocket.app.todo

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.designsystem.WidgetRenderSupport
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.feature.todo.R
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.data.TodoWidgetSnapshotResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The host computes this from each widget's actual dp size and the current font scale. */
data class TodoWidgetLayout(val maxVisibleRows: Int) {
    init {
        require(maxVisibleRows in 0..MAX_VISIBLE_ROWS)
    }

    companion object {
        const val MAX_VISIBLE_ROWS = 4

        fun forSize(widthDp: Int, heightDp: Int, fontScale: Float): TodoWidgetLayout {
            val width = widthDp.takeIf { it > 0 } ?: 180
            val height = heightDp.takeIf { it > 0 } ?: 180
            val scale = fontScale.takeIf { it > 0f } ?: 1f
            val widthLimit = when {
                width < 120 -> 1
                width < 165 -> 2
                width < 245 -> 3
                else -> MAX_VISIBLE_ROWS
            }
            val verticalPadding = 20
            val headerHeight = if (scale >= 1.3f) 44 else 40
            // Reserve a single footer line for the bounded-summary overflow count.
            val footerHeight = 16
            val rowHeight = when {
                scale >= 1.5f -> 54
                scale >= 1.2f -> 48
                else -> 42
            }
            val heightLimit = (height - verticalPadding - headerHeight - footerHeight)
                .coerceAtLeast(0) / rowHeight
            return TodoWidgetLayout(minOf(widthLimit, heightLimit, MAX_VISIBLE_ROWS))
        }
    }
}

/** All navigation and mutation PendingIntents belong to the app host. */
data class TodoWidgetActions(
    val openTodo: PendingIntent,
    val createTask: PendingIntent,
    val openTask: (taskId: String) -> PendingIntent,
    /** Must be a bounded mutable PI so the host can read RemoteViews.EXTRA_CHECKED. */
    val changeCompleted: (taskId: String, revision: Int) -> PendingIntent,
)

/** Renders only a bounded local snapshot; null snapshot means the Todo capability is unavailable. */
class TodoWidgetRenderer(private val context: Context) {

    fun views(
        snapshot: TodoWidgetSnapshotResult?,
        layout: TodoWidgetLayout,
        actions: TodoWidgetActions,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        appearance: WidgetAppearance = WidgetAppearance.SOLID,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_todo)
        WidgetRenderSupport.applyAppearance(views, R.id.widget_todo_root, appearance)

        views.setOnClickPendingIntent(R.id.widget_todo_header, actions.openTodo)
        views.setOnClickPendingIntent(R.id.widget_todo_create, actions.createTask)
        views.setContentDescription(
            R.id.widget_todo_create,
            context.getString(R.string.widget_todo_create_description),
        )
        setTextColor(views, R.id.widget_todo_title, DesignSystemR.color.widget_primary, appearance)
        setTextColor(views, R.id.widget_todo_count, DesignSystemR.color.widget_secondary, appearance)
        if (appearance == WidgetAppearance.FROSTED_GLASS) {
            views.setColorStateList(
                R.id.widget_todo_create,
                "setTextColor",
                DesignSystemR.color.widget_frosted_accent,
            )
        }

        ROWS.forEach { row ->
            views.setViewVisibility(row.containerId, View.GONE)
            views.setViewVisibility(row.deadlineId, View.GONE)
        }
        views.setViewVisibility(R.id.widget_todo_status, View.GONE)
        views.setViewVisibility(R.id.widget_todo_more, View.GONE)

        val ready = snapshot as? TodoWidgetSnapshotResult.Ready
        val count = ready?.incompleteCount?.coerceAtLeast(0)
        val incompleteTasks = ready?.tasks.orEmpty().filterNot(TodoTask::completed)
        views.setTextViewText(
            R.id.widget_todo_count,
            if (count == null) {
                context.getString(R.string.widget_todo_count_unknown)
            } else {
                context.getString(R.string.widget_todo_count, count)
            },
        )

        val state = when {
            snapshot == null -> State(R.string.widget_todo_unavailable, DesignSystemR.color.widget_error)
            snapshot == TodoWidgetSnapshotResult.NotRestored ->
                State(R.string.widget_todo_not_restored, DesignSystemR.color.widget_secondary)
            snapshot == TodoWidgetSnapshotResult.ReadFailed ->
                State(R.string.widget_todo_read_failed, DesignSystemR.color.widget_error)
            count == 0 -> State(R.string.widget_todo_empty, DesignSystemR.color.widget_tertiary)
            layout.maxVisibleRows == 0 ->
                State(R.string.widget_todo_expand, DesignSystemR.color.widget_secondary)
            incompleteTasks.isEmpty() ->
                State(R.string.widget_todo_expand, DesignSystemR.color.widget_secondary)
            else -> null
        }
        if (state != null) {
            WidgetRenderSupport.setMeta(
                views = views,
                viewId = R.id.widget_todo_status,
                text = context.getString(state.textRes),
                colorRes = state.colorRes,
                appearance = appearance,
            )
            views.setViewVisibility(R.id.widget_todo_status, View.VISIBLE)
            return views
        }

        val incompleteCount = requireNotNull(count)
        val visibleTasks = incompleteTasks
            .asSequence()
            .take(incompleteCount)
            .take(layout.maxVisibleRows)
            .toList()
        visibleTasks.forEachIndexed { index, task ->
            bindRow(
                views = views,
                row = ROWS[index],
                task = task,
                actions = actions,
                nowMillis = nowMillis,
                zone = zone,
                appearance = appearance,
            )
        }
        val overflow = (incompleteCount - visibleTasks.size).coerceAtLeast(0)
        if (overflow > 0) {
            views.setTextViewText(
                R.id.widget_todo_more,
                context.getString(R.string.widget_todo_more, overflow),
            )
            setTextColor(views, R.id.widget_todo_more, DesignSystemR.color.widget_secondary, appearance)
            views.setViewVisibility(R.id.widget_todo_more, View.VISIBLE)
        }
        return views
    }

    private fun bindRow(
        views: RemoteViews,
        row: RowIds,
        task: TodoTask,
        actions: TodoWidgetActions,
        nowMillis: Long,
        zone: ZoneId,
        appearance: WidgetAppearance,
    ) {
        views.setViewVisibility(row.containerId, View.VISIBLE)
        views.setOnClickPendingIntent(row.containerId, actions.openTask(task.id))
        views.setTextViewText(row.titleId, task.title)
        setTextColor(views, row.titleId, DesignSystemR.color.widget_primary, appearance)
        views.setCompoundButtonChecked(row.checkId, task.completed)
        if (appearance == WidgetAppearance.FROSTED_GLASS) {
            views.setColorStateList(
                row.checkId,
                "setButtonTintList",
                DesignSystemR.color.widget_frosted_accent,
            )
        }
        views.setContentDescription(
            row.checkId,
            context.getString(R.string.widget_todo_check_description, task.title),
        )
        views.setOnCheckedChangeResponse(
            row.checkId,
            RemoteViews.RemoteResponse.fromPendingIntent(
                actions.changeCompleted(task.id, task.revision),
            ),
        )

        val dueAt = task.dueAt
        if (dueAt != null) {
            val overdue = dueAt < nowMillis
            val dueLabel = DEADLINE_FORMAT.format(Instant.ofEpochMilli(dueAt).atZone(zone))
            views.setTextViewText(
                row.deadlineId,
                context.getString(
                    if (overdue) R.string.widget_todo_overdue else R.string.widget_todo_due,
                    dueLabel,
                ),
            )
            setTextColor(
                views,
                row.deadlineId,
                if (overdue) DesignSystemR.color.widget_error else DesignSystemR.color.widget_secondary,
                appearance,
            )
            views.setViewVisibility(row.deadlineId, View.VISIBLE)
        } else {
            views.setTextViewText(row.deadlineId, "")
            setTextColor(views, row.deadlineId, DesignSystemR.color.widget_secondary, appearance)
        }
    }

    private fun setTextColor(
        views: RemoteViews,
        viewId: Int,
        colorRes: Int,
        appearance: WidgetAppearance,
    ) {
        views.setColorStateList(viewId, "setTextColor", appearance.textColorResource(colorRes))
    }

    private data class State(val textRes: Int, val colorRes: Int)

    private data class RowIds(
        val containerId: Int,
        val checkId: Int,
        val titleId: Int,
        val deadlineId: Int,
    )

    private companion object {
        val DEADLINE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)

        val ROWS = listOf(
            RowIds(R.id.widget_todo_row_1, R.id.widget_todo_check_1, R.id.widget_todo_task_1, R.id.widget_todo_due_1),
            RowIds(R.id.widget_todo_row_2, R.id.widget_todo_check_2, R.id.widget_todo_task_2, R.id.widget_todo_due_2),
            RowIds(R.id.widget_todo_row_3, R.id.widget_todo_check_3, R.id.widget_todo_task_3, R.id.widget_todo_due_3),
            RowIds(R.id.widget_todo_row_4, R.id.widget_todo_check_4, R.id.widget_todo_task_4, R.id.widget_todo_due_4),
        )
    }
}
