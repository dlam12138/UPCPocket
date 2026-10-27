package com.upcpocket.app.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.upcpocket.app.domain.Period

/**
 * 节次作息编辑对话框。
 *
 * 为什么要有它：本原型不再从教务拉作息，而**课表格子、今天页时间轴、桌面组件的
 * "下一节课"全部依赖每节课的起止时间**。没有这个入口，一旦默认作息和学校实际作息不同，
 * 整个时间显示就是错的 —— 那比没有功能更糟。
 *
 * 编辑规则：
 *  - 时间按 `HHmm` 四位数字填（0800 = 08:00），与数据层 `Period.startTime` 的口径一致；
 *  - 每行的结束时间必须晚于开始时间，否则这一行标红、整张表不能保存；
 *  - 节次数不在这里增删：网格与编辑器按固定节次排布，改节数会破坏已保存日程的语义。
 */
@Composable
fun PeriodsEditDialog(
    periods: List<Period>,
    onDismiss: () -> Unit,
    onSave: (List<Period>) -> Unit,
) {
    // 一份可变的编辑副本：key = indexNo，value = (start, end) 的文本形式
    val edits = remember(periods) {
        mutableStateMapOf<Int, Pair<String, String>>().apply {
            periods.forEach { put(it.indexNo, clock(it.startTime) to clock(it.endTime)) }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun validate(): List<Period>? {
        val out = ArrayList<Period>()
        for (p in periods.sortedBy { it.indexNo }) {
            val (sText, eText) = edits[p.indexNo] ?: (clock(p.startTime) to clock(p.endTime))
            val s = parseClock(sText)
            val e = parseClock(eText)
            if (s == null || e == null) {
                error = "第 ${p.indexNo} 节的时间格式不对，请填 HH:mm 或 HHmm"
                return null
            }
            if (e <= s) {
                error = "第 ${p.indexNo} 节的结束时间必须晚于开始时间"
                return null
            }
            out += p.copy(startTime = s, endTime = e)
        }
        error = null
        return out
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("节次时间") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "课表、今天页与桌面组件都按这里的时间显示。默认值只是常见排法（1–13 节），" +
                        "请改成你们专业的实际上课时间。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                periods.sortedBy { it.indexNo }.forEach { p ->
                    val pair = edits[p.indexNo] ?: (clock(p.startTime) to clock(p.endTime))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "第 ${p.indexNo} 节",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(52.dp),
                        )
                        ClockField(
                            value = pair.first,
                            label = "开始",
                            modifier = Modifier.weight(1f),
                        ) { v -> edits[p.indexNo] = v to pair.second }
                        ClockField(
                            value = pair.second,
                            label = "结束",
                            modifier = Modifier.weight(1f),
                        ) { v -> edits[p.indexNo] = pair.first to v }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { validate()?.let(onSave) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ClockField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onChange(raw.filter { it.isDigit() || it == ':' }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** 800 → "08:00"（与 `Period.startText` 同一口径，但容忍三位数写法） */
internal fun clock(v: Int): String {
    val s = v.coerceIn(0, 2359).toString().padStart(4, '0')
    return "${s.substring(0, 2)}:${s.substring(2)}"
}

/** "08:00" / "0800" / "800" → 800；解析失败返回 null */
internal fun parseClock(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val (h, m) = if (t.contains(':')) {
        val parts = t.split(':')
        if (parts.size != 2) return null
        (parts[0].toIntOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
    } else {
        val digits = t.filter { it.isDigit() }
        when (digits.length) {
            3 -> digits.take(1).toInt() to digits.drop(1).toInt()
            4 -> digits.take(2).toInt() to digits.drop(2).toInt()
            else -> return null
        }
    }
    if (h !in 0..23 || m !in 0..59) return null
    return h * 100 + m
}
