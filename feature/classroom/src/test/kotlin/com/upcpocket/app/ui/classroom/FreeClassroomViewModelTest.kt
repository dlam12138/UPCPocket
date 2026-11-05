package com.upcpocket.app.ui.classroom

import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomQuery
import com.upcpocket.app.data.classroom.FreeClassroomRepository
import com.upcpocket.app.data.classroom.FreeClassroomResult
import com.upcpocket.app.data.classroom.matches
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
    fun `default date and today use Asia Shanghai rather than the device zone`() = runTest(dispatcher) {
        val clock = Clock.fixed(
            Instant.parse("2026-11-04T17:00:00Z"), // 2026-11-05 01:00 in Shanghai
            ZoneId.of("Asia/Shanghai"),
        )
        val vm = FreeClassroomViewModel(
            repository = FreeClassroomRepository { query -> result(query) },
            clock = clock,
        )
        assertEquals(LocalDate.of(2026, 11, 5), vm.date)
        assertEquals(LocalDate.of(2026, 11, 5), vm.today())
    }

    @Test
    fun `late old response cannot overwrite a newer filter query`() = runTest(dispatcher) {
        val first = CompletableDeferred<FreeClassroomResult>()
        val second = CompletableDeferred<FreeClassroomResult>()
        val queries = mutableListOf<FreeClassroomQuery>()
        val vm = FreeClassroomViewModel(
            repository = FreeClassroomRepository { query ->
                queries += query
                if (queries.size == 1) first.await() else second.await()
            },
            clock = Clock.fixed(Instant.parse("2026-11-05T01:00:00Z"), ZoneId.of("Asia/Shanghai")),
        )
        runCurrent()
        assertEquals(1, queries.size)
        assertEquals(FreeClassroomCampus.TANGDAOWAN, queries[0].campus)
        assertTrue(vm.loading)

        // 切换校区会取消旧查询；旧响应即使随后返回也不能写回。
        vm.chooseCampus(FreeClassroomCampus.GUZHENKOU)
        assertFalse(vm.loading)
        vm.search()
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
    fun `old result does not match a changed date building or campus`() {
        val query = FreeClassroomQuery(
            campus = FreeClassroomCampus.TANGDAOWAN,
            date = LocalDate.of(2026, 11, 5),
            building = "全部",
            periods = setOf(1, 2, 3, 4, 5),
        )
        val result = result(query)
        assertTrue(result.matches(query))
        assertFalse(result.matches(query.copy(date = query.date.plusDays(1))))
        assertFalse(result.matches(query.copy(building = "东廊")))
        assertFalse(result.matches(query.copy(campus = FreeClassroomCampus.GUZHENKOU)))
    }

    private fun result(query: FreeClassroomQuery) = FreeClassroomResult(
        query = query,
        buildings = listOf("全部"),
        rooms = emptyList(),
        fetchedAt = Instant.now(),
    )
}
