package com.upcpocket.app.ui.power

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.power.ElectricityCampus
import com.upcpocket.app.data.power.ElectricityProtocol
import com.upcpocket.app.data.power.ElectricityReading
import com.upcpocket.app.data.power.ElectricityResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 唐岛湾宿舍剩余电量（原生只读）。
 *
 * 这是**按楼号＋房间号**的公开查询，没有登录态，所以界面不出现“已登录”字样；
 * 换房间就重新查，不做任何“记住房间号”的默认填充（楼号会记住，因为它是公共信息）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TangdaowanElectricityScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var buildings by remember { mutableStateOf<List<String>>(emptyList()) }
    var building by rememberSaveable { mutableStateOf("") }
    var room by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<ElectricityReading?>(null) }
    var fetchedAt by remember { mutableStateOf<Instant?>(null) }
    var candidates by remember { mutableStateOf<List<ElectricityProtocol.Candidate>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }

    fun runQuery() {
        if (loading) return
        val selected = building
        val target = room.trim()
        if (selected.isBlank()) {
            error = "请先选择楼号"
            return
        }
        if (target.isBlank()) {
            error = "请填写房间号"
            return
        }
        loading = true
        error = null
        candidates = emptyList()
        scope.launch {
            try {
                when (val result = AppGraph.tangdaowanElectricityService.query(selected, target)) {
                    is ElectricityResult.Success -> {
                        reading = result.reading
                        fetchedAt = Instant.now()
                        scope.launch { AppGraph.uiPrefsStore.setPowerBuilding(selected) }
                    }
                    is ElectricityResult.Ambiguous -> {
                        reading = null
                        fetchedAt = null
                        candidates = result.candidates
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                reading = null
                error = e.message ?: "查询电费失败，请稍后重试"
            } finally {
                loading = false
            }
        }
    }

    // key 必须是常量：若在 effect 内改自己的 key，协程会被取消，首屏就停在空状态。
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        building = AppGraph.uiPrefsStore.flow.first().powerBuilding
        scope.launch {
            runCatching { AppGraph.tangdaowanElectricityService.buildings() }
                .onSuccess { list ->
                    buildings = list
                    if (building.isBlank() || list.none { it == building }) {
                        building = list.firstOrNull().orEmpty()
                    }
                }
                .onFailure {
                    error = it.message ?: "学校电费页面暂时不可用，请稍后重试"
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("宿舍电费 · 唐岛湾") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "学校用电查询系统按“楼号＋房间号”返回数据，不需要登录。" +
                    "本页只查询剩余电量，不做充值、缴费或批量遍历房间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column {
                OutlinedButton(
                    onClick = { menu = true },
                    enabled = !loading && buildings.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        if (building.isBlank()) "选择楼号" else "楼号：$building",
                        maxLines = 1,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    buildings.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                building = option
                                menu = false
                                error = null
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = room,
                onValueChange = { room = it; error = null },
                label = { Text("房间号") },
                singleLine = true,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("按学校页面上的写法填，例如 1101。房间号不会被保存。")
                },
            )
            Button(
                onClick = { runQuery() },
                enabled = !loading && buildings.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (loading) "正在查询…" else if (error != null) "重试查询" else "查询剩余电量")
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (it.contains("微信")) {
                    Text(
                        text = "学校电费页面在没有 code 参数时会跳转到微信授权；本 App 使用学校页面" +
                            "自带的公开查询表单，如果这次仍被要求授权，说明学校调整了入口条件，暂不绕过。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            reading?.let { value ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = value.kwh.toPlainString() + " 度",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = listOf(value.building, value.room)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (value.systemNo.isNotBlank()) {
                            Text(
                                text = "系统编号 ${value.systemNo}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        fetchedAt?.let {
                            Text(
                                text = "读取于 " + DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                                    .withZone(ZoneId.systemDefault())
                                    .format(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            if (candidates.isNotEmpty()) {
                Text(
                    text = "该房间号对应多个系统编号，请确认是哪一个（学校页面也是先选后查）：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                candidates.forEach { candidate ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(candidate.room, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${candidate.building} · 系统编号 ${candidate.systemNo}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                room = candidate.room
                                error = null
                                candidates = emptyList()
                            }) { Text("用这个房间号再查") }
                        }
                    }
                }
            }

            Text(
                text = "数据来源：学校用电查询系统 /ShiYou/Default.aspx。字段名是“当前剩余电量”，" +
                    "单位为度；空调与照明是否分表以学校页面为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
