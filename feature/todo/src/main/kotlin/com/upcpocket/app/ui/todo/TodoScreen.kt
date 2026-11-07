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
import androidx.compose.material3.HorizontalDivider
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
    val pendingOpenTask by store.pendingOpenTask.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
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
        topBar = {
            TopAppBar(title = { Text("待办") })
        },
        snackbarHost = {
            SnackbarHost(
                snackbar,
                modifier = Modifier.padding(bottom = dockClearance),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editorTask = null
                    adding = true
                },
                modifier = Modifier
                    .semantics { contentDescription = "新增待办" }
                    .testTag("todo_add")
                    .padding(bottom = dockClearance),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
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
                item(key = "reminder_banner") {
                    TodoReminderBanner(
                        access = access,
                        onNotificationSettings = { openNotificationSettings(context) },
                        onExactAlarmSettings = { openExactAlarmSettings(context) },
                    )
                }
            }

            if (incomplete.isEmpty()) {
                item(key = "empty") {
                    EmptyTodoState(onAdd = {
                        editorTask = null
                        adding = true
                    })
                }
            } else {
                items(incomplete, key = { "open-${it.id}" }) { task ->
                    TodoRow(
                        task = task,
                        now = now,
                        onToggle = { scope.launch { store.setCompleted(task.id, !task.completed) } },
                        onEdit = { editorTask = task },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
            }

            if (completed.isNotEmpty()) {
                item(key = "completed_header") {
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
                    items(completed, key = { "done-${it.id}" }) { task ->
                        TodoRow(
                            task = task,
                            now = now,
                            onToggle = {
                                scope.launch { store.setCompleted(task.id, !task.completed) }
                            },
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("暂无待办", style = MaterialTheme.typography.titleMedium)
        Text(
            "只记标题和可选提醒，完成后自动收进已完成。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAdd) { Text("添加一条") }
    }
}

@Composable
private fun TodoRow(
    task: TodoTask,
    now: Long,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val completed = task.completed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .testTag("todo_row_${task.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = completed,
            onCheckedChange = { onToggle() },
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
            TextButton(
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
