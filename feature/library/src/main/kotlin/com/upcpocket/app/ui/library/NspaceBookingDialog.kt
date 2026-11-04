package com.upcpocket.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.data.nspace.NspaceFormField
import com.upcpocket.app.data.nspace.NspaceRoom
import com.upcpocket.app.library.NspaceService

/**
 * nspace 原生预约表单。
 *
 * 只有学校详情/表单规则完整、单人且不选座/不要求附件时才允许提交；提交前显示真实
 * 预约确认。测试和模拟器验收不点击最后确认，因此不会创建真实预约。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NspaceBookingDialog(
    service: NspaceService,
    room: NspaceRoom,
    date: String,
    onDismiss: () -> Unit,
    onOpenOfficial: () -> Unit,
) {
    val vm: NspaceBookingViewModel = viewModel(
        key = "nspace-booking-${room.id}-$date",
        factory = NspaceBookingViewModel.factory(service, room.id, date),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("预约 ${room.name}")
                                Text(
                                    date,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.busy) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.height(20.dp))
                            Text("正在读取学校预约规则…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::load) { Text("重新读取规则") }
                    }
                    state.eligibilityReason?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = onOpenOfficial) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.padding(start = 4.dp))
                            Text("改在官方页面预约")
                        }
                    }
                    if (state.success) {
                        Text(
                            "学校已接受预约请求。请在“我的预约”核对；页面不把 HTTP 200 当座位已可使用。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Button(onClick = onDismiss) { Text("关闭") }
                    } else if (!state.busy && state.detail != null) {
                        Text("房间：${state.detail!!.name}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "开放 ${bookingTime(state.detail!!.openTime)}–" +
                                "${bookingTime(state.detail!!.closeTime)} · " +
                                "最短 ${state.detail!!.minTime} 分钟 / 最长 ${state.detail!!.maxTime} 分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "人数：${state.detail!!.minUser}–${state.detail!!.maxUser} 人；" +
                                "多人房间（如 A123）需要组队，请使用官方页面。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SectionLabel("使用类型")
                        val selectedType = state.useTypes.firstOrNull { it.id == state.selectedUseType }
                        DropdownChoice(
                            text = selectedType?.name ?: "请选择",
                            options = state.useTypes.map { it.id to it.name },
                            onSelect = vm::selectUseType,
                        )

                        SectionLabel("时间")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DropdownChoice(
                                text = state.start?.let(::bookingTime) ?: "开始",
                                options = state.startOptions.map { it.toString() to bookingTime(it) },
                                onSelect = { raw -> raw.toIntOrNull()?.let(vm::selectStart) },
                                modifier = Modifier.weight(1f),
                            )
                            DropdownChoice(
                                text = state.end?.let(::bookingTime) ?: "结束",
                                options = state.endOptions.map { it.toString() to bookingTime(it) },
                                onSelect = { raw -> raw.toIntOrNull()?.let(vm::selectEnd) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "可预约时间以学校返回的开放时段和不可预约时段为准；" +
                                "确认前会再次检查人数、时长和必填项。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SectionLabel("学校必填项")
                        if (state.fields.isEmpty()) {
                            Text(
                                "学校未返回额外必填项。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.fields.forEach { field ->
                            FormField(field, state.values[field.field].orEmpty(), vm::updateField)
                        }

                        Text(
                            "提交后会向学校创建真实预约；确认前不会发送。参与人/团队、选座、" +
                                "附件或未知表单类型不会在此伪造。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = { confirm = true },
                            enabled = !state.submitting && state.eligibilityReason == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.submitting) "正在提交…" else "提交真实预约")
                        }
                        TextButton(onClick = onOpenOfficial, modifier = Modifier.fillMaxWidth()) {
                            Text("改用官方页面")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认创建真实预约？") },
            text = {
                Text(
                    "房间：${room.name}\n" +
                        "日期：$date\n" +
                        "时间：${state.start?.let(::bookingTime)}–${state.end?.let(::bookingTime)}\n\n" +
                        "提交后会调用学校预约接口；本操作不是演示。",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("暂不提交") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        vm.submit()
                    },
                    enabled = !state.submitting,
                ) { Text("确认提交") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DropdownChoice(
    text: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}


@Composable
private fun FormField(
    field: NspaceFormField,
    value: String,
    onChange: (String, String) -> Unit,
) {
    val selectLike = field.type == "SELEC" || field.type == "RADIO"
    val label = field.label + if (field.required) " *" else ""
    if (field.type == "CHECKBOX" && field.options.isNotEmpty()) {
        val selected = value.split(',').filter { it.isNotBlank() }.toSet()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label)
            field.options.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = option.value in selected,
                        onCheckedChange = { checked ->
                            val next = if (checked) selected + option.value
                            else selected - option.value
                            onChange(field.field, next.joinToString(","))
                        },
                    )
                    Text(option.name)
                }
            }
        }
    } else if (selectLike && field.options.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        val selected = field.options.firstOrNull { it.value == value }?.name ?: "请选择"
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$label：$selected")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                field.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            open = false
                            onChange(field.field, option.value)
                        },
                    )
                }
            }
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(field.field, it) },
            label = { Text(label) },
            singleLine = field.type != "TEXTAREA",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun bookingTime(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
