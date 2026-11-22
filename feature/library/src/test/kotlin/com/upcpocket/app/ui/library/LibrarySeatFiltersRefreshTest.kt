package com.upcpocket.app.ui.library

import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryReservationAttempt
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeatOps
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySeatSession
import com.upcpocket.app.data.library.LibrarySessionExpiredException
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.data.library.LibraryTimeSearchKey
import com.upcpocket.app.data.library.LibraryTimeSearchPage
import com.upcpocket.app.data.library.LibraryUser
import com.upcpocket.app.data.prefs.NoOpLibraryHiddenSeatsStore
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 唐岛湾选座日期刷新：进入/刷新必须重新读取学校 filters，旧日期消失时回退到
 * 学校默认日期；新出现的次日可选中并原样传给房间查询。覆盖初次失败重试、
 * 显式刷新失败、会话切换与迟到房间响应，全部使用本地合成数据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySeatFiltersRefreshTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = LibrarySeatRepository()
    private val ops = FakeOps()
    private val today = "2026-11-21"
    private val tomorrow = "2026-11-22"
    private val building = LibraryBuilding("b1", "东馆")

    private var filtersCalls = 0
    private var filtersError: Throwable? = null
    private var filtersGate: CompletableDeferred<Unit>? = null
    private var filtersBody: suspend () -> LibraryFilters = { error("未配置 filters 响应") }
    private val filtersResults = ArrayDeque<LibraryFilters>()

    private val roomsCalls = mutableListOf<Pair<String, String>>()
    private var roomsError: Throwable? = null
    private val roomGates = mutableMapOf<String, CompletableDeferred<Unit>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `进入时重新读取学校日期，旧日期消失后改用学校默认日期查询房间`() = runTest(dispatcher) {
        val vm = newVm(loaded(today))
        filtersResults += LibraryFilters(listOf(tomorrow), listOf(building))

        vm.ensureLoaded()
        advanceUntilIdle()

        assertEquals(1, filtersCalls)
        assertEquals(listOf(tomorrow), vm.state.filters?.dates)
        assertEquals(tomorrow, vm.state.date)
        assertEquals(building, vm.state.building)
        assertEquals(listOf(building.id to tomorrow), roomsCalls)
        assertEquals(listOf("room-$tomorrow"), vm.state.rooms.map { it.roomId })
        assertNull(vm.state.error)
    }

    @Test
    fun `刷新返回次日日期后可选中并用真实次日查询房间`() = runTest(dispatcher) {
        val vm = newVm(loaded(today))
        filtersResults += LibraryFilters(listOf(today, tomorrow), listOf(building))

        vm.loadFilters()
        advanceUntilIdle()

        assertEquals(listOf(today, tomorrow), vm.state.filters?.dates)
        assertEquals("仍有效的当前日期应保留", today, vm.state.date)
        assertEquals(listOf(building.id to today), roomsCalls)

        vm.selectDate(tomorrow)
        advanceUntilIdle()

        assertEquals(tomorrow, vm.state.date)
        assertEquals(listOf(building.id to today, building.id to tomorrow), roomsCalls)
        assertEquals(listOf("room-$tomorrow"), vm.state.rooms.map { it.roomId })
        assertNull(vm.state.error)
    }

    @Test
    fun `初次筛选失败后重试会重新读取并恢复`() = runTest(dispatcher) {
        filtersError = IllegalStateException("network")
        val vm = newVm()

        vm.ensureLoaded()
        advanceUntilIdle()

        assertNull(vm.state.filters)
        assertNotNull(vm.state.error)
        assertFalse(vm.state.loading)
        assertTrue(roomsCalls.isEmpty())
        assertEquals(1, filtersCalls)

        filtersError = null
        filtersResults += LibraryFilters(listOf(today), listOf(building))
        vm.loadFilters()
        advanceUntilIdle()

        assertEquals("重试必须重新发出筛选请求", 2, filtersCalls)
        assertEquals(listOf(today), vm.state.filters?.dates)
        assertEquals(today, vm.state.date)
        assertEquals(listOf(building.id to today), roomsCalls)
        assertNull(vm.state.error)
        assertFalse(vm.state.loading)
    }

    @Test
    fun `显式刷新失败保留原日期并显示错误而不是空结果`() = runTest(dispatcher) {
        val vm = newVm(loaded(today))
        filtersError = IllegalStateException("network")

        vm.loadFilters()
        advanceUntilIdle()

        assertEquals(listOf(today), vm.state.filters?.dates)
        assertEquals(today, vm.state.date)
        assertEquals(building, vm.state.building)
        assertNotNull(vm.state.error)
        assertFalse(vm.state.loading)
        assertTrue("失败时房间为空，但错误态不得当作“没有可预约区域”", vm.state.rooms.isEmpty())

        filtersError = null
        filtersResults += LibraryFilters(listOf(today, tomorrow), listOf(building))
        vm.loadFilters()
        advanceUntilIdle()

        assertEquals(listOf(today, tomorrow), vm.state.filters?.dates)
        assertNull(vm.state.error)
        assertEquals(listOf("room-$today"), vm.state.rooms.map { it.roomId })
    }

    @Test
    fun `旧会话的筛选响应不发布到新会话`() = runTest(dispatcher) {
        publishSession("old")
        val vm = newVm()
        filtersGate = CompletableDeferred()
        filtersResults += LibraryFilters(listOf(today), listOf(building))

        vm.ensureLoaded()
        runCurrent()
        assertEquals(1, filtersCalls)
        assertTrue(vm.state.loading)

        publishSession("new")
        filtersGate?.complete(Unit)
        advanceUntilIdle()

        assertNull(vm.state.filters)
        assertTrue(roomsCalls.isEmpty())
        assertFalse(vm.state.loading)
    }

    @Test
    fun `筛选请求的会话失效仍会回报而不是吞掉`() = runTest(dispatcher) {
        publishSession("expired")
        val vm = newVm()
        filtersBody = {
            repo.clearSession()
            throw LibrarySessionExpiredException()
        }

        vm.ensureLoaded()
        advanceUntilIdle()

        assertTrue(vm.state.sessionExpired)
        assertEquals("expired", vm.state.expiredToken)
        assertFalse(vm.state.loading)
        assertTrue(roomsCalls.isEmpty())
    }

    @Test
    fun `换号后旧筛选请求过期不进入新会话错误态`() = runTest(dispatcher) {
        publishSession("old")
        val vm = newVm()
        filtersGate = CompletableDeferred()
        filtersError = LibrarySessionExpiredException()

        vm.ensureLoaded()
        runCurrent()
        publishSession("new")
        filtersGate?.complete(Unit)
        advanceUntilIdle()

        assertFalse("旧会话过期不得让新会话进入过期态", vm.state.sessionExpired)
        assertNull(vm.state.expiredToken)
        assertNull(vm.state.error)
        assertNull("旧会话目录不得发布到新会话", vm.state.filters)
        assertFalse(vm.state.loading)
    }

    @Test
    fun `切换日期后迟到的旧日期房间响应不覆盖新日期`() = runTest(dispatcher) {
        roomGates[today] = CompletableDeferred()
        roomGates[tomorrow] = CompletableDeferred()
        val vm = newVm(
            loaded(today).copy(
                filters = LibraryFilters(listOf(today, tomorrow), listOf(building)),
            ),
        )

        vm.queryRooms()
        runCurrent()
        vm.selectDate(tomorrow)
        runCurrent()

        assertEquals(listOf(building.id to today, building.id to tomorrow), roomsCalls)

        roomGates.getValue(tomorrow).complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("room-$tomorrow"), vm.state.rooms.map { it.roomId })

        roomGates.getValue(today).complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("room-$tomorrow"), vm.state.rooms.map { it.roomId })
        assertEquals(tomorrow, vm.state.date)
    }

    // ── helpers ────────────────────────────────────────────────

    private fun loaded(date: String) = LibrarySeatViewModel.UiState(
        filters = LibraryFilters(listOf(date), listOf(building)),
        building = building,
        date = date,
        rooms = listOf(room(date)),
    )

    private fun room(date: String) = LibraryRoom(
        roomId = "room-$date",
        name = "$date 房间",
        floor = 1,
        floorName = "1 楼",
        totalSeats = 10,
        freeSeats = 3,
    )

    private fun newVm(
        initialState: LibrarySeatViewModel.UiState = LibrarySeatViewModel.UiState(),
    ) = LibrarySeatViewModel(
        repo = repo,
        hiddenSeatsStore = NoOpLibraryHiddenSeatsStore,
        ops = ops,
        filtersLoader = {
            filtersCalls++
            filtersGate?.await()
            filtersError?.let { throw it }
            filtersResults.removeFirstOrNull() ?: filtersBody()
        },
        floorsLoader = { emptyList() },
        roomsLoader = { buildingId, date ->
            roomsCalls += buildingId to date
            roomGates[date]?.await()
            roomsError?.let { throw it }
            listOf(room(date))
        },
        shanghaiNow = { LocalDateTime.of(2026, 11, 21, 22, 28) },
        initialState = initialState,
    )

    private fun publishSession(token: String) {
        val published = repo.publishSessionIfCurrent(
            LibrarySeatSession(token, LibraryUser("u1", "user", "测试用户")),
            repo.currentSessionGeneration(),
        ) { true }
        check(published) { "测试会话发布失败" }
    }

    private class FakeOps : LibrarySeatOps {
        override val session = MutableStateFlow<LibrarySeatSession?>(null)

        override fun sessionToken(): String? = session.value?.token

        override suspend fun timeSearchSeats(
            key: LibraryTimeSearchKey,
            page: Int,
            batch: Int,
            guard: () -> Boolean,
        ): LibraryTimeSearchPage = LibraryTimeSearchPage(emptyList(), page, false)

        override suspend fun startTimes(seatId: String, date: String): List<LibraryTimeOption> =
            emptyList()

        override suspend fun endTimes(
            seatId: String,
            date: String,
            startId: String,
        ): List<LibraryTimeOption> = emptyList()

        override suspend fun reserve(
            date: String,
            seatId: String,
            startId: String,
            endId: String,
        ): LibraryReservationAttempt = LibraryReservationAttempt.NOT_SENT

        override suspend fun myReservations() = emptyList<com.upcpocket.app.data.library.LibraryReservation>()
    }
}
