package com.upcpocket.app.ui.ecard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import com.upcpocket.app.ui.components.GlassTextButton as TextButton
import com.upcpocket.app.ui.components.GlassIconButton as IconButton
import com.upcpocket.app.ui.components.GlassAlertDialog as AlertDialog

@Composable
internal fun CardHistoryMonthDialog(
    initialMonth: YearMonth,
    onDismiss: () -> Unit,
    onConfirm: (YearMonth) -> Unit,
) {
    var year by rememberSaveable { mutableIntStateOf(initialMonth.year.coerceIn(2000, 9999)) }
    var month by rememberSaveable { mutableIntStateOf(initialMonth.monthValue) }
    var choosingYear by rememberSaveable { mutableStateOf(false) }

    com.upcpocket.app.ui.components.CampusAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择月份") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year-- }, enabled = year > 2000, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一年")
                    }
                    com.upcpocket.app.ui.components.CampusTextButton(
                        onClick = { choosingYear = !choosingYear },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("${year}年") }
                    IconButton(onClick = { year++ }, enabled = year < 9999, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一年")
                    }
                }
                if (choosingYear) {
                    val years = rememberLazyListState(initialFirstVisibleItemIndex = year - 2000)
                    LazyColumn(Modifier.fillMaxWidth().height(240.dp), state = years) {
                        items(count = 8000, key = { it + 2000 }) { index ->
                            val value = index + 2000
                            com.upcpocket.app.ui.components.CampusTextButton(
                                onClick = { year = value; choosingYear = false },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .semantics { selected = value == year },
                            ) { Text("${value}年") }
                        }
                    }
                } else {
                    repeat(4) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { column ->
                                val value = row * 3 + column + 1
                                val isSelected = value == month
                                val btnModifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .semantics { selected = isSelected }
                                val btnPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                if (isSelected) {
                                    com.upcpocket.app.ui.components.CampusTonalButton(
                                        onClick = { month = value },
                                        modifier = btnModifier,
                                        contentPadding = btnPadding,
                                    ) { Text("${value}月") }
                                } else {
                                    com.upcpocket.app.ui.components.CampusOutlinedButton(
                                        onClick = { month = value },
                                        modifier = btnModifier,
                                        contentPadding = btnPadding,
                                    ) { Text("${value}月") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { com.upcpocket.app.ui.components.CampusTextButton(onClick = { onConfirm(YearMonth.of(year, month)) }) { Text("确定") } },
        dismissButton = { com.upcpocket.app.ui.components.CampusTextButton(onClick = onDismiss) { Text("取消") } },
    )
}
