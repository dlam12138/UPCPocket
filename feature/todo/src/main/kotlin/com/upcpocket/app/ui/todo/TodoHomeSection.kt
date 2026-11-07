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

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    val summary = remember(tasks, now) {
        TodoOrdering.homeSummary(tasks, now, limit = 2)
    }
    val incompleteCount = tasks.count { !it.completed }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
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

            if (summary.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "暂无待办",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { adding = true }) { Text("添加") }
                }
            } else {
                summary.forEachIndexed { index, task ->
                    HomeTodoRow(
                        task = task,
                        now = now,
                        onToggle = {
                            scope.launch { store.setCompleted(task.id, !task.completed) }
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
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = false,
            onCheckedChange = { onToggle() },
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
