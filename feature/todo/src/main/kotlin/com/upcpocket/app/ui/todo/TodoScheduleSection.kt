package com.upcpocket.app.ui.todo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoTask
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.GlassCard
import com.upcpocket.app.ui.components.GlassCheckbox
import com.upcpocket.app.ui.components.GlassIconButton
import com.upcpocket.app.ui.components.GlassSurface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val NO_SELECTED_DAY = Long.MIN_VALUE
private const val VISIBLE_TASK_LIMIT = 4
private val WEEKDAY_LABELS = listOf("日", "一", "二", "三", "四", "五", "六")

/**
 * 课表页顶部的本机日程待办。
 *
 * 只读本机 [TodoStore]；不申请权限、不触发学校登录或课表查询。日期条固定周日至
 * 周六，默认跟随设备本地“今天”，显式选日后保持，直到用户点“回到今天”。
 */
@Composable
fun TodoScheduleSection(
    store: TodoStore,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    clock: () -> Long = System::currentTimeMillis,
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var readGeneration by rememberSaveable { mutableIntStateOf(0) }
    val loadState by produceState<TodoScheduleLoad>(
        initialValue = TodoScheduleLoad.Loading,
        store,
        readGeneration,
        lifecycleOwner,
    ) {
        value = TodoScheduleLoad.Loading
        // 页面停止时取消收集，恢复后继续接收最新快照；加载/失败/有效空列表
        // 的区分保持不变。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                store.tasks.collect { value = TodoScheduleLoad.Ready(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = TodoScheduleLoad.Failed
            }
        }
    }
    val tasks = (loadState as? TodoScheduleLoad.Ready)?.tasks.orEmpty()

    var now by remember { mutableLongStateOf(clock()) }
    var selectedEpochDay by rememberSaveable { mutableLongStateOf(NO_SELECTED_DAY) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editorTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var completionOverrides by remember {
        mutableStateOf(emptyMap<String, TodoCompletionOverride>())
    }
    var pendingUndo by remember { mutableStateOf<PendingScheduleUndo?>(null) }
    var undoMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = clock()
        }
    }
    LaunchedEffect(tasks, completionOverrides) {
        val reconciled = reconcileTodoCompletionOverrides(tasks, completionOverrides)
        if (reconciled != completionOverrides) completionOverrides = reconciled
    }
    LaunchedEffect(pendingUndo) {
        val pending = pendingUndo ?: return@LaunchedEffect
        delay(5_000)
        if (pendingUndo == pending) pendingUndo = null
    }
    LaunchedEffect(undoMessage) {
        if (undoMessage != null) {
            delay(3_000)
            undoMessage = null
        }
    }

    val today = remember(now, zone) {
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }
    val selectedDate = remember(selectedEpochDay, today) {
        if (selectedEpochDay == NO_SELECTED_DAY) today else LocalDate.ofEpochDay(selectedEpochDay)
    }
    val isToday = selectedDate == today
    val weekDates = remember(selectedDate) { TodoSchedule.weekDates(selectedDate) }
    val dayTasks = remember(tasks, selectedDate, today, zone) {
        TodoSchedule.tasksForDate(tasks, selectedDate, today, zone)
    }
    val unscheduledOpen = remember(tasks) { TodoSchedule.unscheduledOpenCount(tasks) }
    val editorTask = remember(tasks, editorTaskId) {
        tasks.firstOrNull { it.id == editorTaskId }
    }

    LaunchedEffect(editorTaskId, loadState) {
        val id = editorTaskId ?: return@LaunchedEffect
        val ready = loadState as? TodoScheduleLoad.Ready ?: return@LaunchedEffect
        if (ready.tasks.none { it.id == id }) editorTaskId = null
    }

    fun startAdding() {
        editorTaskId = null
        adding = true
    }

    fun selectDate(date: LocalDate) {
        // 点今天与「回到今天」是同一跟随态，跨午夜后仍会跟到新的今天。
        selectedEpochDay = if (date == today) NO_SELECTED_DAY else date.toEpochDay()
    }

    fun toggleCompletion(task: TodoTask) {
        if (task.id in completionOverrides) return
        completionOverrides = completionOverrides + (
            task.id to TodoCompletionOverride(
                revision = task.revision + 1,
                completed = true,
            )
        )
        scope.launch {
            val completed = try {
                store.setCompletedIfRevision(task.id, task.revision, completed = true)
            } catch (cancelled: CancellationException) {
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
                completed.id to TodoCompletionOverride(
                    revision = completed.revision,
                    completed = true,
                )
            )
            pendingUndo = PendingScheduleUndo.Completion(
                taskId = completed.id,
                revision = completed.revision,
            )
        }
    }

    fun undoCompletion(pending: PendingScheduleUndo.Completion) {
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
            } catch (cancelled: CancellationException) {
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
                    undone.id to TodoCompletionOverride(
                        revision = undone.revision,
                        completed = false,
                    )
                )
            }
        }
    }

    fun undoDeletion(pending: PendingScheduleUndo.Deletion) {
        if (pendingUndo == pending) pendingUndo = null
        undoMessage = null
        scope.launch {
            val restored = try {
                store.restore(pending.task)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!restored) undoMessage = "待办已变化，无法撤销"
        }
    }

    val weekday = WEEKDAY_LABELS[selectedDate.dayOfWeek.value % 7]
    val summary = if (isToday) {
        "今天 · ${selectedDate.monthValue}月${selectedDate.dayOfMonth}日"
    } else {
        "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 周$weekday"
    }
    // 周条跨月/跨年时显示真实范围，不用单一月份误代表整周。
    val monthLabel = remember(weekDates) {
        val first = weekDates.first()
        val last = weekDates.last()
        when {
            first.year == last.year && first.monthValue == last.monthValue ->
                "${first.year}年${first.monthValue}月"
            first.year == last.year ->
                "${first.year}年${first.monthValue}月–${last.monthValue}月"
            else ->
                "${first.year}年${first.monthValue}月–${last.year}年${last.monthValue}月"
        }
    }
    // 加载/失败/有效空列表已由正文唯一表达；这里只显示确实存在的日程数量。
    val countLabel = dayTasks.size.takeIf { it > 0 }?.let { "$it 项" }

    GlassCard(modifier = modifier.fillMaxWidth().testTag("todo_schedule_section")) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("日程待办", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                CampusTextButton(
                    onClick = onOpenAll,
                    modifier = Modifier.testTag("todo_schedule_all"),
                ) { Text("全部待办") }
                GlassIconButton(
                    onClick = ::startAdding,
                    modifier = Modifier.testTag("todo_schedule_add"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新增待办")
                }
            }

            // 年月标题独占一行，周切换按钮不再挤占七个日期格。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    onClick = { selectDate(selectedDate.minusDays(7)) },
                    modifier = Modifier.testTag("todo_schedule_previous_week"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一周",
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        monthLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        countLabel?.let { label ->
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
                GlassIconButton(
                    onClick = { selectDate(selectedDate.plusDays(7)) },
                    modifier = Modifier.testTag("todo_schedule_next_week"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一周",
                    )
                }
            }

            // 七格等宽铺满，窄屏也不会因两侧图标被压成椭圆。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                weekDates.forEach { date ->
                    ScheduleDayCell(
                        date = date,
                        selected = date == selectedDate,
                        today = date == today,
                        onClick = { selectDate(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (!isToday) {
                CampusTextButton(
                    onClick = { selectedEpochDay = NO_SELECTED_DAY },
                    modifier = Modifier.testTag("todo_schedule_today"),
                ) { Text("回到今天") }
            }

            when (val load = loadState) {
                TodoScheduleLoad.Loading -> Text(
                    "正在读取待办",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                TodoScheduleLoad.Failed -> Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "待办读取失败。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    CampusTextButton(
                        onClick = { readGeneration++ },
                        modifier = Modifier.testTag("todo_schedule_retry"),
                    ) { Text("重试") }
                }

                is TodoScheduleLoad.Ready -> {
                    if (load.tasks.isEmpty()) {
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
                            CampusTextButton(onClick = ::startAdding) { Text("添加一条") }
                        }
                    } else if (dayTasks.isEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "这天没有未完成待办。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (unscheduledOpen > 0) {
                                Text(
                                    "另有 $unscheduledOpen 项未设置时间。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        val visibleTasks = if (expanded) {
                            dayTasks
                        } else {
                            dayTasks.take(VISIBLE_TASK_LIMIT)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            visibleTasks.forEach { task ->
                                ScheduleTaskRow(
                                    task = task,
                                    now = now,
                                    zone = zone,
                                    completionOverride = completionOverrides[task.id],
                                    onToggle = { toggleCompletion(task) },
                                    onEdit = {
                                        adding = false
                                        editorTaskId = task.id
                                    },
                                )
                            }
                        }
                        if (dayTasks.size > VISIBLE_TASK_LIMIT) {
                            CampusTextButton(
                                onClick = { expanded = !expanded },
                                modifier = Modifier.testTag("todo_schedule_expand"),
                            ) {
                                Text(
                                    if (expanded) {
                                        "收起"
                                    } else {
                                        "展开另外 ${dayTasks.size - VISIBLE_TASK_LIMIT} 项"
                                    },
                                )
                            }
                        }
                    }
                }
            }

            pendingUndo?.let { pending ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .testTag("todo_schedule_undo"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (pending) {
                            is PendingScheduleUndo.Completion -> "已完成"
                            is PendingScheduleUndo.Deletion -> "已删除"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    CampusTextButton(
                        onClick = {
                            when (pending) {
                                is PendingScheduleUndo.Completion -> undoCompletion(pending)
                                is PendingScheduleUndo.Deletion -> undoDeletion(pending)
                            }
                        },
                    ) { Text("撤销") }
                }
            }
            undoMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (adding || editorTask != null) {
        TodoEditorSheet(
            store = store,
            initialTask = editorTask,
            onDismiss = {
                adding = false
                editorTaskId = null
            },
            onDeleted = { deleted ->
                // 与完整待办编辑器一致：删除后在同一页给一次恢复机会。
                pendingUndo = PendingScheduleUndo.Deletion(deleted)
                undoMessage = null
            },
        )
    }
}

@Composable
private fun ScheduleDayCell(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val weekday = WEEKDAY_LABELS[date.dayOfWeek.value % 7]
    val label = if (today) {
        "今天 ${date.monthValue}月${date.dayOfMonth}日"
    } else {
        "${date.monthValue}月${date.dayOfMonth}日 周$weekday"
    }
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .testTag("todo_schedule_day_${date}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            weekday,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                selected -> scheme.primary
                today -> scheme.primary
                else -> scheme.onSurfaceVariant
            },
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .then(
                    when {
                        selected -> Modifier.background(scheme.primary, CircleShape)
                        today -> Modifier.border(1.dp, scheme.primary, CircleShape)
                        else -> Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    selected -> scheme.onPrimary
                    today -> scheme.primary
                    else -> scheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun ScheduleTaskRow(
    task: TodoTask,
    now: Long,
    zone: ZoneId,
    completionOverride: TodoCompletionOverride?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val completed = completionOverride?.completed ?: task.completed
    val pending = completionOverride != null
    val scheme = MaterialTheme.colorScheme
    val deadline = task.dueAt
    val accent = if (!completed && deadline != null && deadline <= now) {
        scheme.error
    } else {
        scheduleAccent(scheme, task.id)
    }
    GlassSurface(
        onClick = onEdit,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().testTag("todo_schedule_task_${task.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
            )
            GlassCheckbox(
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
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (completed) TextDecoration.LineThrough else null,
                    color = if (completed) {
                        scheme.onSurfaceVariant
                    } else {
                        scheme.onSurface
                    },
                )
                deadline?.let { dueAt ->
                    Text(
                        "截止 ${TodoFormat.shortTime(dueAt, now, zone)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!completed && dueAt <= now) {
                            scheme.error
                        } else {
                            scheme.onSurfaceVariant
                        },
                    )
                }
                task.reminderAt?.let { reminderAt ->
                    Text(
                        "提醒 ${TodoFormat.shortTime(reminderAt, now, zone)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!completed && reminderAt <= now && task.reminderFiredAt == null) {
                            scheme.error
                        } else {
                            scheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private fun scheduleAccent(scheme: ColorScheme, taskId: String): Color {
    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary)
    return palette[Math.floorMod(taskId.hashCode(), palette.size)]
}

private sealed interface TodoScheduleLoad {
    data object Loading : TodoScheduleLoad
    data object Failed : TodoScheduleLoad
    data class Ready(val tasks: List<TodoTask>) : TodoScheduleLoad
}

private sealed interface PendingScheduleUndo {
    data class Completion(val taskId: String, val revision: Int) : PendingScheduleUndo
    data class Deletion(val task: TodoTask) : PendingScheduleUndo
}
