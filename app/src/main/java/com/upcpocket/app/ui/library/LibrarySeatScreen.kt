package com.upcpocket.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.upcpocket.app.core.AppGraph
import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryCaptchaRequiredException
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryReservation
import com.upcpocket.app.data.library.LibraryReservationMatch
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatException
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySessionExpiredException
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.ui.components.GlassCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 原生座位预约：登录走官方网页，查询、选座、确认、取消都在 App 内完成。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySeatScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
) {
    val repo = AppGraph.librarySeatRepository
    val session by repo.session.collectAsStateWithLifecycle()
    val factory = remember(repo) {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibrarySeatViewModel(repo) as T
        }
    }
    val vm: LibrarySeatViewModel = viewModel(factory = factory)
    val state = vm.state
    LaunchedEffect(session) {
        if (session != null) vm.ensureLoaded()
    }
    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) vm.logout()
    }

    var buildingMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }

    if (session == null) {
        LibraryLoginGate(onBack = onBack, onLogin = onLogin)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图书馆座位") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.logout() }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "退出登录")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "已通过学校统一认证登录：${session?.user?.name.orEmpty()}。查询、选座和取消都由你主动操作；" +
                        "不自动抢座、不后台轮询。预约结果以学校返回和“我的预约”为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::clearError) { Text("知道了") }
                }
            }
            state.notice?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = vm::clearNotice) { Text("知道了") }
                }
            }

            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("查询条件", fontWeight = FontWeight.SemiBold)
                        val filters = state.filters
                        if (filters == null) {
                            Text("正在读取馆区与日期…", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = vm::ensureLoaded, enabled = !state.loading) { Text("重新读取") }
                        } else {
                            Box {
                                OutlinedButton(
                                    onClick = { buildingMenu = true },
                                    enabled = !state.loading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("馆区：${state.building?.name ?: "请选择"}") }
                                DropdownMenu(expanded = buildingMenu, onDismissRequest = { buildingMenu = false }) {
                                    filters.buildings.forEach { building ->
                                        DropdownMenuItem(
                                            text = { Text(building.name) },
                                            onClick = {
                                                vm.selectBuilding(building)
                                                buildingMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                            Box {
                                OutlinedButton(
                                    onClick = { dateMenu = true },
                                    enabled = !state.loading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("日期：${state.date ?: "请选择"}") }
                                DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                                    filters.dates.forEach { date ->
                                        DropdownMenuItem(
                                            text = { Text(date) },
                                            onClick = {
                                                vm.selectDate(date)
                                                dateMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = vm::queryRooms,
                                enabled = !state.loading && state.building != null && state.date != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("查询房间") }
                        }
                    }
                }
            }

            state.layout?.let { layout ->
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "座位图 · ${state.room?.name.orEmpty()}",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "灰色不可选，绿色可用，橙色暂离，红色使用中/已预约。点选可用座位后选择时间。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SeatMap(
                                layout = layout,
                                selectedId = state.seat?.id,
                                onSelect = vm::chooseSeat,
                            )
                        }
                    }
                }
            }

            if (state.rooms.isNotEmpty()) {
                item {
                    Text(
                        "房间（${state.rooms.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(state.rooms, key = { it.roomId }) { room ->
                    RoomCard(
                        room = room,
                        selected = state.room?.roomId == room.roomId,
                        onClick = { vm.openRoom(room) },
                    )
                }
            } else if (state.filters != null && !state.loading) {
                item {
                    Text(
                        "选择馆区和日期后查询房间；没有查询结果时不显示空房间列表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("我的预约", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = vm::refreshReservations, enabled = !state.loading) {
                        Text("刷新")
                    }
                }
            }
            if (state.reservations.isEmpty()) {
                item {
                    Text(
                        "当前没有预约记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.reservations, key = { it.id }) { reservation ->
                    ReservationCard(reservation, enabled = !state.loading, onCancel = { vm.cancel(reservation) })
                }
            }
            item {
                Text(
                    "数据来源：libspace.upc.edu.cn 座位预约系统的正常登录与接口。登录 token 只在本次运行内存中，" +
                        "退出登录会清除；不保存密码、Cookie 或预约历史到本地数据库。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    state.seat?.let { seat ->
        val start = state.start
        val end = state.end
        if (state.showTimeDialog) {
            AlertDialog(
                onDismissRequest = { vm.closeTimeDialog() },
                title = { Text("选择时间 · ${seat.name}") },
                text = {
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("开始时间", style = MaterialTheme.typography.labelLarge)
                        val starts = state.startTimes
                        if (starts.isEmpty()) {
                            Text("该座位当天没有可预约的开始时间。", color = MaterialTheme.colorScheme.error)
                        } else {
                            starts.forEach { option ->
                                FilterChip(
                                    selected = start?.id == option.id,
                                    onClick = { vm.chooseStart(option) },
                                    label = { Text(option.value) },
                                )
                            }
                        }
                        Text("结束时间", style = MaterialTheme.typography.labelLarge)
                        if (start != null) {
                            val ends = state.endTimes
                            if (ends.isEmpty()) {
                                Text("该开始时间没有可选的结束时间。", color = MaterialTheme.colorScheme.error)
                            } else {
                                ends.forEach { option ->
                                    FilterChip(
                                        selected = end?.id == option.id,
                                        onClick = { vm.chooseEnd(option) },
                                        label = { Text(option.value) },
                                    )
                                }
                            }
                        } else {
                            Text("先选择开始时间。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = start != null && end != null && !state.loading,
                        onClick = { vm.requestReservation() },
                    ) { Text("下一步") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.closeTimeDialog() }) { Text("取消") }
                },
            )
        }
        if (state.showConfirm) {
            AlertDialog(
                onDismissRequest = { vm.dismissConfirm() },
                title = { Text("确认预约") },
                text = {
                    Text(
                        "${state.room?.name.orEmpty()} · ${seat.name}\n" +
                            "${state.date.orEmpty()} ${start?.value.orEmpty()}–${end?.value.orEmpty()}\n\n" +
                            "提交后由学校系统处理；本应用不会自动重试或抢座。",
                    )
                },
                confirmButton = {
                    TextButton(enabled = !state.loading, onClick = { vm.confirmReservation() }) {
                        Text("确认提交")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissConfirm() }) { Text("返回") }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryLoginGate(onBack: () -> Unit, onLogin: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图书馆座位") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("需要学校统一认证", style = MaterialTheme.typography.titleLarge)
            Text(
                "登录由学校 CAS 页面完成，App 不保存密码。登录后才能在原生界面查询馆区、选座和取消预约。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("使用学校统一认证登录") }
            Text(
                "如果学校启用了图形验证码，原生提交会明确提示并停止；不会伪造预约成功。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoomCard(room: LibraryRoom, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(room.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (room.floor > 0) "楼层 ${room.floor}" else "楼层未提供",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(if (selected) "已打开" else "查看座位", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ReservationCard(
    reservation: LibraryReservation,
    enabled: Boolean,
    onCancel: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(reservation.location.ifBlank { "座位" }, fontWeight = FontWeight.SemiBold)
                Text(reservation.statusLabel, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${reservation.date} · ${reservation.begin}–${reservation.end}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (reservation.cancellable) {
                TextButton(enabled = enabled, onClick = onCancel) { Text("取消预约") }
            }
        }
    }
}

@Composable
private fun SeatMap(
    layout: LibrarySeatLayout,
    selectedId: String?,
    onSelect: (LibrarySeat) -> Unit,
) {
    val byPosition = remember(layout) { layout.seats.associateBy { it.row to it.col } }
    val cell = 34.dp
    val h = rememberScrollState()
    val v = rememberScrollState()
    Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(v)) {
        Column(Modifier.horizontalScroll(h)) {
            for (row in 0 until layout.rows) {
                Row {
                    for (col in 0 until layout.cols) {
                        val seat = byPosition[row to col]
                        if (seat == null) {
                            Spacer(Modifier.size(cell))
                        } else {
                            SeatCell(
                                seat = seat,
                                selected = seat.id == selectedId,
                                cell = cell,
                                onClick = { onSelect(seat) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatCell(
    seat: LibrarySeat,
    selected: Boolean,
    cell: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val color = when {
        selected -> MaterialTheme.colorScheme.primary
        !seat.enabled || seat.localOnly -> MaterialTheme.colorScheme.surfaceVariant
        seat.status == "FREE" || seat.status == "YESSELECT" -> Color(0xFF81C784)
        seat.status == "AWAY" -> Color(0xFFFFB74D)
        seat.status == "IN_USE" || seat.status == "BOOKED" || seat.status == "FULL" -> Color(0xFFE57373)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        Modifier.size(cell).padding(1.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .clickable(enabled = seat.selectable, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seat.name.takeLast(4),
            fontSize = 7.sp,
            lineHeight = 8.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal class LibrarySeatViewModel(private val repo: LibrarySeatRepository) : ViewModel() {
    data class UiState(
        val filters: LibraryFilters? = null,
        val building: LibraryBuilding? = null,
        val date: String? = null,
        val rooms: List<LibraryRoom> = emptyList(),
        val room: LibraryRoom? = null,
        val layout: LibrarySeatLayout? = null,
        val seat: LibrarySeat? = null,
        val startTimes: List<LibraryTimeOption> = emptyList(),
        val endTimes: List<LibraryTimeOption> = emptyList(),
        val start: LibraryTimeOption? = null,
        val end: LibraryTimeOption? = null,
        val reservations: List<LibraryReservation> = emptyList(),
        /** 最近一次成功读取“我的预约”的时间；失败时保留它，界面据此标注旧数据。 */
        val reservationsUpdatedAt: Long? = null,
        val reservationsError: String? = null,
        /** 已提交取消、但最终状态尚未核对的预约 id。 */
        val pendingCancelIds: Set<String> = emptySet(),
        val reservationsBusy: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        val sessionExpired: Boolean = false,
        val showTimeDialog: Boolean = false,
        val showConfirm: Boolean = false,
    )

    var state by mutableStateOf(UiState())
        private set

    fun ensureLoaded() {
        if (state.filters != null || state.loading) return
        loadFilters()
    }

    fun loadFilters() {
        run {
            state = state.copy(loading = true, error = null)
            viewModelScope.launch {
                try {
                    val filters = repo.filters()
                    val building = state.building ?: filters.buildings.first()
                    val date = state.date ?: filters.dates.first()
                    state = state.copy(
                        filters = filters,
                        building = building,
                        date = date,
                        loading = false,
                    )
                    refreshReservations()
                    queryRooms()
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }
    }

    fun selectBuilding(value: LibraryBuilding) {
        state = state.copy(
            building = value,
            rooms = emptyList(),
            room = null,
            layout = null,
            seat = null,
            error = null,
        )
    }

    fun selectDate(value: String) {
        state = state.copy(
            date = value,
            rooms = emptyList(),
            room = null,
            layout = null,
            seat = null,
            error = null,
        )
    }

    fun queryRooms() {
        val building = state.building ?: return
        val date = state.date ?: return
        state = state.copy(loading = true, error = null, rooms = emptyList(), room = null, layout = null, seat = null)
        viewModelScope.launch {
            try {
                val floors = runCatching { repo.floors(building.id) }.getOrDefault(emptyList())
                val names = floors.associate { it.floorNum to it.floorName }
                val rooms = repo.rooms(building.id, date)
                    .map { if (it.floorName == null) it.copy(floorName = names[it.floor]) else it }
                    .sortedWith(compareBy({ it.floor }, { it.name }))
                state = state.copy(rooms = rooms, loading = false)
                if (rooms.isEmpty()) state = state.copy(error = "学校未返回该馆区/日期的房间，不能解释为全部可约")
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun openRoom(room: LibraryRoom) {
        val date = state.date ?: return
        state = state.copy(loading = true, error = null, room = room, layout = null, seat = null)
        viewModelScope.launch {
            try {
                state = state.copy(layout = repo.layout(room.roomId, date), loading = false)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun chooseSeat(seat: LibrarySeat) {
        if (!seat.selectable) return
        val date = state.date ?: return
        state = state.copy(
            loading = true,
            error = null,
            seat = seat,
            startTimes = emptyList(),
            endTimes = emptyList(),
            start = null,
            end = null,
        )
        viewModelScope.launch {
            try {
                val starts = repo.startTimes(seat.id, date)
                state = state.copy(
                    startTimes = starts,
                    loading = false,
                    showTimeDialog = true,
                    error = if (starts.isEmpty()) "该座位当天没有可预约时间" else null,
                )
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun chooseStart(option: LibraryTimeOption) {
        val date = state.date ?: return
        val seat = state.seat ?: return
        state = state.copy(loading = true, start = option, end = null, endTimes = emptyList(), error = null)
        viewModelScope.launch {
            try {
                val ends = repo.endTimes(seat.id, date, option.id)
                state = state.copy(
                    endTimes = ends,
                    end = ends.firstOrNull(),
                    loading = false,
                    error = if (ends.isEmpty()) "该开始时间没有可选的结束时间" else null,
                )
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun chooseEnd(option: LibraryTimeOption) {
        state = state.copy(end = option, error = null)
    }

    fun closeTimeDialog() {
        state = state.copy(showTimeDialog = false, showConfirm = false)
    }

    fun requestReservation() {
        if (state.start != null && state.end != null) state = state.copy(showConfirm = true)
    }

    fun dismissConfirm() {
        state = state.copy(showConfirm = false)
    }

    fun confirmReservation() {
        val date = state.date ?: return
        val seat = state.seat ?: return
        val start = state.start ?: return
        val end = state.end ?: return
        val beforeIds = state.reservations.map { it.id }.toSet()
        state = state.copy(loading = true, error = null, notice = null, showConfirm = false)
        viewModelScope.launch {
            // 请求与最终状态分开：请求失败/超时也不等于“没预约上”，先查询核对再下结论。
            val requestError = try {
                repo.reserve(date, seat.id, start.id, end.id)
                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e
            }
            if (requestError is LibrarySessionExpiredException) {
                fail(requestError)
                return@launch
            }
            val checked = runCatching { repo.myReservations() }
            checked.onSuccess { list ->
                val current = activeReservations(list)
                val matched = current.firstOrNull { it.id !in beforeIds }?.takeIf {
                    LibraryReservationMatch.matches(it, date, start.value, end.value, seat.name)
                }
                val legacyMatchWithoutNewId = current.firstOrNull {
                    it.id in beforeIds && LibraryReservationMatch.matches(
                        it, date, start.value, end.value, seat.name,
                    )
                }
                val notice = when {
                    matched != null ->
                        "预约成功，已在“我的预约”中核对到该座位与完整时段（记录 ${matched.id}）。"
                    legacyMatchWithoutNewId != null ->
                        "学校已接受请求，但“我的预约”里只有同步前就存在的记录，不能证明本次预约成功；请刷新核对，不要重复提交。"
                    requestError != null ->
                        "预约请求结果不确定（${requestError.friendly()}），核对后也没有看到新记录；请先刷新核对，不要重复提交。"
                    else ->
                        "学校已接受预约请求，但“我的预约”暂未出现新记录；结果待核对，请刷新，不要重复提交。"
                }
                state = state.copy(
                    loading = false,
                    reservations = current,
                    reservationsUpdatedAt = System.currentTimeMillis(),
                    reservationsError = null,
                    showTimeDialog = false,
                    notice = notice,
                )
            }.onFailure { error ->
                state = state.copy(
                    loading = false,
                    notice = if (requestError == null) {
                        "预约请求已提交，但核对“我的预约”失败（${error.friendly()}）；结果待确认，请稍后刷新，不要重复提交。"
                    } else {
                        "预约请求与核对都没有成功（${requestError.friendly()}）；结果不确定，请先刷新核对，不要重复提交。"
                    },
                )
            }
        }
    }

    fun refreshReservations() {
        if (state.reservationsBusy) return
        state = state.copy(reservationsBusy = true)
        viewModelScope.launch {
            loadReservations()
            state = state.copy(reservationsBusy = false)
        }
    }

    /** 读取“我的预约”。失败时保留上一次已确认的记录，只标记“刷新失败 + 旧数据时间”。 */
    private suspend fun loadReservations() {
        try {
            val list = repo.myReservations()
            state = state.copy(
                reservations = activeReservations(list),
                reservationsUpdatedAt = System.currentTimeMillis(),
                reservationsError = null,
                pendingCancelIds = state.pendingCancelIds.intersect(activeReservations(list).map { it.id }.toSet()),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is LibrarySessionExpiredException) {
                state = state.copy(sessionExpired = true)
                return
            }
            state = state.copy(
                reservationsError = "刷新“我的预约”失败：${e.friendly()}。" +
                    "下面仍是上一次成功读取的记录" +
                    (state.reservationsUpdatedAt?.let { "（${timeLabel(it)}）" } ?: "") +
                    "，不代表当前状态。",
            )
        }
    }

    fun cancel(reservation: LibraryReservation) {
        state = state.copy(
            loading = true,
            error = null,
            notice = null,
            pendingCancelIds = state.pendingCancelIds + reservation.id,
        )
        viewModelScope.launch {
            val requestError = try {
                repo.cancel(reservation.id)
                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e
            }
            if (requestError is LibrarySessionExpiredException) {
                fail(requestError)
                return@launch
            }
            // 取消请求返回只说明“学校收到”，最终状态必须再查一次。
            val checked = runCatching { repo.myReservations() }
            checked.onSuccess { list ->
                val current = activeReservations(list)
                val stillThere = current.any { it.id == reservation.id }
                state = state.copy(
                    loading = false,
                    reservations = current,
                    reservationsUpdatedAt = System.currentTimeMillis(),
                    reservationsError = null,
                    pendingCancelIds = if (stillThere) {
                        state.pendingCancelIds // 仍显示 = 结果未确认，保留“待确认”标记
                    } else {
                        state.pendingCancelIds - reservation.id
                    },
                    notice = when {
                        !stillThere && requestError == null ->
                            "取消已由学校确认，该记录不再出现在“我的预约”中。"
                        !stillThere ->
                            "取消请求返回不确定（${requestError?.friendly()}），但核对后记录已不在“我的预约”中。"
                        requestError == null ->
                            "取消请求已提交，但学校仍返回该预约；结果待确认，请稍后刷新，不会自动重试。"
                        else ->
                            "取消请求结果不确定（${requestError.friendly()}），学校仍返回该预约；请刷新核对，不会自动重试。"
                    },
                )
            }.onFailure { error ->
                // 关键：核对失败时不清空列表，也不能把“没读到”当成“已取消”。
                state = state.copy(
                    loading = false,
                    reservationsError = "取消请求已提交，但核对最终状态失败：${error.friendly()}。" +
                        "列表仍是取消前的记录；请稍后刷新，不会自动重复提交。",
                    notice = "该预约标记为“待确认”：请求已发出，最终状态尚未核对。",
                )
            }
        }
    }

    private fun activeReservations(list: List<LibraryReservation>) =
        list.filter { it.status == "RESERVE" || it.status == "CHECK_IN" || it.status == "AWAY" }

    private fun timeLabel(epochMs: Long): String {
        val time = java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "%02d:%02d".format(time.hour, time.minute)
    }

    private fun Throwable.friendly(): String = when (this) {
        is LibrarySeatException -> message ?: "学校服务异常"
        else -> message ?: javaClass.simpleName
    }

    fun logout() {
        repo.clearSession()
        state = UiState()
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun clearNotice() {
        state = state.copy(notice = null)
    }

    private fun fail(e: Exception) {
        if (e is CancellationException) throw e
        val message = when (e) {
            is LibrarySessionExpiredException -> {
                state = state.copy(sessionExpired = true)
                e.message
            }
            is LibraryCaptchaRequiredException -> e.message
            is LibrarySeatException -> e.message
            else -> e.message ?: "图书馆服务查询失败，请稍后重试"
        }
        state = state.copy(loading = false, error = message)
    }
}
