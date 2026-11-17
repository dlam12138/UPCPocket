package com.upcpocket.app.ui.todo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.plugin.ui.LocalDockClearance
import com.upcpocket.app.plugin.ui.LocalPluginPageActive
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoOrdering
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.todo.reminder.TodoReminderAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(store: TodoStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = LocalPluginPageActive.current
    val dockClearance = LocalDockClearance.current
    val snackbar = remember { SnackbarHostState() }
    val tasks by store.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var access by remember { mutableStateOf(store.reminderAccess()) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var editorTask by remember { mutableStateOf<TodoTask?>(null) }
    var adding by remember { mutableStateOf(false) }
    var completionOverrides by remember {
        mutableStateOf(emptyMap<String, TodoCompletionOverride>())
    }
    val pendingOpenTask by store.pendingOpenTask.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(tasks, completionOverrides) {
        val reconciled = reconcileTodoCompletionOverrides(tasks, completionOverrides)
        if (reconciled != completionOverrides) completionOverrides = reconciled
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val previous = access
        access = store.reminderAccess()
        if ((!previous.notificationsEnabled && access.notificationsEnabled) ||
            (!previous.exactAlarmsAllowed && access.exactAlarmsAllowed)
        ) {
            scope.launch { store.reconcileAll() }
        }
    }

    val incomplete = remember(tasks, now) { TodoOrdering.incomplete(tasks, now) }
    val completed = remember(tasks) { TodoOrdering.completed(tasks) }
    val hasReminders = tasks.any { !it.completed && it.reminderAt != null }

    fun toggleCompletion(task: TodoTask) {
        if (task.id in completionOverrides) return
        val desiredCompleted = !task.completed
        completionOverrides = completionOverrides + (
            task.id to TodoCompletionOverride(
                revision = task.revision + 1,
                completed = desiredCompleted,
            )
        )
        scope.launch {
            val updated = try {
                store.setCompletedIfRevision(
                    task.id,
                    task.revision,
                    completed = desiredCompleted,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                completionOverrides = completionOverrides - task.id
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (updated == null) {
                completionOverrides = completionOverrides - task.id
                return@launch
            }
            completionOverrides = completionOverrides + (
                task.id to TodoCompletionOverride(
                    revision = updated.revision,
                    completed = desiredCompleted,
                )
            )
            if (!desiredCompleted) return@launch

            val result = snackbar.showSnackbar(
                message = "已完成",
                actionLabel = "撤销",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                completionOverrides = completionOverrides + (
                    updated.id to TodoCompletionOverride(
                        revision = updated.revision + 1,
                        completed = false,
                    )
                )
                val undone = try {
                    store.setCompletedIfRevision(
                        updated.id,
                        updated.revision,
                        completed = false,
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    completionOverrides = completionOverrides - updated.id
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (undone == null) {
                    completionOverrides = completionOverrides - updated.id
                    snackbar.showSnackbar(
                        message = "待办已变化，无法撤销",
                        duration = SnackbarDuration.Short,
                    )
                } else {
                    completionOverrides = completionOverrides + (
                        updated.id to TodoCompletionOverride(
                            revision = undone.revision,
                            completed = false,
                        )
                    )
                }
            }
        }
    }

    // 通知点击后只消费确实存在的任务；列表尚未加载或页面只是 Pager 预加载时保留
    // pending，等页面稳定活动后再打开编辑器。
    LaunchedEffect(pendingOpenTask, tasks, active) {
        if (!active) return@LaunchedEffect
        val id = pendingOpenTask ?: return@LaunchedEffect
        tasks.firstOrNull { it.id == id }?.let { task ->
            adding = false
            editorTask = task
            store.consumeOpenTask()
        }
    }

    Scaffold(
        containerColor = com.upcpocket.app.ui.liquid.liquidScaffoldColor(),
        topBar = {
            com.upcpocket.app.ui.liquid.LiquidTopAppBar(title = { Text("待办") })
        },
        snackbarHost = {
            SnackbarHost(
                snackbar,
                modifier = Modifier.padding(bottom = dockClearance),
            )
        },
        floatingActionButton = {
            com.upcpocket.app.ui.liquid.LiquidFloatingActionButton(
                onClick = {
                    editorTask = null
                    adding = true
                },
                modifier = Modifier
                    .padding(bottom = dockClearance)
                    .semantics { contentDescription = "新增待办" }
                    .testTag("todo_add"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 96.dp + dockClearance,
            ),
        ) {
            if (hasReminders && (access.hasNotificationIssue || access.hasExactAlarmIssue)) {
                item(key = "reminder_banner", contentType = "reminder_banner") {
                    TodoReminderBanner(
                        access = access,
                        onNotificationSettings = { openNotificationSettings(context) },
                        onExactAlarmSettings = { openExactAlarmSettings(context) },
                    )
                }
            }

            if (incomplete.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyTodoState(onAdd = {
                        editorTask = null
                        adding = true
                    })
                }
            } else {
                items(
                    items = incomplete,
                    key = { it.id },
                    contentType = { "todo_task" },
                ) { task ->
                    TodoRow(
                        task = task,
                        now = now,
                        completionOverride = { completionOverrides[task.id]?.completed },
                        modifier = Modifier.animateItem(),
                        onToggle = { toggleCompletion(task) },
                        onEdit = { editorTask = task },
                    )
                }
            }

            if (completed.isNotEmpty()) {
                item(key = "completed_header", contentType = "completed_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCompleted = !showCompleted }
                            .padding(top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已完成 ${completed.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (showCompleted) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (showCompleted) "收起已完成" else "展开已完成",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showCompleted) {
                    items(
                        items = completed,
                        key = { it.id },
                        contentType = { "todo_task" },
                    ) { task ->
                        TodoRow(
                            task = task,
                            now = now,
                            completionOverride = { completionOverrides[task.id]?.completed },
                            modifier = Modifier.animateItem(),
                            onToggle = { toggleCompletion(task) },
                            onEdit = { editorTask = task },
                        )
                    }
                }
            }
        }
    }

    if (adding || editorTask != null) {
        TodoEditorSheet(
            store = store,
            initialTask = editorTask,
            onDismiss = {
                adding = false
                editorTask = null
            },
            onDeleted = { deleted ->
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = "已删除",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        store.restore(deleted)
                    }
                }
            },
        )
    }
}

@Composable
private fun EmptyTodoState(onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("暂无待办", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        com.upcpocket.app.ui.liquid.LiquidTextButton(onClick = onAdd) { Text("添加一条") }
    }
}

@Composable
private fun TodoRow(
    task: TodoTask,
    now: Long,
    completionOverride: () -> Boolean?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val override = completionOverride()
    val pending = override != null
    val completed = override ?: task.completed
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .testTag("todo_row_${task.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = completed,
            onCheckedChange = { if (!pending) onToggle() },
            enabled = !pending,
            modifier = Modifier.semantics {
                contentDescription = if (completed) {
                    "标记未完成：${task.title}"
                } else {
                    "标记完成：${task.title}"
                }
                role = Role.Checkbox
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (completed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (completed) TextDecoration.LineThrough else null,
            )
            task.reminderAt?.let { reminder ->
                Text(
                    text = TodoFormat.shortTime(reminder, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!completed && reminder <= now) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun TodoReminderBanner(
    access: TodoReminderAccess,
    onNotificationSettings: () -> Unit,
    onExactAlarmSettings: () -> Unit,
) {
    val notificationIssue = access.hasNotificationIssue
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (notificationIssue) Icons.Outlined.NotificationsOff else Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (notificationIssue) {
                    "通知权限未开启，提醒不会显示。"
                } else {
                    "未允许精确闹钟，提醒可能延迟。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            com.upcpocket.app.ui.liquid.LiquidTextButton(
                onClick = if (notificationIssue) onNotificationSettings else onExactAlarmSettings,
            ) { Text("去设置") }
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

private fun openExactAlarmSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }
}
