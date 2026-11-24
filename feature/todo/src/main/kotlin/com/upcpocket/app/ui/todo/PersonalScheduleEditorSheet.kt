package com.upcpocket.app.ui.todo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.upcpocket.app.todo.PersonalSchedule
import com.upcpocket.app.todo.PersonalScheduleStore
import com.upcpocket.app.todo.data.PersonalScheduleTime
import com.upcpocket.app.ui.components.CampusAlertDialog
import com.upcpocket.app.ui.components.CampusButton
import com.upcpocket.app.ui.components.CampusOutlinedButton
import com.upcpocket.app.ui.components.CampusTextButton
import com.upcpocket.app.ui.components.GlassDatePicker as DatePicker
import com.upcpocket.app.ui.components.GlassDatePickerDialog as DatePickerDialog
import com.upcpocket.app.ui.components.GlassModalBottomSheet as ModalBottomSheet
import com.upcpocket.app.ui.components.GlassOutlinedTextField as OutlinedTextField
import com.upcpocket.app.ui.components.GlassTimePicker as TimePicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新增/编辑个人日程。
 *
 * 开始与结束一律按上海时间填写、按上海时间回显；设备时区只影响列表的日期投影，
 * 不改变本表单的字段。上海时间不存在（夏令时跳变）或不唯一（回拨重叠）时不生成
 * 时点，保存按钮保持禁用并给出原因。保存/删除失败或 revision 已过期时保留全部
 * 草稿，由用户重试或关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalScheduleEditorSheet(
    store: PersonalScheduleStore,
    initial: PersonalSchedule?,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val initialStart = initial?.let { PersonalScheduleTime.shanghaiDateTime(it.startAt) }
    val initialEnd = initial?.let { PersonalScheduleTime.shanghaiDateTime(it.endAt) }
    val stateKey = initial?.id ?: "new:${initialDate.toEpochDay()}"

    var title by rememberSaveable(stateKey) { mutableStateOf(initial?.title.orEmpty()) }
    var startEpochDay by rememberSaveable(stateKey) {
        mutableLongStateOf((initialStart?.toLocalDate() ?: initialDate).toEpochDay())
    }
    var startHour by rememberSaveable(stateKey) {
        mutableIntStateOf(initialStart?.hour ?: DEFAULT_START_HOUR)
    }
    var startMinute by rememberSaveable(stateKey) {
        mutableIntStateOf(initialStart?.minute ?: 0)
    }
    var endEpochDay by rememberSaveable(stateKey) {
        mutableLongStateOf((initialEnd?.toLocalDate() ?: initialDate).toEpochDay())
    }
    var endHour by rememberSaveable(stateKey) {
        mutableIntStateOf(initialEnd?.hour ?: DEFAULT_END_HOUR)
    }
    var endMinute by rememberSaveable(stateKey) {
        mutableIntStateOf(initialEnd?.minute ?: 0)
    }
    var location by rememberSaveable(stateKey) { mutableStateOf(initial?.location.orEmpty()) }
    var note by rememberSaveable(stateKey) { mutableStateOf(initial?.note.orEmpty()) }
    val expectedRevision by rememberSaveable(stateKey) {
        mutableIntStateOf(initial?.revision ?: 0)
    }
    var showStartDate by rememberSaveable(stateKey) { mutableStateOf(false) }
    var showStartTime by rememberSaveable(stateKey) { mutableStateOf(false) }
    var showEndDate by rememberSaveable(stateKey) { mutableStateOf(false) }
    var showEndTime by rememberSaveable(stateKey) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(stateKey) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val startLocal = LocalDate.ofEpochDay(startEpochDay)
        .atTime(startHour, startMinute)
    val endLocal = LocalDate.ofEpochDay(endEpochDay)
        .atTime(endHour, endMinute)
    val startAt = PersonalScheduleTime.resolve(startLocal)
    val endAt = PersonalScheduleTime.resolve(endLocal)

    val timeError = when {
        startAt == null -> "开始时间在上海时间中不存在或不唯一，请改选。"
        endAt == null -> "结束时间在上海时间中不存在或不唯一，请改选。"
        endAt <= startAt -> "结束时间须晚于开始时间。"
        else -> null
    }
    val canSave = title.trim().isNotEmpty() &&
        startAt != null &&
        endAt != null &&
        endAt > startAt &&
        !saving

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    fun persist() {
        // canSave 是组合时快照；同一帧的第二次点击必须直接看当前 saving，避免重复新建。
        if (saving) return
        if (!canSave) return
        val trimmedTitle = title.trim()
        saving = true
        errorText = null
        scope.launch {
            try {
                val saved = if (initial == null) {
                    store.add(
                        title = trimmedTitle,
                        startAt = startAt,
                        endAt = endAt,
                        location = location,
                        note = note,
                    )
                } else {
                    store.update(
                        id = initial.id,
                        expectedRevision = expectedRevision,
                        title = trimmedTitle,
                        startAt = startAt,
                        endAt = endAt,
                        location = location,
                        note = note,
                    )
                }
                if (saved == null) {
                    saving = false
                    errorText = "日程已变化，请重新打开后再保存"
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

    fun delete() {
        val current = initial ?: return
        if (saving) return
        saving = true
        errorText = null
        scope.launch {
            // 删除回流可能让宿主提前离开组合并取消本面板作用域，删除本身必须完成。
            withContext(NonCancellable) {
                try {
                    val deleted = store.delete(current.id, expectedRevision)
                    if (deleted == null) {
                        saving = false
                        errorText = "日程已变化，未能删除"
                    } else {
                        saving = false
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
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
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
                if (initial == null) "新增日程" else "编辑日程",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "开始、结束均按上海时间填写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 200) title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("schedule_title_input"),
                enabled = !saving,
                label = { Text("标题") },
                colors = scheduleFieldColors(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { persist() }),
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("开始", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampusOutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            showStartDate = true
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("schedule_start_date"),
                    ) {
                        Text(PersonalSchedulePresentation.dateLabel(startLocal.toLocalDate()))
                    }
                    CampusOutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            showStartTime = true
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("schedule_start_time"),
                    ) {
                        Text(clockLabel(startHour, startMinute))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("结束", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampusOutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            showEndDate = true
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("schedule_end_date"),
                    ) {
                        Text(PersonalSchedulePresentation.dateLabel(endLocal.toLocalDate()))
                    }
                    CampusOutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            showEndTime = true
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("schedule_end_time"),
                    ) {
                        Text(clockLabel(endHour, endMinute))
                    }
                }
            }

            timeError?.let {
                Text(
                    it,
                    modifier = Modifier.testTag("schedule_time_error"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = location,
                onValueChange = { if (it.length <= 200) location = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("schedule_location_input"),
                enabled = !saving,
                label = { Text("地点（可选）") },
                colors = scheduleFieldColors(),
                singleLine = true,
            )

            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 500) note = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("schedule_note_input"),
                enabled = !saving,
                label = { Text("备注（可选）") },
                colors = scheduleFieldColors(),
                minLines = 2,
                maxLines = 4,
            )

            errorText?.let {
                Text(
                    it,
                    modifier = Modifier.testTag("schedule_error"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (initial != null) {
                    CampusTextButton(
                        onClick = {
                            focusManager.clearFocus()
                            confirmDelete = true
                        },
                        enabled = !saving,
                        contentColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("schedule_delete"),
                    ) {
                        Text("删除")
                    }
                }
                Column(Modifier.weight(1f)) {}
                CampusTextButton(
                    onClick = onDismiss,
                    enabled = !saving,
                    modifier = Modifier.testTag("schedule_cancel"),
                ) { Text("取消") }
                CampusButton(
                    onClick = ::persist,
                    enabled = canSave,
                    modifier = Modifier.testTag("schedule_save"),
                ) {
                    Text("保存")
                }
            }
        }
    }

    if (showStartDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = utcDateMillis(startLocal.toLocalDate()),
        )
        DatePickerDialog(
            onDismissRequest = { showStartDate = false },
            confirmButton = {
                CampusTextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            startEpochDay = selectedUtcDate(millis).toEpochDay()
                        }
                        showStartDate = false
                    },
                    modifier = Modifier.testTag("schedule_start_date_confirm"),
                ) { Text("确定") }
            },
            dismissButton = {
                CampusTextButton(onClick = { showStartDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showStartTime) {
        val timeState = rememberTimePickerState(
            initialHour = startHour,
            initialMinute = startMinute,
            is24Hour = true,
        )
        CampusAlertDialog(
            onDismissRequest = { showStartTime = false },
            title = { Text("选择开始时间（上海时间）") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                CampusTextButton(
                    onClick = {
                        startHour = timeState.hour
                        startMinute = timeState.minute
                        showStartTime = false
                    },
                    modifier = Modifier.testTag("schedule_start_time_confirm"),
                ) { Text("确定") }
            },
            dismissButton = {
                CampusTextButton(onClick = { showStartTime = false }) { Text("取消") }
            },
        )
    }

    if (showEndDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = utcDateMillis(endLocal.toLocalDate()),
        )
        DatePickerDialog(
            onDismissRequest = { showEndDate = false },
            confirmButton = {
                CampusTextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            endEpochDay = selectedUtcDate(millis).toEpochDay()
                        }
                        showEndDate = false
                    },
                    modifier = Modifier.testTag("schedule_end_date_confirm"),
                ) { Text("确定") }
            },
            dismissButton = {
                CampusTextButton(onClick = { showEndDate = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showEndTime) {
        val timeState = rememberTimePickerState(
            initialHour = endHour,
            initialMinute = endMinute,
            is24Hour = true,
        )
        CampusAlertDialog(
            onDismissRequest = { showEndTime = false },
            title = { Text("选择结束时间（上海时间）") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                CampusTextButton(
                    onClick = {
                        endHour = timeState.hour
                        endMinute = timeState.minute
                        showEndTime = false
                    },
                    modifier = Modifier.testTag("schedule_end_time_confirm"),
                ) { Text("确定") }
            },
            dismissButton = {
                CampusTextButton(onClick = { showEndTime = false }) { Text("取消") }
            },
        )
    }

    if (confirmDelete && initial != null) {
        CampusAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除日程") },
            text = { Text("删除后无法恢复，确定删除「${initial.title}」？") },
            confirmButton = {
                CampusTextButton(
                    onClick = {
                        confirmDelete = false
                        delete()
                    },
                    contentColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("schedule_delete_confirm"),
                ) { Text("删除") }
            },
            dismissButton = {
                CampusTextButton(
                    onClick = { confirmDelete = false },
                    modifier = Modifier.testTag("schedule_delete_cancel"),
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun scheduleFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
)

private const val DEFAULT_START_HOUR = 9
private const val DEFAULT_END_HOUR = 10

private fun clockLabel(hour: Int, minute: Int): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

private fun utcDateMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun selectedUtcDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
