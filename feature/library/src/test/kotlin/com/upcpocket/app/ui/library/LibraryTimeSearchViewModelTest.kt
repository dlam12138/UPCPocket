package com.upcpocket.app.ui.library

import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryHiddenSeat
import com.upcpocket.app.data.library.LibraryReservationAttempt
import com.upcpocket.app.data.library.LibraryReservationDuplicateException
import com.upcpocket.app.data.library.LibraryReservationPreflightFailedException
import com.upcpocket.app.data.library.LibraryReservationRequest
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatOps
import com.upcpocket.app.data.library.LibrarySeatRepository
import com.upcpocket.app.data.library.LibrarySeatSession
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.data.library.LibraryTimeSearchKey
import com.upcpocket.app.data.library.LibraryTimeSearchPage
import com.upcpocket.app.data.library.LibraryTimeSearchResult
import com.upcpocket.app.data.library.ReservationAttemptLatch
import com.upcpocket.app.data.prefs.LibraryHiddenSeatsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel 编排测试：可控 fake ops / hidden store，覆盖
 *  - 隐藏响应校验房间+代次（挂起存储 → 切房 → 完成不污染新房间 / 不清新选择）；
 *  - 选中隐藏立即清选、迟到 start/end 不恢复确认；
 *  - 未知结果会话范围防重（close/reenter/新 VM 不再次 POST）；
 *  - 分页失败 / 协议不前进后重试真的发出请求；
 *  - 隐藏读写抛异常不崩、不假成功。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTimeSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = LibrarySeatRepository()
    private lateinit var ops: FakeOps
    private lateinit var store: FakeHiddenStore
    private lateinit var vm: LibrarySeatViewModel

    /** 用给定初始状态构造（或重建）ViewModel，模拟关闭重进 / 新 VM。 */
    private fun newVm(state: LibrarySeatViewModel.UiState = LibrarySeatViewModel.UiState()): LibrarySeatViewModel =
        LibrarySeatViewModel(
            repo = repo,
            hiddenSeatsStore = store,
            ops = ops,
            initialState = state,
        )

    private val roomA = LibraryRoom("rA", "一层 A 区", 1, "1 楼")
    private val roomB = LibraryRoom("rB", "二层 B 区", 2, "2 楼")
    private val keyA = LibraryTimeSearchKey("1", "rA", "2026-11-07", 840, 960, "14:00", "16:00")
    private val keyB = keyA.copy(roomId = "rB")

    private fun seat(id: String) = LibrarySeat(
        id = id, name = "A$id", row = 0, col = 0,
        type = "seat", enabled = true, status = "FREE", localOnly = false,
    )

    private fun baseState(room: LibraryRoom = roomA) = LibrarySeatViewModel.UiState(
        filters = LibraryFilters(listOf("2026-11-07"), listOf(LibraryBuilding("1", "东馆"))),
        building = LibraryBuilding("1", "东馆"),
        date = "2026-11-07",
        room = room,
        seatMode = RoomSeatMode.TIME,
        timeBegin = "14:00",
        timeFinish = "16:00",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ops = FakeOps()
        store = FakeHiddenStore()
        vm = newVm()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 2. 隐藏响应房间/代次隔离 ───────────────────────────────

    @Test
    fun `挂起隐藏存储期间切到另一房间完成后不污染新房间`() = runTest(dispatcher) {
        vm.setState(baseState(roomA))
        val hideResult = CompletableDeferred<Boolean>()
        store.hideResult = hideResult
        vm.hideSeat(seat("s1"))
        // 期间用户返回并打开另一房间（同 seatId 也不能串）
        vm.setState(baseState(roomB).copy(timeSelectedSeat = seat("s1")))
        // 完成 A 的隐藏写入
        hideResult.complete(true)
        advanceUntilIdle()
        // B 的 UI 不得加入 A 的隐藏项，也不得清掉 B 的选择
        assertTrue(vm.state.hiddenSeatIds.isEmpty())
        assertTrue(vm.state.hiddenEntries.isEmpty())
        assertEquals("s1", vm.state.timeSelectedSeat?.id)
        assertEquals("rB", vm.state.room?.roomId)
    }

    @Test
    fun `隐藏正被选座位立即清选并递增代次使迟到time响应失效`() = runTest(dispatcher) {
        vm.setState(
            baseState(roomA).copy(
                timeResult = LibraryTimeSearchResult(
                    key = keyA, seats = listOf(seat("s1")), complete = true,
                ),
                timeSelectedSeat = seat("s1"),
            ),
        )
        val startReturn = CompletableDeferred<List<LibraryTimeOption>>()
        ops.startReturn = startReturn
        vm.selectTimeCandidate(seat("s1"))
        // 选时请求在途，用户隐藏该座位
        store.hideResult = CompletableDeferred(true)
        vm.hideSeat(seat("s1"))
        // 立即清选，不等持久化
        assertEquals(null, vm.state.timeSelectedSeat)
        assertFalse(vm.state.showConfirm)
        // 迟到的 start/end 响应不能恢复确认
        startReturn.complete(listOf(LibraryTimeOption("840", "14:00")))
        advanceUntilIdle()
        assertEquals(null, vm.state.timeStart)
        assertEquals(null, vm.state.timeStop)
        assertFalse(vm.state.canConfirmTimeReservation())
    }

    @Test
    fun `隐藏存储抛异常不崩不假成功`() = runTest(dispatcher) {
        vm.setState(baseState(roomA))
        store.hideThrows = true
        vm.hideSeat(seat("s1"))
        advanceUntilIdle()
        assertTrue(vm.state.hiddenSeatIds.isEmpty())
        assertTrue(vm.state.hiddenActionError?.contains("保存失败") == true)
    }

    @Test
    fun `loadHiddenSeats 读取抛异常显示真实错误不假装空名单`() = runTest(dispatcher) {
        vm.setState(baseState(roomA))
        store.entriesThrows = true
        vm.openHiddenSheet()
        advanceUntilIdle()
        assertTrue(vm.state.hiddenActionError?.contains("读取失败") == true)
        assertTrue(vm.state.hiddenSeatIds.isEmpty())
    }

    // ── 3. 分页失败 / 不前进后重试真的发请求 ───────────────────

    @Test
    fun `首屏失败后 retryTime 真的重新发出查询`() = runTest(dispatcher) {
        vm.setState(baseState(roomA))
        ops.timeSearchResults.clear()
        ops.timeSearchError = RuntimeException("network")
        vm.searchByTime()
        advanceUntilIdle()
        assertTrue(vm.state.timeResult?.failed == true)
        val before = ops.timeSearchCalls
        ops.timeSearchError = null
        vm.retryTime()
        advanceUntilIdle()
        assertTrue("retry 必须真正发请求", ops.timeSearchCalls > before)
    }

    @Test
    fun `下一页失败后 retryTime 真的重新发出当前页`() = runTest(dispatcher) {
        vm.setState(baseState(roomA))
        // 第 1 页成功 hasMore=true
        ops.timeSearchResults.add(LibraryTimeSearchPage(listOf(seat("a")), 1, true))
        // 第 2 页失败
        ops.failOnCall = 2
        vm.searchByTime()
        advanceUntilIdle()
        val afterFirst = vm.state.timeResult
        assertTrue(afterFirst?.hasMore == true)
        vm.loadMoreTime()
        advanceUntilIdle()
        assertTrue(vm.state.timeResult?.partialFailure == true)
        assertTrue(vm.state.timeResult?.hasMore == true)
        val beforeRetry = ops.timeSearchCalls
        ops.failOnCall = 0
        ops.timeSearchResults.add(LibraryTimeSearchPage(listOf(seat("b")), 2, false))
        vm.retryTime()
        advanceUntilIdle()
        assertTrue("重试必须真正发请求", ops.timeSearchCalls > beforeRetry)
    }

    // ── 3b. 合法空终页 / 不前进空页（VM 真实编排，不直接构造 complete）──

    @Test
    fun `首次严格解析合法空终页走生产 complete 空态`() = runTest(dispatcher) {
        vm.setState(baseState(roomA))
        // 经 parser 等价形状：seats={} page=1 hasMore=false
        ops.timeSearchResults.add(LibraryTimeSearchPage(emptyList(), 1, false))
        vm.searchByTime()
        advanceUntilIdle()
        val r = requireNotNull(vm.state.timeResult)
        assertTrue(r.complete)
        assertFalse(r.hasMore)
        assertFalse(r.partialFailure)
        assertTrue(r.seats.isEmpty())
        // 生产 UI 分支依据：complete && seats.isEmpty() → 「这个时段暂无可约座位」
    }

    @Test
    fun `后续合法空末页保留 N 项且 complete 无加载更多`() = runTest(dispatcher) {
        vm.setState(baseState(roomA))
        ops.timeSearchResults.add(LibraryTimeSearchPage(listOf(seat("a"), seat("b")), 1, true))
        vm.searchByTime()
        advanceUntilIdle()
        assertTrue(vm.state.timeResult?.hasMore == true)
        // 下一页 = 真实观察过的 batch=1,page=2 → 0 个 / hasMore=false
        ops.timeSearchResults.add(LibraryTimeSearchPage(emptyList(), 2, false))
        vm.loadMoreTime()
        advanceUntilIdle()
        val r = requireNotNull(vm.state.timeResult)
        assertTrue(r.complete)
        assertFalse(r.hasMore)
        assertFalse(r.partialFailure)
        assertEquals(listOf("a", "b"), r.seats.map { it.id })
    }

    @Test
    fun `不前进空页后重试真发请求`() = runTest(dispatcher) {
        vm.setState(baseState(roomA))
        // 空名单 + hasMore=true → partialFailure/可重试
        ops.timeSearchResults.add(LibraryTimeSearchPage(emptyList(), 1, true))
        vm.searchByTime()
        advanceUntilIdle()
        assertTrue(vm.state.timeResult?.partialFailure == true)
        val before = ops.timeSearchCalls
        // 重试后返回合法空终页 → complete
        ops.timeSearchResults.add(LibraryTimeSearchPage(emptyList(), 1, false))
        vm.retryTime()
        advanceUntilIdle()
        assertTrue("重试必须真发请求", ops.timeSearchCalls > before)
        assertTrue(vm.state.timeResult?.complete == true)
    }

    // ── 4. 未知结果会话范围防重（close/reenter/新 VM 不再 POST）──

    @Test
    fun `同 key 未知结果 close 重进新 VM 不再次 POST`() = runTest(dispatcher) {
        ops.reserveResult = LibraryReservationAttempt.SENT
        ops.reserveThrowsUnknown = true  // 模拟 POST 后结果未知
        vm.setState(
            baseState(roomA).copy(
                timeResult = LibraryTimeSearchResult(
                    key = keyA, seats = listOf(seat("s1")), complete = true,
                ),
                timeSelectedSeat = seat("s1"),
                timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
                timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
                timeStart = LibraryTimeOption("840", "14:00"),
                timeStop = LibraryTimeOption("960", "16:00"),
            ),
        )
        vm.confirmTimeReservation()
        advanceUntilIdle()
        assertEquals(1, ops.reserveCalls)
        assertTrue(vm.state.reservationSubmitted)

        // 关闭重进：新 ViewModel、同一 repository/ops（同一会话）
        val vm2 = newVm(
            baseState(roomA).copy(
                timeResult = LibraryTimeSearchResult(
                    key = keyA, seats = listOf(seat("s1")), complete = true,
                ),
                timeSelectedSeat = seat("s1"),
                timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
                timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
                timeStart = LibraryTimeOption("840", "14:00"),
                timeStop = LibraryTimeOption("960", "16:00"),
            ),
        )
        // 即便绕过 UI 布尔直接调用，repository 防重闩必须拒绝第二次 POST。
        vm2.confirmTimeReservation()
        advanceUntilIdle()
        assertEquals("同 key 未知结果不得再次 POST", 1, ops.reserveCalls)
    }

    @Test
    fun `POST 前验证码 NOT_SENT 不置 submitted 不影响网页回退`() = runTest(dispatcher) {
        ops.reserveResult = LibraryReservationAttempt.NOT_SENT
        vm.setState(
            baseState(roomA).copy(
                timeResult = LibraryTimeSearchResult(
                    key = keyA, seats = listOf(seat("s1")), complete = true,
                ),
                timeSelectedSeat = seat("s1"),
                timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
                timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
                timeStart = LibraryTimeOption("840", "14:00"),
                timeStop = LibraryTimeOption("960", "16:00"),
            ),
        )
        vm.confirmTimeReservation()
        advanceUntilIdle()
        assertFalse("POST 前验证码不应冒充已发送", vm.state.reservationSubmitted)
        assertTrue(vm.state.error?.contains("验证码") == true)
        // 仍可再次尝试（未被闩），网页回退不受影响
        assertTrue(vm.state.canConfirmTimeReservation())
    }

    @Test
    fun `POST 前验证失败不冒充已发送不影响再次尝试`() = runTest(dispatcher) {
        ops.reservePreflightFails = true
        vm.setState(
            baseState(roomA).copy(
                timeResult = LibraryTimeSearchResult(
                    key = keyA, seats = listOf(seat("s1")), complete = true,
                ),
                timeSelectedSeat = seat("s1"),
                timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
                timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
                timeStart = LibraryTimeOption("840", "14:00"),
                timeStop = LibraryTimeOption("960", "16:00"),
            ),
        )
        vm.confirmTimeReservation()
        advanceUntilIdle()
        assertEquals(0, ops.reserveCalls)
        assertFalse("POST 前失败不得置 submitted", vm.state.reservationSubmitted)
        assertTrue(vm.state.canConfirmTimeReservation()) // 仍可正常重试/网页回退
    }

    @Test
    fun `reservationSubmitted 后底部守卫不可确认`() = runTest(dispatcher) {
        vm.setState(
            baseState(roomA).copy(
                timeResult = LibraryTimeSearchResult(
                    key = keyA, seats = listOf(seat("s1")), complete = true,
                ),
                timeSelectedSeat = seat("s1"),
                timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
                timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
                timeStart = LibraryTimeOption("840", "14:00"),
                timeStop = LibraryTimeOption("960", "16:00"),
                reservationSubmitted = true,
            ),
        )
        assertFalse(vm.state.canConfirmTimeReservation())
        vm.requestTimeReservation()
        assertFalse(vm.state.showConfirm)
    }

    // ── 5. 已加载候选交集计数 ─────────────────────────────────

    @Test
    fun `hide 后 loadHiddenSeats 单次读取派生 ids 与 entries 一致`() = runTest(dispatcher) {
        vm.setState(baseState(roomA))
        store.hideResult = CompletableDeferred(true)
        vm.hideSeat(seat("s1"))
        advanceUntilIdle()
        assertEquals(setOf("s1"), vm.state.hiddenSeatIds)
        assertEquals(1, vm.state.hiddenEntries.size)
        assertEquals("s1", vm.state.hiddenEntries.single().seatId)
    }

    // ── fakes ──────────────────────────────────────────────────

    @Test
    fun `迟到提交响应和预检错误都不改新房间状态`() = runTest(dispatcher) {
        for (lateFailure in listOf(false, true)) {
            ops.reserveGate = CompletableDeferred()
            vm = newVm(baseState(roomA).copy(
                timeResult = LibraryTimeSearchResult(key = keyA, seats = listOf(seat("s-late-$lateFailure")), complete = true),
                timeSelectedSeat = seat("s-late-$lateFailure"),
                timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
                timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
                timeStart = LibraryTimeOption("840", "14:00"),
                timeStop = LibraryTimeOption("960", "16:00"),
            ))
            vm.confirmTimeReservation()
            advanceUntilIdle()
            val newRoomState = baseState(roomB).copy(loading = true, error = "new-room-status")
            vm.setState(newRoomState)
            val readsBefore = ops.reservationReads
            if (lateFailure) {
                ops.reserveGate!!.completeExceptionally(LibraryReservationPreflightFailedException("old-status"))
            } else {
                ops.reserveGate!!.complete(LibraryReservationAttempt.SENT)
            }
            advanceUntilIdle()
            assertEquals(newRoomState, vm.state)
            assertEquals(readsBefore, ops.reservationReads)
        }
    }

    @Test
    fun `确认之后调度之前换号不向新会话提交旧选择`() = runTest(dispatcher) {
        val candidate = seat("s-switch")
        vm.setState(baseState(roomA).copy(
            timeResult = LibraryTimeSearchResult(key = keyA, seats = listOf(candidate), complete = true),
            timeSelectedSeat = candidate,
            timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
            timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
            timeStart = LibraryTimeOption("840", "14:00"),
            timeStop = LibraryTimeOption("960", "16:00"),
        ))
        vm.confirmTimeReservation()
        ops.token = "new-session"
        advanceUntilIdle()
        assertEquals(0, ops.reserveCalls)
    }

    /** 直接设置 UiState（绕过 private set），仅测试用。 */
    private fun LibrarySeatViewModel.setState(value: LibrarySeatViewModel.UiState) {
        // Compose `by mutableStateOf` 生成 `state$delegate` 字段（MutableState<UiState>）。
        val field = LibrarySeatViewModel::class.java.declaredFields
            .firstOrNull { it.name == "state\$delegate" }
            ?: error("state delegate field not found")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val mutable = field.get(this) as androidx.compose.runtime.MutableState<LibrarySeatViewModel.UiState>
        mutable.value = value
    }

    private class FakeOps : LibrarySeatOps {
        private val _session = MutableStateFlow<LibrarySeatSession?>(null)
        override val session get() = _session
        var token: String? = "token-fixed"
        override fun sessionToken(): String? = token

        val timeSearchResults = mutableListOf<LibraryTimeSearchPage>()
        var timeSearchError: Throwable? = null
        var failOnCall = 0
        var timeSearchCalls = 0

        var startGate: CompletableDeferred<Unit>? = null
        var startReturn: CompletableDeferred<List<LibraryTimeOption>>? = null
        var reserveResult = LibraryReservationAttempt.SENT
        var reserveThrowsUnknown = false
        var reservePreflightFails = false
        var reserveCalls = 0
        var reserveGate: CompletableDeferred<LibraryReservationAttempt>? = null
        var reservationReads = 0
        // 与生产 Repository 共用的会话范围防重闩，供 close/reenter/新 VM 全链路验证。
        val latch = ReservationAttemptLatch()

        override suspend fun timeSearchSeats(
            key: LibraryTimeSearchKey, page: Int, batch: Int, guard: () -> Boolean,
        ): LibraryTimeSearchPage {
            timeSearchCalls++
            timeSearchError?.let { throw it }
            if (failOnCall == timeSearchCalls) {
                throw RuntimeException("simulated next-page failure")
            }
            return if (timeSearchResults.isNotEmpty()) timeSearchResults.removeAt(0)
            else LibraryTimeSearchPage(emptyList(), page, false)
        }

        override suspend fun startTimes(seatId: String, date: String): List<LibraryTimeOption> {
            startReturn?.let { return it.await() }
            return listOf(LibraryTimeOption("840", "14:00"))
        }

        override suspend fun endTimes(
            seatId: String, date: String, startId: String,
        ): List<LibraryTimeOption> = listOf(LibraryTimeOption("960", "16:00"))

        override suspend fun reserve(
            date: String, seatId: String, startId: String, endId: String,
        ): LibraryReservationAttempt {
            if (reserveResult == LibraryReservationAttempt.NOT_SENT) {
                // POST 前验证/CAPTCHA：不发 POST、不登记。
                return LibraryReservationAttempt.NOT_SENT
            }
            if (reservePreflightFails) {
                throw LibraryReservationPreflightFailedException("提交前验证失败")
            }
            val key = LibraryReservationRequest(
                system = LibraryReservationRequest.SYSTEM_LIBSEAT,
                userId = "user-1", date = date, seatId = seatId,
                startId = startId, endId = endId,
            )
            if (!latch.registerOrReject(key, sessionGeneration = 1L)) {
                throw LibraryReservationDuplicateException()
            }
            reserveCalls++
            reserveGate?.let { return it.await() }
            if (reserveThrowsUnknown) throw RuntimeException("unknown result")
            return LibraryReservationAttempt.SENT
        }

        override suspend fun myReservations(): List<com.upcpocket.app.data.library.LibraryReservation> {
            reservationReads++
            return emptyList()
        }
    }

    private class FakeHiddenStore : LibraryHiddenSeatsStore {
        private val data = linkedMapOf<String, LibraryHiddenSeat>()
        var hideGate: CompletableDeferred<Unit>? = null
        var hideResult: CompletableDeferred<Boolean>? = null
        var hideThrows = false
        var entriesThrows = false

        override suspend fun entries(buildingId: String, roomId: String): List<LibraryHiddenSeat> {
            if (entriesThrows) throw IllegalStateException("read failed")
            return data.values.filter {
                it.buildingId == buildingId && it.roomId == roomId
            }.toList()
        }

        override suspend fun hiddenIds(buildingId: String, roomId: String): Set<String> =
            entries(buildingId, roomId).mapTo(linkedSetOf()) { it.seatId }

        override suspend fun hide(entry: LibraryHiddenSeat): Boolean {
            hideGate?.await()
            if (hideThrows) throw IllegalStateException("write failed")
            val ok = hideResult?.await() ?: true
            if (ok) data["${entry.buildingId}|${entry.roomId}|${entry.seatId}"] = entry
            return ok
        }

        override suspend fun restore(buildingId: String, roomId: String, seatId: String): Boolean {
            data.remove("$buildingId|$roomId|$seatId")
            return true
        }

        override suspend fun restoreAll(buildingId: String, roomId: String): Boolean {
            data.keys.filter { it.startsWith("$buildingId|$roomId|") }.forEach { data.remove(it) }
            return true
        }

        override suspend fun count(buildingId: String, roomId: String): Int =
            entries(buildingId, roomId).size
    }
}
