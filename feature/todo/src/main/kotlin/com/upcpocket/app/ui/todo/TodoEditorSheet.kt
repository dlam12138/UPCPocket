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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.upcpocket.app.todo.TodoStore
import com.upcpocket.app.todo.data.TodoFormat
import com.upcpocket.app.todo.data.TodoTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * 新增/编辑待办底部面板。
 *
 * 新增模式回车保存后保留面板并清空输入，便于连续添加；编辑模式回车保存并关闭。
 * 提醒时间默认当前系统本地时间，明确显示；空白标题不可保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditorSheet(
    store: TodoStore,
    initialTask: TodoTask?,
    onDismiss: () -> Unit,
    onDeleted: (TodoTask) -> Unit = {},
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var title by remember(initialTask?.id) { mutableStateOf(initialTask?.title.orEmpty()) }
    var reminderEnabled by remember(initialTask?.id) {
        mutableStateOf(initialTask?.reminderAt != null)
    }
    var reminderAt by remember(initialTask?.id) {
        mutableStateOf(initialTask?.reminderAt ?: defaultReminderNow(zone))
    }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var access by remember { mutableStateOf(store.reminderAccess()) }

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
        if (trimmed.isEmpty() || saving) return
        saving = true
        errorText = null
        scope.launch {
            runCatching {
                if (initialTask == null) {
                    store.add(trimmed, reminderAt.takeIf { reminderEnabled })
                } else {
                    store.edit(initialTask.id, trimmed, reminderAt.takeIf { reminderEnabled })
                }
            }.onSuccess {
                if (initialTask == null && keepOpen) {
                    title = ""
                    reminderEnabled = false
                    reminderAt = defaultReminderNow(zone)
                    saving = false
                    runCatching { focusRequester.requestFocus() }
                    keyboard?.show()
                } else {
                    saving = false
                    onDismiss()
                }
            }.onFailure {
                saving = false
                errorText = "保存失败，请重试"
            }
        }
    }

    fun toggleReminder(enabled: Boolean) {
        reminderEnabled = enabled
        if (!enabled) return
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
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
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
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (title.trim().isNotEmpty() && !saving) {
                            save(keepOpen = initialTask == null)
                        }
                    },
                ),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("提醒", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (reminderEnabled) TodoFormat.editorTime(reminderAt, zone) else "不提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = reminderEnabled,
                    onCheckedChange = ::toggleReminder,
                    modifier = Modifier.testTag("todo_reminder_switch"),
                )
            }

            if (reminderEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDate = true }) {
                        Text(TodoFormat.date(reminderAt, zone))
                    }
                    OutlinedButton(onClick = { showTime = true }) {
                        Text(TodoFormat.clock(reminderAt, zone))
                    }
                }
                if (reminderAt <= System.currentTimeMillis()) {
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
                    TextButton(
                        onClick = {
                            if (saving) return@TextButton
                            saving = true
                            scope.launch {
                                store.delete(initialTask.id)?.let(onDeleted)
                                saving = false
                                onDismiss()
                            }
                        },
                        enabled = !saving,
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                Column(Modifier.weight(1f)) {}
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                Button(
                    onClick = { save(keepOpen = false) },
                    enabled = title.trim().isNotEmpty() && !saving,
                    modifier = Modifier.testTag("todo_save"),
                ) {
                    Text("保存")
                }
            }
        }
    }

    if (showDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = utcDateMillis(reminderAt, zone),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            reminderAt = combine(date, instantTime(reminderAt, zone), zone)
                        }
                        showDate = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTime) {
        val time = instantTime(reminderAt, zone)
        val timeState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("选择提醒时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        reminderAt = combine(
                            instantDate(reminderAt, zone),
                            LocalTime.of(timeState.hour, timeState.minute),
                            zone,
                        )
                        showTime = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text("取消") }
            },
        )
    }
}

private fun defaultReminderNow(zone: ZoneId): Long =
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
