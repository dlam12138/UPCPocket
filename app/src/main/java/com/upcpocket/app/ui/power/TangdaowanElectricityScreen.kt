package com.upcpocket.app.ui.power

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.RefreshInvalidatedException
import com.upcpocket.app.data.power.ElectricityProtocol
import com.upcpocket.app.data.power.ElectricityResult
import com.upcpocket.app.widget.WidgetUpdates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 唐岛湾宿舍剩余电量（原生只读）。
 *
 * 流程只有一件事：选楼号 → 填房间号 → 查询结果。
 *
 * 输入（楼号＋房间号）通过 [AppGraph.powerInputRepository] 持久保存，进入页面和进程重启
 * 都会回填；保存与查询成功无关，查询失败或断网不会清空。用户改动输入时旧结果立即隐藏，
 * 结果卡片也自带楼号/房间号，不会把旧房间的电量挂到新输入下面。
 *
 * 楼号只来自学校页面下发的列表；加载失败时保留已保存的楼号，并提供“重新加载楼号”，
 * 不编造静态楼号，也不把选择器做成无法恢复的禁用按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TangdaowanElectricityScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val repository = AppGraph.powerReadingRepository
    val snapshot by repository.snapshot.collectAsStateWithLifecycle()
    val savedInput by AppGraph.powerInputRepository.input
        .collectAsStateWithLifecycle(initialValue = null)

    var hydrated by rememberSaveable { mutableStateOf(false) }
    var building by rememberSaveable { mutableStateOf("") }
    var room by rememberSaveable { mutableStateOf("") }
    var buildings by remember { mutableStateOf<List<String>>(emptyList()) }
    var buildingsLoading by remember { mutableStateOf(false) }
    var buildingsError by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    var loading by remember { mutableStateOf(false) }
    var systemNo by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<ElectricityProtocol.Candidate>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var requestId by remember { mutableIntStateOf(0) }

    fun persist(buildingValue: String = building, roomValue: String = room) {
        scope.launch { AppGraph.powerInputRepository.save(buildingValue, roomValue) }
    }

    /** 输入一变就作废旧结果，避免旧房间的电量被当成新输入的结果。 */
    fun invalidateResult() {
        requestId++
        systemNo = ""
        candidates = emptyList()
        error = null
    }

    fun selectBuilding(value: String) {
        if (value == building) {
            showPicker = false
            return
        }
        building = value
        showPicker = false
        invalidateResult()
        persist(buildingValue = value, roomValue = room)
    }

    fun changeRoom(value: String) {
        room = value
        invalidateResult()
        persist(buildingValue = building, roomValue = value)
    }

    fun clearInput() {
        building = ""
        room = ""
        showPicker = false
        invalidateResult()
        scope.launch {
            // 清除输入也清除“最后一次成功查询”，否则桌面组件会继续显示旧房间。
            repository.clear()
            AppGraph.powerInputRepository.clear()
            WidgetUpdates.requestPower(context)
        }
    }

    fun loadBuildings() {
        if (buildingsLoading) return
        buildingsLoading = true
        buildingsError = null
        scope.launch {
            try {
                buildings = AppGraph.tangdaowanElectricityService.buildings()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                buildingsError = failure.message ?: "无法加载学校楼号列表，请重试"
            } finally {
                buildingsLoading = false
            }
        }
    }

    // 第一次进入：优先回填持久输入；输入为空时用最后一次成功快照的房间，
    // 保证从桌面组件点进来能看到上次结果。用户已经改过输入时不覆盖。
    LaunchedEffect(savedInput, snapshot) {
        if (hydrated || building.isNotBlank() || room.isNotBlank()) return@LaunchedEffect
        val saved = savedInput
        if (saved != null && !saved.isEmpty) {
            building = saved.building
            room = saved.room
            hydrated = true
            return@LaunchedEffect
        }
        val last = snapshot ?: return@LaunchedEffect
        building = last.building
        room = last.room
        hydrated = true
    }
    LaunchedEffect(Unit) {
        if (buildings.isEmpty() && !buildingsLoading) loadBuildings()
    }
    LaunchedEffect(Unit) {
        // 先把上次成功快照读进共享 Flow，页面进入即可显示最近一次结果。
        repository.cached()
    }

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
        val id = ++requestId
        loading = true
        error = null
        candidates = emptyList()
        systemNo = ""
        scope.launch {
            try {
                val result = repository.query(selected, target)
                if (id != requestId || building != selected || room.trim() != target) return@launch
                when (result) {
                    is ElectricityResult.Success -> {
                        systemNo = result.reading.systemNo
                        // 成功分支已写入共享快照；立即把同一份值推给桌面组件。
                        WidgetUpdates.requestPower(context)
                    }
                    is ElectricityResult.Ambiguous -> {
                        candidates = result.candidates
                    }
                }
            } catch (_: RefreshInvalidatedException) {
                // 查询途中记录被清除：静默丢弃，不能让旧结果复活。
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (id == requestId && building == selected && room.trim() == target) {
                    error = e.message ?: "查询电费失败，请稍后重试"
                }
            } finally {
                if (id == requestId) loading = false
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择楼号") },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    when {
                        buildingsLoading -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp))
                            Text("正在加载学校楼号列表…")
                        }
                        buildingsError != null -> Column {
                            Text(
                                buildingsError.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = ::loadBuildings) { Text("重新加载楼号") }
                        }
                        buildings.isEmpty() -> Column {
                            Text("学校页面没有返回楼号列表。")
                            TextButton(onClick = ::loadBuildings) { Text("重新加载楼号") }
                        }
                        else -> LazyColumn {
                            items(buildings) { option ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { selectBuilding(option) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = building == option,
                                        onClick = { selectBuilding(option) },
                                    )
                                    Text(option)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("关闭") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电费 · 唐岛湾") },
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
                text = "选择楼号并填写房间号，查询学校返回的剩余电量。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column {
                OutlinedButton(
                    onClick = { showPicker = true },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        when {
                            building.isNotBlank() -> "楼号：$building"
                            buildingsLoading -> "正在加载楼号…"
                            else -> "选择楼号"
                        },
                        maxLines = 1,
                    )
                }
                when {
                    buildingsLoading -> Text(
                        "正在加载学校楼号列表…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                    buildingsError != null -> {
                        Text(
                            "楼号加载失败：${buildingsError.orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                        TextButton(onClick = ::loadBuildings) { Text("重新加载楼号") }
                    }
                    buildings.isEmpty() -> {
                        Text(
                            "尚未获取学校楼号列表。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                        TextButton(onClick = ::loadBuildings) { Text("重新加载楼号") }
                    }
                    else -> Unit
                }
                if (buildings.isNotEmpty() && building.isNotBlank() && building !in buildings) {
                    Text(
                        "上次保存的楼号不在学校当前列表里，请重新选择。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }

            OutlinedTextField(
                value = room,
                onValueChange = ::changeRoom,
                label = { Text("房间号") },
                singleLine = true,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("会记住这台设备的楼号与房间号；查询失败不会清空。清除会同时移除桌面组件的上次电量。")
                },
            )

            Button(
                onClick = ::runQuery,
                enabled = !loading && building.isNotBlank() && room.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (loading) "正在查询…" else "查询剩余电量")
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (building.isNotBlank() || room.isNotBlank()) {
                TextButton(onClick = ::clearInput, enabled = !loading) {
                    Text("清除楼号、房间号与查询记录")
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (it.contains("微信")) {
                    Text(
                        text = "学校页面要求微信授权时，App 不会绕过；请稍后重试或改用学校官方入口。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            snapshot?.takeIf { it.matches(building, room) }?.let { value ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = value.kwh + " 度",
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
                        if (systemNo.isNotBlank()) {
                            Text(
                                text = "系统编号 $systemNo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text(
                            text = "读取于 " + DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(value.fetchedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
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
                                changeRoom(candidate.room)
                                candidates = emptyList()
                            }) { Text("用这个房间号再查") }
                        }
                    }
                }
            }

            Text(
                text = "只查询剩余电量，不提供充值。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
