package com.upcpocket.app.ui.classroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomQuery
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
import com.upcpocket.app.data.classroom.PublicFreeClassroomRepository
import com.upcpocket.app.ui.components.GlassCard
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Native, read-only room availability. No WebView, reservation action or background polling. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FreeClassroomScreen(
    onBack: () -> Unit,
    repository: FreeClassroomRepository? = null,
) {
    val actualRepository = remember(repository) { repository ?: PublicFreeClassroomRepository() }
    val factory = remember(actualRepository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FreeClassroomViewModel(actualRepository) as T
        }
    }
    val model: FreeClassroomViewModel = viewModel(factory = factory)
    var buildingMenu by rememberSaveable { mutableStateOf(false) }
    val query = model.validQuery()
    val result = model.result
    val matchingResult = result?.takeIf { it.query == query }
    val buildings = result?.takeIf { it.query.campus == model.campus }?.buildings ?: listOf("全部")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("空闲教室") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                Text(
                    "查询学校公布的教室占用数据。所选节次全部未占用的教室才列为可用；临时调课与场地管理要求以现场为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("查询条件", fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FreeClassroomCampus.entries.forEach { campus ->
                                FilterChip(
                                    selected = model.campus == campus,
                                    onClick = { model.chooseCampus(campus) },
                                    enabled = !model.loading,
                                    label = { Text(campus.label) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        OutlinedTextField(
                            value = model.dateText,
                            onValueChange = { model.dateText = it; model.error = null },
                            label = { Text("日期（YYYY-MM-DD）") },
                            singleLine = true,
                            enabled = !model.loading,
                            isError = model.dateText.isNotBlank() && model.parsedDate() == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { model.moveDate(-1) }, enabled = !model.loading) { Text("前一天") }
                            TextButton(onClick = { model.dateText = LocalDate.now().toString(); model.error = null }, enabled = !model.loading) { Text("今天") }
                            TextButton(onClick = { model.moveDate(1) }, enabled = !model.loading) { Text("后一天") }
                        }
                        Column {
                            OutlinedButton(
                                onClick = { buildingMenu = true },
                                enabled = !model.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("教学楼：${model.building}") }
                            DropdownMenu(expanded = buildingMenu, onDismissRequest = { buildingMenu = false }) {
                                buildings.forEach { building ->
                                    DropdownMenuItem(
                                        text = { Text(building) },
                                        onClick = { model.building = building; model.error = null; buildingMenu = false },
                                    )
                                }
                            }
                        }
                        if (buildings.size == 1) {
                            Text("先查询全部教学楼，成功后可按楼筛选。", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("节次（可多选）", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..13).forEach { period ->
                                FilterChip(
                                    selected = period in model.periods,
                                    onClick = { model.togglePeriod(period) },
                                    enabled = !model.loading,
                                    label = { Text("$period") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        Text("这里使用学校空闲教室系统的 1–13 节。", style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { model.search() },
                            enabled = !model.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(if (model.loading) "正在查询…" else if (model.error != null) "重试查询" else "查询空闲教室") }
                    }
                }
            }
            if (model.loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            model.error?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    if (matchingResult == null) {
                        Text("当前没有这些条件下的有效数据，不能判断教室是否空闲。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (matchingResult == null) {
                item {
                    Text(
                        if (result == null) "选择条件后点击查询。" else "筛选条件已变更，请重新查询。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault()).format(matchingResult.fetchedAt)
                    Text(
                        when {
                            model.loading -> "正在更新，以下为上次成功结果（$time）"
                            model.error != null -> "以下为上次成功结果，未能更新（$time）"
                            else -> "查询成功 · $time"
                        },
                        color = if (model.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${matchingResult.query.campus.label} · ${matchingResult.query.date} · ${matchingResult.query.building} · " +
                            "第 ${matchingResult.query.periods.sorted().joinToString("、")} 节",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "可用 ${matchingResult.availableRooms.size} 间 / 共 ${matchingResult.rooms.size} 间",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (matchingResult.rooms.isEmpty()) {
                    item { Text("学校未返回该条件下的教室目录，不能解释为全部空闲。") }
                } else if (matchingResult.availableRooms.isEmpty()) {
                    item { Text("所选节次没有符合条件的空闲教室，可调整日期或节次后查询。") }
                }
                items(matchingResult.availableRooms, key = { it.code }) { room ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(room.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                room.building + (room.capacity?.let { " · 容量 $it 人" } ?: " · 容量未提供"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("所选节次未占用", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item {
                Text("数据来源：学校空闲教室页面。页面退出后不继续查询，不提供预约。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal class FreeClassroomViewModel(private val repository: FreeClassroomRepository) : ViewModel() {
    var campus by mutableStateOf(FreeClassroomCampus.TANGDAOWAN)
        private set
    var dateText by mutableStateOf(LocalDate.now().toString())
    var building by mutableStateOf("全部")
    var periods by mutableStateOf(setOf(1, 2))
        private set
    var result by mutableStateOf<FreeClassroomResult?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun parsedDate(): LocalDate? = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    fun validQuery(): FreeClassroomQuery? = parsedDate()?.let {
        FreeClassroomQuery(campus, it, building, periods).takeIf { periods.isNotEmpty() }
    }

    fun chooseCampus(value: FreeClassroomCampus) {
        if (campus != value) { campus = value; building = "全部"; error = null }
    }

    fun togglePeriod(value: Int) {
        periods = if (value in periods) periods - value else periods + value
        error = null
    }

    fun moveDate(days: Long) {
        dateText = (parsedDate() ?: LocalDate.now()).plusDays(days).toString()
        error = null
    }

    fun search() {
        if (loading) return
        val query = validQuery()
        if (query == null) {
            error = if (parsedDate() == null) "请填写有效日期，例如 2026-10-27" else "请至少选择一节课"
            return
        }
        loading = true
        error = null
        viewModelScope.launch {
            try {
                result = repository.query(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnknownHostException) {
                error = "无法连接学校服务，请检查网络后重试"
            } catch (_: SocketTimeoutException) {
                error = "学校服务响应超时，请稍后重试"
            } catch (failed: IOException) {
                error = failed.message ?: "查询失败，请检查网络后重试"
            } catch (_: Exception) {
                error = "查询失败，请检查网络或稍后重试"
            } finally {
                loading = false
            }
        }
    }
}
