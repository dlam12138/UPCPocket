package com.upcpocket.app.ui.todo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.plugin.ui.PluginSectionHost
import com.upcpocket.app.todo.TODO_ROUTE
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoOrdering
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.ui.components.GlassCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页页头下、常用服务上的待办摘要。
 *
 * 只展示未完成排序的前两条；勾选/新增/进入待办页都直接操作同一 Repository Flow。
 */
@Composable
fun TodoHomeSection(
    store: TodoStore,
    host: PluginSectionHost,
) {
    val tasks by store.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var adding by remember { mutableStateOf(false) }
    var completionOverrides by remember {
        mutableStateOf(emptyMap<String, TodoCompletionOverride>())
    }
    var pendingUndo by remember { mutableStateOf<PendingCompletionUndo?>(null) }
    var undoMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(pendingUndo) {
        val pending = pendingUndo ?: return@LaunchedEffect
        delay(5_000)
        if (pendingUndo == pending) pendingUndo = null
    }
    LaunchedEffect(tasks, completionOverrides) {
        val reconciled = reconcileTodoCompletionOverrides(tasks, completionOverrides)
        if (reconciled != completionOverrides) completionOverrides = reconciled
    }
    LaunchedEffect(undoMessage) {
        if (undoMessage != null) {
            delay(3_000)
            undoMessage = null
        }
    }

    val summary = remember(tasks, now) {
        TodoOrdering.homeSummary(tasks, now, limit = 2)
    }
    val incompleteCount = tasks.count { !it.completed }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "待办",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable { host.open(TODO_ROUTE) }
                        .padding(vertical = 4.dp),
                )
                Text(
                    "$incompleteCount 项未完成",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { adding = true },
                    modifier = Modifier.testTag("todo_home_add"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新增待办")
                }
            }

            pendingUndo?.let { pending ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .testTag("todo_home_undo"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (pendingUndo == pending) pendingUndo = null
                            undoMessage = null
                            completionOverrides = completionOverrides + (
                                pending.taskId to TodoCompletionOverride(
                                    revision = pending.revision + 1,
                                    completed = false,
                                )
                            )
                            scope.launch {
                                val undone = try {
                                    store.setCompletedIfRevision(
                                        pending.taskId,
                                        pending.revision,
                                        completed = false,
                                    )
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    completionOverrides = completionOverrides - pending.taskId
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                                if (undone == null) {
                                    completionOverrides = completionOverrides - pending.taskId
                                    undoMessage = "待办已变化，无法撤销"
                                } else {
                                    completionOverrides = completionOverrides + (
                                        pending.taskId to TodoCompletionOverride(
                                            revision = undone.revision,
                                            completed = false,
                                        )
                                    )
                                }
                            }
                        },
                    ) { Text("撤销") }
                }
            }
            undoMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (summary.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "暂无待办",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                summary.forEachIndexed { index, task ->
                    HomeTodoRow(
                        task = task,
                        now = now,
                        completionState = completionOverrides[task.id]?.completed ?: task.completed,
                        isPending = task.id in completionOverrides,
                        onToggle = {
                            if (task.id in completionOverrides) return@HomeTodoRow
                            completionOverrides = completionOverrides + (
                                task.id to TodoCompletionOverride(
                                    revision = task.revision + 1,
                                    completed = true,
                                )
                            )
                            scope.launch {
                                val completed = try {
                                    store.setCompletedIfRevision(
                                        task.id,
                                        task.revision,
                                        completed = true,
                                    )
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    completionOverrides = completionOverrides - task.id
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                                if (completed == null) {
                                    completionOverrides = completionOverrides - task.id
                                    undoMessage = "待办已变化，未能完成"
                                    return@launch
                                }
                                completionOverrides = completionOverrides + (
                                    task.id to TodoCompletionOverride(
                                        revision = completed.revision,
                                        completed = true,
                                    )
                                )
                                pendingUndo = PendingCompletionUndo(
                                    taskId = completed.id,
                                    revision = completed.revision,
                                )
                            }
                        },
                        onOpen = { host.open(TODO_ROUTE) },
                    )
                    if (index != summary.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 44.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        TodoEditorSheet(
            store = store,
            initialTask = null,
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun HomeTodoRow(
    task: TodoTask,
    now: Long,
    completionState: Boolean,
    isPending: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = completionState,
            onCheckedChange = { if (!isPending) onToggle() },
            enabled = !isPending,
            modifier = Modifier.semantics {
                contentDescription = "完成：${task.title}"
                role = Role.Checkbox
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                textDecoration = if (completionState) TextDecoration.LineThrough else null,
                color = if (completionState) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            task.reminderAt?.let { reminder ->
                Text(
                    TodoFormat.shortTime(reminder, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reminder <= now) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private data class PendingCompletionUndo(
    val taskId: String,
    val revision: Int,
)
