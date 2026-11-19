package com.upcpocket.app.ui.todo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.upcpocket.app.ui.components.GlassCheckbox as Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import com.upcpocket.app.ui.components.GlassSnackbarHost as SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.upcpocket.app.ui.components.GlassSurface as Surface
import androidx.compose.material3.Text
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
import com.upcpocket.app.ui.components.glassPressHighlight
import com.upcpocket.app.ui.components.isGlassSheetVisible
import com.upcpocket.app.ui.components.rememberGlassSnackbarBackdrop
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(store: TodoStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = LocalPluginPageActive.current
    val dockClearance = LocalDockClearance.current
    val snackbar = remember { SnackbarHostState() }
    val snackbarBackdrop = rememberGlassSnackbarBackdrop()
    val taskSnapshotFlow = remember(store) {
        store.tasks.map<List<TodoTask>, List<TodoTask>?> { it }
    }
    val taskSnapshot by taskSnapshotFlow.collectAsStateWithLifecycle(initialValue = null)
    val tasks = taskSnapshot.orEmpty()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var access by remember { mutableStateOf(store.reminderAccess()) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var editorTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val editorTask = remember(tasks, editorTaskId) {
        tasks.firstOrNull { it.id == editorTaskId }
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var completionOverrides by remember {
        mutableStateOf(emptyMap<String, TodoCompletionOverride>())
    }
    val pendingOpenTask by store.pendingOpenTask.collectAsStateWithLifecycle()
    val pendingCreateRequest by store.pendingCreateRequest.collectAsStateWithLifecycle()

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

    LaunchedEffect(editorTaskId, taskSnapshot) {
        val id = editorTaskId ?: return@LaunchedEffect
        val loadedTasks = taskSnapshot ?: return@LaunchedEffect
        if (loadedTasks.none { it.id == id }) editorTaskId = null
    }

    // 任务还未从 Room 发出首个快照时保留请求；不存在或已删除的目标在快照到达后清除。
    LaunchedEffect(pendingOpenTask, taskSnapshot, active) {
        if (!active) return@LaunchedEffect
        val id = pendingOpenTask ?: return@LaunchedEffect
        val loadedTasks = taskSnapshot ?: return@LaunchedEffect
        val task = loadedTasks.firstOrNull { it.id == id }
        if (task == null) {
            store.consumeOpenTask()
        } else {
            adding = false
            editorTaskId = task.id
            store.consumeOpenTask()
        }
    }

    // 新建请求只由活动待办页消费，离开/预加载期间保留在 Store 中。
    LaunchedEffect(pendingCreateRequest, active) {
        if (!active || pendingCreateRequest == null) return@LaunchedEffect
        editorTaskId = null
        adding = true
        store.consumeCreateRequest()
    }

    fun startAdding() {
        editorTaskId = null
        adding = true
    }

    fun editTask(task: TodoTask) {
        adding = false
        editorTaskId = task.id
    }

    fun dismissEditor() {
        adding = false
        editorTaskId = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            com.upcpocket.app.ui.components.CampusTopAppBar(title = { Text("待办") })
        },
        snackbarHost = {
            SnackbarHost(
                snackbar,
                modifier = if (snackbarBackdrop == null) Modifier.padding(bottom = dockClearance) else Modifier,
                backdrop = snackbarBackdrop,
            )
        },
        floatingActionButton = {
            if (!isGlassSheetVisible()) {
                com.upcpocket.app.ui.components.CampusFloatingActionButton(
                    onClick = ::startAdding,
                    modifier = Modifier
                        .padding(bottom = dockClearance)
                        .semantics { contentDescription = "新增待办" }
                        .testTag("todo_add"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { padding ->
        val background = MaterialTheme.colorScheme.surface
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
                .then(snackbarBackdrop?.recordContent(background) ?: Modifier),
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

            if (taskSnapshot == null) {
                item(key = "loading", contentType = "loading") {
                    Text("正在读取待办", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (incomplete.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyTodoState(onAdd = ::startAdding)
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
                        onEdit = { editTask(task) },
                    )
                }
            }

            if (completed.isNotEmpty()) {
                item(key = "completed_header", contentType = "completed_header") {
                    val completedHeaderInteractions = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassPressHighlight(MaterialTheme.shapes.medium, completedHeaderInteractions)
                            .clickable(
                                interactionSource = completedHeaderInteractions,
                                indication = LocalIndication.current,
                            ) { showCompleted = !showCompleted }
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
                            onEdit = { editTask(task) },
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
            onDismiss = ::dismissEditor,
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
        com.upcpocket.app.ui.components.CampusTextButton(onClick = onAdd) { Text("添加一条") }
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
    val editInteractions = remember(task.id) { MutableInteractionSource() }
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
                .glassPressHighlight(MaterialTheme.shapes.medium, editInteractions)
                .clickable(
                    interactionSource = editInteractions,
                    indication = LocalIndication.current,
                    onClick = onEdit,
                )
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
            task.dueAt?.let { deadline ->
                Text(
                    text = "截止 ${TodoFormat.shortTime(deadline, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!completed && deadline <= now) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            task.reminderAt?.let { reminder ->
                Text(
                    text = "提醒 ${TodoFormat.shortTime(reminder, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!completed && reminder <= now && task.reminderFiredAt == null) {
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
            com.upcpocket.app.ui.components.CampusTextButton(
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
