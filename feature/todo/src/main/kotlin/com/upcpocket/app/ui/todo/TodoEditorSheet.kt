package com.upcpocket.app.ui.todo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.upcpocket.app.ui.components.GlassDatePicker as DatePicker
import com.upcpocket.app.ui.components.GlassDatePickerDialog as DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.upcpocket.app.ui.components.GlassModalBottomSheet as ModalBottomSheet
import com.upcpocket.app.ui.components.GlassOutlinedTextField as OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import com.upcpocket.app.ui.components.GlassTimePicker as TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.TodoDeletedTask
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoImportance
import com.upcpocket.app.todo.data.TodoSnapshot
import com.upcpocket.app.todo.data.TodoTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val IMPORTANCE_LABELS = listOf("高", "普通", "低")

/**
 * 新增/编辑待办底部面板。
 *
 * 新增模式回车保存后保留面板并清空输入，便于连续添加；编辑模式回车保存并关闭。
 * 截止与一次性提醒分别编辑，时间均按设备本地日期和时分保存；空白标题不可保存。
 * 编辑时等待真实重要程度到达后才允许保存，内容与重要程度同次写入；仅改程度走轻量路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditorSheet(
    store: TodoStore,
    initialTask: TodoTask?,
    onDismiss: () -> Unit,
    onDeleted: (TodoDeletedTask) -> Unit = {},
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val supportsOrdering = store.supportsOrdering
    var title by rememberSaveable(initialTask?.id) { mutableStateOf(initialTask?.title.orEmpty()) }
    var dueAt by rememberSaveable(initialTask?.id) { mutableStateOf(initialTask?.dueAt) }
    var reminderAt by rememberSaveable(initialTask?.id) { mutableStateOf(initialTask?.reminderAt) }
    var importance by rememberSaveable(initialTask?.id) {
        mutableStateOf<TodoImportance?>(
            if (initialTask == null || !supportsOrdering) TodoImportance.NORMAL else null,
        )
    }
    var importanceLoaded by remember(initialTask?.id) {
        mutableStateOf(initialTask == null || !supportsOrdering)
    }
    var showDueDate by rememberSaveable(initialTask?.id) { mutableStateOf(false) }
    var showDueTime by rememberSaveable(initialTask?.id) { mutableStateOf(false) }
    var showReminderDate by rememberSaveable(initialTask?.id) { mutableStateOf(false) }
    var showReminderTime by rememberSaveable(initialTask?.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var access by remember { mutableStateOf(store.reminderAccess()) }
    val snapshotFlow = remember(store) {
        store.snapshot.map<TodoSnapshot, TodoSnapshot?> { it }
    }
    val listSnapshot by snapshotFlow.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(initialTask?.id, listSnapshot, supportsOrdering) {
        val task = initialTask ?: return@LaunchedEffect
        if (!supportsOrdering) {
            importance = TodoImportance.NORMAL
            importanceLoaded = true
            return@LaunchedEffect
        }
        if (importanceLoaded) return@LaunchedEffect
        // 编辑时等重要程度与任务从同一份真实快照到达；不覆盖用户已选值。
        val snapshot = listSnapshot ?: return@LaunchedEffect
        val loaded = snapshot.ordering.importanceOf(task.id)
        importanceLoaded = true
        if (importance == null) importance = loaded
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        access = store.reminderAccess()
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    fun save(keepOpen: Boolean) {
        val trimmed = title.trim()
        val selectedImportance = importance ?: return
        if (trimmed.isEmpty() || saving) return
        saving = true
        errorText = null
        scope.launch {
            try {
                // 纯改重要程度由服务在锁内识别；旧 Store 没有排序表时走原有保存路径。
                val saved = when {
                    initialTask == null && supportsOrdering -> {
                        store.add(trimmed, reminderAt, dueAt, selectedImportance)
                        true
                    }
                    initialTask == null -> {
                        store.add(trimmed, reminderAt, dueAt)
                        true
                    }
                    supportsOrdering -> store.edit(
                        initialTask.id,
                        trimmed,
                        reminderAt,
                        dueAt,
                        selectedImportance,
                    )
                    else -> store.edit(initialTask.id, trimmed, reminderAt, dueAt)
                }
                if (!saved) {
                    saving = false
                    errorText = "待办已不存在，无法保存"
                    return@launch
                }
                if (initialTask == null && keepOpen) {
                    title = ""
                    reminderAt = null
                    dueAt = null
                    importance = TodoImportance.NORMAL
                    saving = false
                    runCatching { focusRequester.requestFocus() }
                    keyboard?.show()
                } else {
                    saving = false
                    onDismiss()
                }
            } catch (cancelled: CancellationException) {
                saving = false
                throw cancelled
            } catch (_: Exception) {
                saving = false
                errorText = "保存失败，请重试"
            }
        }
    }

    fun toggleReminder(enabled: Boolean) {
        if (!enabled) {
            reminderAt = null
            return
        }
        reminderAt = reminderAt ?: currentLocalMinute(zone)
        access = store.reminderAccess()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (initialTask == null) "新增待办" else "编辑待办",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 200) title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("todo_title_input"),
                label = { Text("标题") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (title.trim().isNotEmpty() && !saving && importance != null) {
                            save(keepOpen = initialTask == null)
                        }
                    },
                ),
            )

            if (supportsOrdering) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("重要程度", style = MaterialTheme.typography.titleSmall)
                    val selectedImportance = importance
                    if (selectedImportance == null) {
                        Text(
                            "正在读取重要程度",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        com.upcpocket.app.ui.components.CampusSegmentedTabs(
                            tabs = IMPORTANCE_LABELS,
                            selectedIndex = selectedImportance.ordinal,
                            onSelect = { index ->
                                importance = TodoImportance.entries[index]
                            },
                            modifier = Modifier.fillMaxWidth().testTag("todo_importance"),
                            enabled = !saving,
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("提醒", style = MaterialTheme.typography.titleSmall)
                    Text(
                        reminderAt?.let { TodoFormat.editorTime(it, zone) } ?: "不提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                com.upcpocket.app.ui.components.CampusSwitch(
                    checked = reminderAt != null,
                    onCheckedChange = ::toggleReminder,
                    modifier = Modifier.testTag("todo_reminder_switch"),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("截止", style = MaterialTheme.typography.titleSmall)
                        Text(
                            dueAt?.let { TodoFormat.editorTime(it, zone) } ?: "不设截止",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("todo_due_value"),
                        )
                    }
                    if (dueAt != null) {
                        com.upcpocket.app.ui.components.CampusTextButton(
                            onClick = {
                                focusManager.clearFocus()
                                dueAt = null
                            },
                            modifier = Modifier.testTag("todo_clear_due"),
                        ) { Text("清除") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.upcpocket.app.ui.components.CampusOutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            showDueDate = true
                        },
                        modifier = Modifier.testTag("todo_due_date"),
                    ) {
                        Text(dueAt?.let { TodoFormat.date(it, zone) } ?: "选择日期")
                    }
                    com.upcpocket.app.ui.components.CampusOutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            showDueTime = true
                        },
                        modifier = Modifier.testTag("todo_due_time"),
                    ) {
                        Text(dueAt?.let { TodoFormat.clock(it, zone) } ?: "选择时间")
                    }
                }
            }

            if (reminderAt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.upcpocket.app.ui.components.CampusOutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            showReminderDate = true
                        },
                        modifier = Modifier.testTag("todo_reminder_date"),
                    ) {
                        Text(TodoFormat.date(reminderAt!!, zone))
                    }
                    com.upcpocket.app.ui.components.CampusOutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            showReminderTime = true
                        },
                        modifier = Modifier.testTag("todo_reminder_time"),
                    ) {
                        Text(TodoFormat.clock(reminderAt!!, zone))
                    }
                }
                if (reminderAt!! <= System.currentTimeMillis()) {
                    val alreadyFired = initialTask?.let {
                        it.reminderAt == reminderAt && it.reminderFiredAt != null
                    } == true
                    Text(
                        if (alreadyFired) {
                            "本次提醒已发送（一次性）。"
                        } else {
                            "时间已过，保存后会立即提醒一次。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!access.notificationsEnabled) {
                    Text(
                        "通知权限未开启，保存后不会显示提醒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!access.exactAlarmsAllowed) {
                    Text(
                        "未允许精确闹钟，提醒可能延迟。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            errorText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (initialTask != null) {
                    com.upcpocket.app.ui.components.CampusTextButton(
                        onClick = {
                            if (saving) return@CampusTextButton
                            saving = true
                            errorText = null
                            scope.launch {
                                try {
                                    val deleted = store.deleteWithOrder(initialTask.id)
                                    saving = false
                                    if (deleted == null) {
                                        errorText = "待办已不存在"
                                    } else {
                                        onDeleted(deleted)
                                        onDismiss()
                                    }
                                } catch (cancelled: CancellationException) {
                                    saving = false
                                    throw cancelled
                                } catch (_: Exception) {
                                    saving = false
                                    errorText = "删除失败，请重试"
                                }
                            }
                        },
                        enabled = !saving,
                        contentColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("todo_editor_delete"),
                    ) {
                        Text("删除")
                    }
                }
                Column(Modifier.weight(1f)) {}
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = onDismiss,
                    enabled = !saving,
                    modifier = Modifier.testTag("todo_editor_cancel"),
                ) { Text("取消") }
                com.upcpocket.app.ui.components.CampusButton(
                    onClick = { save(keepOpen = false) },
                    enabled = title.trim().isNotEmpty() && !saving && importance != null,
                    modifier = Modifier.testTag("todo_save"),
                ) {
                    Text("保存")
                }
            }
        }
    }

    if (showDueDate) {
        val dueBase = dueAt ?: currentLocalMinute(zone)
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = utcDateMillis(dueBase, zone),
        )
        DatePickerDialog(
            onDismissRequest = { showDueDate = false },
            confirmButton = {
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            dueAt = combine(date, instantTime(dueBase, zone), zone)
                        }
                        showDueDate = false
                    },
                    modifier = Modifier.testTag("todo_due_date_confirm"),
                ) { Text("确定") }
            },
            dismissButton = {
                com.upcpocket.app.ui.components.CampusTextButton(onClick = { showDueDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showDueTime) {
        val dueBase = dueAt ?: currentLocalMinute(zone)
        val time = instantTime(dueBase, zone)
        val timeState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        com.upcpocket.app.ui.components.CampusAlertDialog(
            onDismissRequest = { showDueTime = false },
            title = { Text("选择截止时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = {
                        dueAt = combine(
                            instantDate(dueBase, zone),
                            LocalTime.of(timeState.hour, timeState.minute),
                            zone,
                        )
                        showDueTime = false
                    },
                    modifier = Modifier.testTag("todo_due_time_confirm"),
                ) { Text("确定") }
            },
            dismissButton = {
                com.upcpocket.app.ui.components.CampusTextButton(onClick = { showDueTime = false }) { Text("取消") }
            },
        )
    }

    if (showReminderDate) {
        val reminder = reminderAt ?: currentLocalMinute(zone)
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = utcDateMillis(reminder, zone),
        )
        DatePickerDialog(
            onDismissRequest = { showReminderDate = false },
            confirmButton = {
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            reminderAt = combine(date, instantTime(reminder, zone), zone)
                        }
                        showReminderDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                com.upcpocket.app.ui.components.CampusTextButton(onClick = { showReminderDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showReminderTime) {
        val reminder = reminderAt ?: currentLocalMinute(zone)
        val time = instantTime(reminder, zone)
        val timeState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        com.upcpocket.app.ui.components.CampusAlertDialog(
            onDismissRequest = { showReminderTime = false },
            title = { Text("选择提醒时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                com.upcpocket.app.ui.components.CampusTextButton(
                    onClick = {
                        reminderAt = combine(
                            instantDate(reminder, zone),
                            LocalTime.of(timeState.hour, timeState.minute),
                            zone,
                        )
                        showReminderTime = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                com.upcpocket.app.ui.components.CampusTextButton(onClick = { showReminderTime = false }) { Text("取消") }
            },
        )
    }
}

private fun currentLocalMinute(zone: ZoneId): Long =
    Instant.ofEpochMilli(System.currentTimeMillis())
        .atZone(zone)
        .withSecond(0)
        .withNano(0)
        .toInstant()
        .toEpochMilli()

private fun instantDate(epochMillis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

private fun instantTime(epochMillis: Long, zone: ZoneId): LocalTime =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()

private fun combine(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
    date.atTime(time).atZone(zone).toInstant().toEpochMilli()

private fun utcDateMillis(epochMillis: Long, zone: ZoneId): Long =
    instantDate(epochMillis, zone)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
