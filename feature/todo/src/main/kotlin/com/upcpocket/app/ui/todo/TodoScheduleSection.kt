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
import androidx.compose.runtime.key
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
import com.upcpocket.app.todo.PersonalSchedule
import com.upcpocket.app.todo.PersonalScheduleStore
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.PersonalScheduleTime
import com.upcpocket.app.todo.data.TodoDeletedTask
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoOrderingSnapshot
import com.upcpocket.app.todo.data.TodoSnapshot
import com.upcpocket.app.todo.data.TodoSortMode
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
 * 只读本机 [TodoStore] 与可选的 [PersonalScheduleStore]；不申请权限、不触发学校
 * 登录或课表查询。日期条固定周日至周六，默认跟随设备本地“今天”，显式选日后保持，
 * 直到用户点“回到今天”。日程按设备本地日期的半开窗口读取，列表用设备本地时刻
 * 显示所选日期内的片段；未接入日程存储时保持原有待办入口与行为。
 *
 * [zone] 为 null 时随前台恢复和分钟刷新重新读取设备时区；测试可显式注入固定时区
 * 与 [clock]，避免依赖运行环境。
 */
@Composable
fun TodoScheduleSection(
    store: TodoStore,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId? = null,
    clock: () -> Long = System::currentTimeMillis,
    personalScheduleStore: PersonalScheduleStore? = null,
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
        // 任务与排序来自同一份真实快照；页面停止时取消收集，恢复后继续接收最新值。
        // 加载/失败/有效空列表的区分保持不变。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                store.snapshot.collect { value = TodoScheduleLoad.Ready(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = TodoScheduleLoad.Failed
            }
        }
    }
    val loadedSnapshot = (loadState as? TodoScheduleLoad.Ready)?.snapshot
    val ordering = loadedSnapshot?.ordering
    val tasks = loadedSnapshot?.tasks.orEmpty()

    var now by remember { mutableLongStateOf(clock()) }
    var effectiveZone by remember { mutableStateOf(zone ?: ZoneId.systemDefault()) }
    var selectedEpochDay by rememberSaveable { mutableLongStateOf(NO_SELECTED_DAY) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editorTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var agendaRequest by rememberSaveable { mutableStateOf<AgendaEditorRequest?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var completionOverrides by remember {
        mutableStateOf(emptyMap<String, TodoCompletionOverride>())
    }
    // 每行独立提交；写入期间同一任务不会再次提交，用任务 ID 集合即可锁定。
    var inFlightSubmissions by remember { mutableStateOf(emptySet<String>()) }
    var pendingDeletion by remember { mutableStateOf<TodoDeletedTask?>(null) }
    var undoMessage by remember { mutableStateOf<String?>(null) }

    // 前台恢复立即刷新日期与设备时区；停留在前台时每分钟推进日期并复核时区。
    LaunchedEffect(lifecycleOwner, zone) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effectiveZone = zone ?: ZoneId.systemDefault()
            now = clock()
            while (true) {
                delay(60_000)
                now = clock()
                if (zone == null) effectiveZone = ZoneId.systemDefault()
            }
        }
    }
    LaunchedEffect(tasks, completionOverrides) {
        val reconciled = reconcileTodoCompletionOverrides(tasks, completionOverrides)
        if (reconciled != completionOverrides) completionOverrides = reconciled
    }
    LaunchedEffect(pendingDeletion) {
        val pending = pendingDeletion ?: return@LaunchedEffect
        delay(5_000)
        if (pendingDeletion == pending) pendingDeletion = null
    }
    LaunchedEffect(undoMessage) {
        if (undoMessage != null) {
            delay(3_000)
            undoMessage = null
        }
    }

    val today = remember(now, effectiveZone) {
        Instant.ofEpochMilli(now).atZone(effectiveZone).toLocalDate()
    }
    val selectedDate = remember(selectedEpochDay, today) {
        if (selectedEpochDay == NO_SELECTED_DAY) today else LocalDate.ofEpochDay(selectedEpochDay)
    }
    val isToday = selectedDate == today
    val weekDates = remember(selectedDate) { TodoSchedule.weekDates(selectedDate) }
    val dayTasks = remember(tasks, selectedDate, today, effectiveZone, ordering) {
        TodoSchedule.tasksForDate(
            tasks = tasks,
            date = selectedDate,
            today = today,
            zone = effectiveZone,
            mode = ordering?.mode ?: TodoSortMode.DATE,
            ordering = ordering ?: TodoOrderingSnapshot.Default,
        )
    }
    val unscheduledOpen = remember(tasks) { TodoSchedule.unscheduledOpenCount(tasks) }
    val editorTask = remember(tasks, editorTaskId) {
        tasks.firstOrNull { it.id == editorTaskId }
    }

    LaunchedEffect(editorTaskId, loadState) {
        val id = editorTaskId ?: return@LaunchedEffect
        val ready = loadState as? TodoScheduleLoad.Ready ?: return@LaunchedEffect
        if (ready.snapshot.tasks.none { it.id == id }) editorTaskId = null
    }

    fun startAdding() {
        editorTaskId = null
        val scheduleStore = personalScheduleStore
        if (scheduleStore != null) {
            agendaRequest = AgendaEditorRequest.Add(selectedDate, effectiveZone)
        } else {
            adding = true
        }
    }

    fun selectDate(date: LocalDate) {
        // 点今天与「回到今天」是同一跟随态，跨午夜后仍会跟到新的今天。
        selectedEpochDay = if (date == today) NO_SELECTED_DAY else date.toEpochDay()
    }

    fun submitCompletion(task: TodoTask, target: Boolean) {
        // 本行在写入结果或 Room Flow 确认前保持锁定；不同任务各自独立。
        if (task.id in completionOverrides || task.id in inFlightSubmissions) return
        val optimistic = TodoCompletionOverride(
            revision = task.revision + 1,
            completed = target,
        )
        inFlightSubmissions = inFlightSubmissions + task.id
        completionOverrides = completionOverrides + (task.id to optimistic)
        undoMessage = null
        scope.launch {
            var writeFailed = false
            val updated = try {
                store.setCompletedIfRevision(task.id, task.revision, target)
            } catch (cancelled: CancellationException) {
                if (completionOverrides[task.id] == optimistic) {
                    completionOverrides = completionOverrides - task.id
                }
                inFlightSubmissions = inFlightSubmissions - task.id
                throw cancelled
            } catch (_: Exception) {
                writeFailed = true
                null
            }
            if (completionOverrides[task.id] == optimistic) {
                if (updated == null) {
                    completionOverrides = completionOverrides - task.id
                } else {
                    completionOverrides = completionOverrides + (
                        task.id to TodoCompletionOverride(
                            revision = updated.revision,
                            completed = updated.completed,
                        )
                    )
                }
            }
            inFlightSubmissions = inFlightSubmissions - task.id
            if (updated == null) {
                undoMessage = if (writeFailed) {
                    "待办未能更新，请重试"
                } else {
                    "待办已变化，未能更新"
                }
            }
        }
    }

    fun undoDeletion(pending: TodoDeletedTask) {
        if (pendingDeletion == pending) pendingDeletion = null
        undoMessage = null
        scope.launch {
            val restored = try {
                store.restoreWithOrder(pending)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            undoMessage = when (restored) {
                null -> "待办未能恢复，请重试"
                false -> "待办已变化，无法撤销"
                true -> null
            }
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
    // 加载/失败/有效空列表已由正文唯一表达；未完成计数独立于当天含已完成项的总数。
    val countLabel = dayTasks.count { !it.completed }
        .takeIf { dayTasks.isNotEmpty() }
        ?.let { "$it 项未完成" }

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
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = if (personalScheduleStore == null) {
                            "新增待办"
                        } else {
                            "新增待办或日程"
                        },
                    )
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

            personalScheduleStore?.let { scheduleStore ->
                ScheduleDayList(
                    store = scheduleStore,
                    date = selectedDate,
                    zone = effectiveZone,
                    onEdit = { schedule ->
                        agendaRequest = AgendaEditorRequest.EditSchedule(schedule.id)
                    },
                )
            }

            when {
                loadState is TodoScheduleLoad.Failed -> Row(
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

                loadedSnapshot != null -> {
                    val readyTasks = loadedSnapshot.tasks
                    if (readyTasks.isEmpty()) {
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
                                "这天没有待办。",
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
                                key(task.id) {
                                    ScheduleTaskRow(
                                        task = task,
                                        now = now,
                                        zone = effectiveZone,
                                        completionOverride = completionOverrides[task.id],
                                        submitting = task.id in inFlightSubmissions,
                                        onToggle = { target -> submitCompletion(task, target) },
                                        onEdit = {
                                            adding = false
                                            editorTaskId = task.id
                                        },
                                    )
                                }
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

                else -> Text(
                    "正在读取待办",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            pendingDeletion?.let { pending ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .testTag("todo_schedule_undo"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    CampusTextButton(
                        onClick = { undoDeletion(pending) },
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
                pendingDeletion = deleted
                undoMessage = null
            },
        )
    }

    val scheduleStore = personalScheduleStore
    if (scheduleStore != null) {
        val activeAgendaRequest = agendaRequest
        AgendaEditorHost(
            todoStore = store,
            personalScheduleStore = scheduleStore,
            request = activeAgendaRequest,
            onDismiss = {
                // 旧面板的异步收尾（如 NonCancellable 删除）不得清掉随后打开的新请求。
                if (agendaRequest === activeAgendaRequest) {
                    agendaRequest = null
                }
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
private fun ScheduleDayList(
    store: PersonalScheduleStore,
    date: LocalDate,
    zone: ZoneId,
    onEdit: (PersonalSchedule) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var readGeneration by rememberSaveable { mutableIntStateOf(0) }
    // 设备本地半开窗口：结束恰好落在次日零点的日程不会漏到次日。
    val window = remember(date, zone) { PersonalScheduleTime.dayWindow(date, zone) }
    val loadState by produceState<ScheduleListLoad>(
        initialValue = ScheduleListLoad.Loading,
        store,
        window,
        readGeneration,
        lifecycleOwner,
    ) {
        value = ScheduleListLoad.Loading
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                store.observeWindow(window.fromInclusive, window.untilExclusive)
                    .collect { value = ScheduleListLoad.Ready(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = ScheduleListLoad.Failed
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("todo_schedule_schedule_list"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("日程", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            if (zone != PersonalScheduleTime.SHANGHAI) {
                Text(
                    "设备时间",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("todo_schedule_schedule_timezone"),
                )
            }
        }
        when (val state = loadState) {
            ScheduleListLoad.Failed -> Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "日程读取失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                CampusTextButton(
                    onClick = { readGeneration++ },
                    modifier = Modifier.testTag("todo_schedule_schedule_retry"),
                ) { Text("重试") }
            }

            is ScheduleListLoad.Ready -> {
                val schedules = PersonalSchedulePresentation.sorted(state.schedules)
                if (schedules.isEmpty()) {
                    Text(
                        "这天没有日程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("todo_schedule_schedule_empty"),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        schedules.forEach { schedule ->
                            key(schedule.id) {
                                ScheduleItemRow(
                                    schedule = schedule,
                                    date = date,
                                    zone = zone,
                                    onEdit = { onEdit(schedule) },
                                )
                            }
                        }
                    }
                }
            }

            else -> Text(
                "正在读取日程",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("todo_schedule_schedule_loading"),
            )
        }
    }
}

@Composable
private fun ScheduleItemRow(
    schedule: PersonalSchedule,
    date: LocalDate,
    zone: ZoneId,
    onEdit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GlassSurface(
        onClick = onEdit,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("todo_schedule_item_${schedule.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .background(
                        scheduleAccent(scheme, schedule.id),
                        RoundedCornerShape(2.dp),
                    ),
            )
            Column(
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    schedule.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = scheme.onSurface,
                    modifier = Modifier.testTag("todo_schedule_item_title_${schedule.id}"),
                )
                Text(
                    PersonalSchedulePresentation.timeLabel(schedule, date, zone),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag("todo_schedule_item_time_${schedule.id}"),
                )
                schedule.location?.takeIf { it.isNotBlank() }?.let { location ->
                    Text(
                        location,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("todo_schedule_item_location_${schedule.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleTaskRow(
    task: TodoTask,
    now: Long,
    zone: ZoneId,
    completionOverride: TodoCompletionOverride?,
    submitting: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val completed = completionOverride?.completed ?: task.completed
    // 与 submitCompletion 的拒绝条件保持一致，避免可点击但提交被丢弃。
    val pending = completionOverride != null || submitting
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
                onCheckedChange = { target -> if (!pending) onToggle(target) },
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
    data class Ready(val snapshot: TodoSnapshot) : TodoScheduleLoad
}

private sealed interface ScheduleListLoad {
    data object Loading : ScheduleListLoad
    data object Failed : ScheduleListLoad
    data class Ready(val schedules: List<PersonalSchedule>) : ScheduleListLoad
}
