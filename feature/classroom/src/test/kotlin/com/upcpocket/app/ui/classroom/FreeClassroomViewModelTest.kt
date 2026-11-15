package com.upcpocket.app.ui.classroom

import com.upcpocket.app.data.classroom.ClassroomPeriodGroup
import com.upcpocket.app.data.classroom.ClassroomQueryMode
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomCampusStore
import com.upcpocket.app.data.classroom.FreeClassroomQuery
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
import com.upcpocket.app.data.classroom.matches
import java.io.IOException
import java.net.SocketException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class FreeClassroomViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `new entry starts at Shanghai today morning and queries once after restore`() = runTest(dispatcher) {
        val store = FakeCampusStore()
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = viewModel(
            store = store,
            repository = FreeClassroomRepository { query ->
                queries += query
                result(query)
            },
        )

        runCurrent()

        assertFalse(vm.restoringCampus)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, vm.campus)
        assertEquals(LocalDate.of(2026, 11, 5), vm.date)
        assertEquals(ClassroomPeriodGroup.MORNING.periods, vm.periods)
        assertEquals(ClassroomQueryMode.PRESET, vm.mode)
        assertEquals(1, store.reads)
        assertEquals(1, queries.size)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, queries[0].campus)
        assertEquals(LocalDate.of(2026, 11, 5), queries[0].date)
        assertEquals(ClassroomPeriodGroup.MORNING.periods, queries[0].periods)
    }

    @Test
    fun `saved campus is applied before the only initial query`() = runTest(dispatcher) {
        val store = FakeCampusStore(saved = FreeClassroomCampus.GUZHENKOU)
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = viewModel(
            store = store,
            repository = FreeClassroomRepository { query ->
                queries += query
                result(query)
            },
        )

        runCurrent()

        assertEquals(FreeClassroomCampus.GUZHENKOU, vm.campus)
        assertEquals(1, queries.size)
        assertEquals(FreeClassroomCampus.GUZHENKOU, queries[0].campus)
        assertEquals(LocalDate.of(2026, 11, 5), queries[0].date)
        assertEquals(ClassroomPeriodGroup.MORNING.periods, queries[0].periods)
    }

    @Test
    fun `re-entry restores campus but resets date and period to morning`() = runTest(dispatcher) {
        val store = FakeCampusStore()
        val firstQueries = mutableListOf<FreeClassroomQuery>()
        val first = viewModel(
            store = store,
            repository = FreeClassroomRepository { query ->
                firstQueries += query
                result(query)
            },
        )
        runCurrent()
        first.chooseCampus(FreeClassroomCampus.GUZHENKOU)
        runCurrent()
        assertEquals(FreeClassroomCampus.GUZHENKOU, store.saved)

        val nextDay = Clock.fixed(
            Instant.parse("2026-11-05T17:00:00Z"), // 2026-11-06 01:00 in Shanghai
            ZoneId.of("Asia/Shanghai"),
        )
        val secondQueries = mutableListOf<FreeClassroomQuery>()
        val second = viewModel(
            store = store,
            clock = nextDay,
            repository = FreeClassroomRepository { query ->
                secondQueries += query
                result(query)
            },
        )
        runCurrent()

        assertEquals(FreeClassroomCampus.GUZHENKOU, second.campus)
        assertEquals(LocalDate.of(2026, 11, 6), second.date)
        assertEquals(ClassroomPeriodGroup.MORNING.periods, second.periods)
        assertEquals(1, secondQueries.size)
        assertEquals(LocalDate.of(2026, 11, 6), secondQueries[0].date)
        assertEquals(ClassroomPeriodGroup.MORNING.periods, secondQueries[0].periods)
    }

    @Test
    fun `preset changes auto query and the same query key is not repeated`() = runTest(dispatcher) {
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = viewModel(
            repository = FreeClassroomRepository { query ->
                queries += query
                result(query)
            },
        )
        runCurrent()
        assertEquals(1, queries.size)

        vm.chooseCampus(FreeClassroomCampus.TANGDAOWAN)
        vm.chooseDate(vm.date)
        runCurrent()
        assertEquals(1, queries.size)

        // 自定义节次保持同一查询键，切回同一预设也不重复请求。
        vm.applyPeriods(ClassroomPeriodGroup.MORNING.periods)
        vm.choosePreset(ClassroomPeriodGroup.MORNING)
        runCurrent()
        assertEquals(1, queries.size)
        assertEquals(ClassroomQueryMode.PRESET, vm.mode)

        vm.chooseCampus(FreeClassroomCampus.GUZHENKOU)
        runCurrent()
        assertEquals(2, queries.size)
        assertEquals(FreeClassroomCampus.GUZHENKOU, queries[1].campus)

        vm.chooseDate(vm.date.plusDays(1))
        runCurrent()
        assertEquals(3, queries.size)
        assertEquals(LocalDate.of(2026, 11, 6), queries[2].date)

        vm.choosePreset(ClassroomPeriodGroup.AFTERNOON)
        runCurrent()
        assertEquals(4, queries.size)
        assertEquals(
            ClassroomPeriodGroup.MORNING.periods + ClassroomPeriodGroup.AFTERNOON.periods,
            queries[3].periods,
        )

        // 用户主动查询/重试允许重复同一键。
        vm.search()
        runCurrent()
        assertEquals(5, queries.size)
    }

    @Test
    fun `custom mode waits for explicit search and preset resumes automatic query`() = runTest(dispatcher) {
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = viewModel(
            repository = FreeClassroomRepository { query ->
                queries += query
                result(query)
            },
        )
        runCurrent()
        assertEquals(1, queries.size)

        vm.applyPeriods(setOf(6, 7))
        runCurrent()
        assertEquals(ClassroomQueryMode.CUSTOM, vm.mode)
        assertEquals(1, queries.size)

        vm.chooseCampus(FreeClassroomCampus.GUZHENKOU)
        vm.chooseDate(vm.date.plusDays(1))
        vm.moveDate(1)
        vm.chooseBuilding("东廊")
        runCurrent()
        assertEquals(1, queries.size)
        assertFalse(vm.loading)

        vm.search()
        runCurrent()
        assertEquals(2, queries.size)
        assertEquals(FreeClassroomCampus.GUZHENKOU, queries[1].campus)
        assertEquals(LocalDate.of(2026, 11, 7), queries[1].date)
        assertEquals("东廊", queries[1].building)
        assertEquals(setOf(6, 7), queries[1].periods)

        vm.choosePreset(ClassroomPeriodGroup.MORNING)
        runCurrent()
        assertEquals(3, queries.size)
        assertEquals(ClassroomQueryMode.PRESET, vm.mode)
        assertEquals(ClassroomPeriodGroup.MORNING.periods, queries[2].periods)
    }

    @Test
    fun `explicit custom confirmation keeps custom mode when periods equal the preset`() = runTest(dispatcher) {
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = viewModel(
            repository = FreeClassroomRepository { query ->
                queries += query
                result(query)
            },
        )
        runCurrent()
        assertEquals(1, queries.size)

        vm.applyFilters(
            buildingValue = "全部",
            periodValues = ClassroomPeriodGroup.MORNING.periods,
            customPeriodsExplicitlySelected = true,
        )
        runCurrent()
        assertEquals(ClassroomQueryMode.CUSTOM, vm.mode)
        assertEquals(1, queries.size)

        vm.moveDate(1)
        runCurrent()
        assertEquals(1, queries.size)

        vm.search()
        runCurrent()
        assertEquals(2, queries.size)
        assertEquals(vm.date, queries.last().date)
        assertEquals(ClassroomPeriodGroup.MORNING.periods, queries.last().periods)
    }

    @Test
    fun `building only filter keeps preset auto query mode`() = runTest(dispatcher) {
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = viewModel(
            repository = FreeClassroomRepository { query ->
                queries += query
                result(query)
            },
        )
        runCurrent()

        vm.applyFilters("东廊", ClassroomPeriodGroup.MORNING.periods)
        assertEquals(ClassroomQueryMode.PRESET, vm.mode)
        assertEquals(1, queries.size)

        vm.moveDate(1)
        runCurrent()
        assertEquals(2, queries.size)
        assertEquals("东廊", queries.last().building)
        assertEquals(vm.date, queries.last().date)
    }

    @Test
    fun `late old response cannot overwrite a newer campus query`() = runTest(dispatcher) {
        val first = CompletableDeferred<FreeClassroomResult>()
        val second = CompletableDeferred<FreeClassroomResult>()
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = viewModel(
            repository = FreeClassroomRepository { query ->
                queries += query
                if (queries.size == 1) first.await() else second.await()
            },
        )
        runCurrent()
        assertEquals(1, queries.size)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, queries[0].campus)
        assertTrue(vm.loading)

        // 预设模式切换校区会自动发起新查询，并取消旧查询。
        vm.chooseCampus(FreeClassroomCampus.GUZHENKOU)
        assertTrue(vm.loading)
        runCurrent()
        assertEquals(2, queries.size)
        assertEquals(FreeClassroomCampus.GUZHENKOU, queries[1].campus)

        first.complete(result(queries[0]))
        runCurrent()
        assertNull(vm.result)

        second.complete(result(queries[1]))
        runCurrent()
        assertEquals(queries[1], vm.result?.query)
        assertFalse(vm.loading)
    }

    @Test
    fun `failed query keeps a real error and no fake result, then retries`() = runTest(dispatcher) {
        var calls = 0
        val vm = viewModel(
            repository = FreeClassroomRepository { query ->
                calls++
                if (calls == 1) throw IOException("学校服务暂时不可用，请稍后重试")
                result(query)
            },
        )
        runCurrent()

        assertFalse(vm.loading)
        assertNull(vm.result)
        assertTrue(vm.error?.contains("学校服务") == true)

        vm.search()
        runCurrent()
        assertEquals(2, calls)
        assertNull(vm.error)
        assertEquals(vm.currentQuery(), vm.result?.query)
        assertTrue(vm.result!!.matches(vm.currentQuery()))
    }

    @Test
    fun `atomic detail filter waits for one explicit manual query`() = runTest(dispatcher) {
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = viewModel(
            repository = FreeClassroomRepository { query ->
                queries += query
                result(query)
            },
        )
        runCurrent()
        val previous = vm.result

        vm.applyFilters("东廊", setOf(2, 3, 6))
        runCurrent()

        assertEquals(1, queries.size)
        assertEquals("东廊", vm.building)
        assertEquals(setOf(2, 3, 6), vm.periods)
        assertEquals(ClassroomQueryMode.CUSTOM, vm.mode)
        assertEquals(previous, vm.result)
        assertFalse(vm.result!!.matches(vm.currentQuery()))

        vm.search()
        runCurrent()
        assertEquals(2, queries.size)
        assertEquals(vm.currentQuery(), queries.last())
        assertTrue(vm.result!!.matches(vm.currentQuery()))
    }

    @Test
    fun `same query refresh failure preserves the timestamped result`() = runTest(dispatcher) {
        var calls = 0
        val vm = viewModel(
            repository = FreeClassroomRepository { query ->
                calls++
                if (calls == 2) throw IOException("学校服务暂时不可用，请稍后重试")
                result(query)
            },
        )
        runCurrent()
        val previous = vm.result
        assertNotNull(previous)

        vm.search()
        runCurrent()

        assertEquals(previous, vm.result)
        assertNotNull(vm.error)
        assertFalse(vm.loading)
    }

    @Test
    fun `repository result for a different query is rejected`() = runTest(dispatcher) {
        val vm = viewModel(
            repository = FreeClassroomRepository { query -> result(query.copy(date = query.date.plusDays(1))) },
        )
        runCurrent()

        assertNull(vm.result)
        assertTrue(vm.error?.contains("查询条件") == true)
    }

    @Test
    fun `unknown io failure is shown as an unconfirmed school result without raw detail`() = runTest(dispatcher) {
        val vm = viewModel(
            repository = FreeClassroomRepository { throw IOException("synthetic response payload detail") },
        )
        runCurrent()

        assertEquals("学校结果暂不可确认，请稍后重试", vm.error)
        assertFalse(vm.error!!.contains("synthetic"))
    }

    @Test
    fun `socket failure is reported as network unavailable`() = runTest(dispatcher) {
        val vm = viewModel(
            repository = FreeClassroomRepository { throw SocketException("synthetic socket detail") },
        )
        runCurrent()

        assertEquals("无法连接学校服务，请检查网络后重试", vm.error)
        assertFalse(vm.error!!.contains("synthetic"))
    }

    @Test
    fun `campus store failure still allows one initial query`() = runTest(dispatcher) {
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = viewModel(
            store = object : FreeClassroomCampusStore {
                override suspend fun read(): FreeClassroomCampus = error("read failed")
                override suspend fun write(campus: FreeClassroomCampus) = Unit
            },
            repository = FreeClassroomRepository { query ->
                queries += query
                result(query)
            },
        )
        runCurrent()

        assertFalse(vm.restoringCampus)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, vm.campus)
        assertEquals(1, queries.size)
    }

    @Test
    fun `default date and today use Asia Shanghai rather than the device zone`() = runTest(dispatcher) {
        val clock = Clock.fixed(
            Instant.parse("2026-11-04T17:00:00Z"), // 2026-11-05 01:00 in Shanghai
            ZoneId.of("Asia/Shanghai"),
        )
        val vm = viewModel(clock = clock)
        runCurrent()
        assertEquals(LocalDate.of(2026, 11, 5), vm.date)
        assertEquals(LocalDate.of(2026, 11, 5), vm.today())
    }

    private fun viewModel(
        store: FreeClassroomCampusStore = FakeCampusStore(),
        clock: Clock = Clock.fixed(
            Instant.parse("2026-11-05T01:00:00Z"),
            ZoneId.of("Asia/Shanghai"),
        ),
        repository: FreeClassroomRepository = FreeClassroomRepository { query -> result(query) },
    ) = FreeClassroomViewModel(
        repository = repository,
        campusStore = store,
        clock = clock,
    )

    private fun result(query: FreeClassroomQuery) = FreeClassroomResult(
        query = query,
        buildings = listOf("全部"),
        rooms = emptyList(),
        fetchedAt = Instant.now(),
    )

    private class FakeCampusStore(
        var saved: FreeClassroomCampus? = null,
    ) : FreeClassroomCampusStore {
        var reads = 0

        override suspend fun read(): FreeClassroomCampus? {
            reads++
            return saved
        }

        override suspend fun write(campus: FreeClassroomCampus) {
            saved = campus
        }
    }
}
