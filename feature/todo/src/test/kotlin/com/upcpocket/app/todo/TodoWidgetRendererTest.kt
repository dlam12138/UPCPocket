package com.upcpocket.app.todo

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.upcpocket.app.designsystem.WidgetAppearance
import com.upcpocket.app.designsystem.R as DesignSystemR
import com.upcpocket.app.feature.todo.R
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.data.TodoWidgetSnapshotResult
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TodoWidgetRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_800_000_000_000L
    private val renderer = TodoWidgetRenderer(context)
    private var nextRequestCode = 1

    @Test
    fun `layout reduces visible rows for narrow short and large font widgets`() {
        assertEquals(1, TodoWidgetLayout.forSize(80, 234, 1f).maxVisibleRows)
        assertEquals(3, TodoWidgetLayout.forSize(172, 234, 1f).maxVisibleRows)
        assertEquals(1, TodoWidgetLayout.forSize(172, 145, 1f).maxVisibleRows)
        assertEquals(1, TodoWidgetLayout.forSize(172, 145, 1.5f).maxVisibleRows)
        assertEquals(4, TodoWidgetLayout.forSize(360, 280, 1f).maxVisibleRows)
    }

    @Test
    fun `unavailable restore failure and valid empty show distinct states`() {
        val states = listOf(
            null to R.string.widget_todo_unavailable,
            TodoWidgetSnapshotResult.NotRestored to R.string.widget_todo_not_restored,
            TodoWidgetSnapshotResult.ReadFailed to R.string.widget_todo_read_failed,
            TodoWidgetSnapshotResult.Ready(emptyList(), 0) to R.string.widget_todo_empty,
        )

        states.forEach { (snapshot, expectedState) ->
            val root = applied(
                render(snapshot),
                widthDp = 172,
                heightDp = 180,
            )
            assertEquals(
                context.getString(expectedState),
                root.findViewById<TextView>(R.id.widget_todo_status).text.toString(),
            )
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_todo_status).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_todo_row_1).visibility)
        }
    }

    @Test
    fun `appearance is applied for each snapshot state`() {
        val snapshots = listOf(
            null,
            TodoWidgetSnapshotResult.NotRestored,
            TodoWidgetSnapshotResult.ReadFailed,
            TodoWidgetSnapshotResult.Ready(emptyList(), 0),
            TodoWidgetSnapshotResult.Ready(listOf(task("ready", "有待办")), 1),
        )
        val expectedColor = context.getColor(DesignSystemR.color.widget_surface_translucent)

        snapshots.forEach { snapshot ->
            val root = applied(
                renderer.views(
                    snapshot = snapshot,
                    layout = TodoWidgetLayout(maxVisibleRows = 2),
                    actions = actions().actions,
                    nowMillis = now,
                    zone = zone,
                    appearance = WidgetAppearance.TRANSLUCENT,
                ),
                widthDp = 172,
                heightDp = 234,
            )

            val background = root.background as GradientDrawable
            assertEquals(expectedColor, background.color?.defaultColor)
        }
    }

    @Test
    fun `rows use dueAt for overdue text and colors with independent host actions`() {
        val overdue = task(
            id = "overdue",
            title = "交实验报告",
            revision = 8,
            reminderAt = now + 86_400_000,
            dueAt = now - 60_000,
        )
        val future = task(
            id = "future",
            title = "准备展示",
            revision = 11,
            reminderAt = now - 60_000,
            dueAt = now + 86_400_000,
        )
        val reminderWithoutDeadline = task(
            id = "reminder-only",
            title = "只有提醒",
            reminderAt = now - 60_000,
        )
        val hostActions = actions()
        val snapshot = TodoWidgetSnapshotResult.Ready(
            tasks = listOf(
                overdue,
                future,
                reminderWithoutDeadline,
                task("fourth", "第四项"),
                task("fifth", "第五项"),
            ),
            incompleteCount = 8,
        )
        val views = renderer.views(
            snapshot = snapshot,
            layout = TodoWidgetLayout(maxVisibleRows = 4),
            actions = hostActions.actions,
            nowMillis = now,
            zone = zone,
        )
        val root = applied(views, widthDp = 360, heightDp = 280)

        assertEquals("8 项未完成", root.findViewById<TextView>(R.id.widget_todo_count).text.toString())
        assertEquals("交实验报告", root.findViewById<TextView>(R.id.widget_todo_task_1).text.toString())
        assertEquals("已逾期 · 截止 1月15日 15:59", root.findViewById<TextView>(R.id.widget_todo_due_1).text.toString())
        assertEquals(
            context.getColor(DesignSystemR.color.widget_error),
            root.findViewById<TextView>(R.id.widget_todo_due_1).currentTextColor,
        )
        assertEquals("截止 1月16日 16:00", root.findViewById<TextView>(R.id.widget_todo_due_2).text.toString())
        assertEquals(
            context.getColor(DesignSystemR.color.widget_secondary),
            root.findViewById<TextView>(R.id.widget_todo_due_2).currentTextColor,
        )
        assertFalse(root.findViewById<CheckBox>(R.id.widget_todo_check_1).isChecked)
        assertFalse(root.findViewById<CheckBox>(R.id.widget_todo_check_3).isChecked)
        assertEquals(View.GONE, root.findViewById<TextView>(R.id.widget_todo_due_3).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_todo_row_4).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_todo_more).visibility)
        assertEquals("还有 4 项", root.findViewById<TextView>(R.id.widget_todo_more).text.toString())
        assertEquals(listOf("overdue", "future", "reminder-only", "fourth"), hostActions.openTaskIds)
        assertEquals(
            listOf("overdue" to 8, "future" to 11, "reminder-only" to 1, "fourth" to 1),
            hostActions.completionTargets,
        )
    }

    @Test
    fun `tight layout displays only its requested number of tasks`() {
        val tasks = (1..4).map { task("task-$it", "待办 $it") }
        val root = applied(
            render(TodoWidgetSnapshotResult.Ready(tasks, incompleteCount = 4), maxRows = 1),
            widthDp = 172,
            heightDp = 145,
        )

        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_todo_row_1).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_todo_row_2).visibility)
        assertEquals("还有 3 项", root.findViewById<TextView>(R.id.widget_todo_more).text.toString())
    }

    private fun render(
        snapshot: TodoWidgetSnapshotResult?,
        maxRows: Int = 2,
    ): RemoteViews = renderer.views(
        snapshot = snapshot,
        layout = TodoWidgetLayout(maxRows),
        actions = actions().actions,
        nowMillis = now,
        zone = zone,
    )

    private fun applied(views: RemoteViews, widthDp: Int, heightDp: Int): FrameLayout {
        val root = views.apply(context, FrameLayout(context)) as FrameLayout
        val width = (widthDp * context.resources.displayMetrics.density).toInt()
        val height = (heightDp * context.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        return root
    }

    private fun task(
        id: String,
        title: String,
        revision: Int = 1,
        reminderAt: Long? = null,
        dueAt: Long? = null,
    ) = TodoTask(
        id = id,
        title = title,
        completed = false,
        createdAt = now - 1_000,
        updatedAt = now,
        completedAt = null,
        reminderAt = reminderAt,
        reminderFiredAt = null,
        revision = revision,
        dueAt = dueAt,
    )

    private fun actions(): RecordedActions {
        val opens = mutableListOf<String>()
        val completions = mutableListOf<Pair<String, Int>>()
        val actions = TodoWidgetActions(
            openTodo = pendingIntent("open-todo"),
            createTask = pendingIntent("create-todo"),
            openTask = { taskId ->
                opens += taskId
                pendingIntent("open-$taskId")
            },
            changeCompleted = { taskId, revision ->
                completions += taskId to revision
                pendingIntent("complete-$taskId-$revision", mutable = true)
            },
        )
        return RecordedActions(actions, opens, completions)
    }

    private fun pendingIntent(action: String, mutable: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context,
        nextRequestCode++,
        Intent(context, Activity::class.java).setAction(action),
        (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or
            PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private data class RecordedActions(
        val actions: TodoWidgetActions,
        val openTaskIds: List<String>,
        val completionTargets: List<Pair<String, Int>>,
    )
}
