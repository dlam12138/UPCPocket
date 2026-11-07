package com.upcpocket.app.ui.library

import com.upcpocket.app.data.library.LibraryBuilding
import com.upcpocket.app.data.library.LibraryFilters
import com.upcpocket.app.data.library.LibraryRoom
import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibrarySeatLayout
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.data.library.LibraryTimeSearchKey
import com.upcpocket.app.data.library.LibraryTimeSearchPage
import com.upcpocket.app.data.library.LibraryTimeSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按时段找的纯状态推导：分页、区间、官方 id、两模式守卫、旧条件/账号隔离。 */
class LibraryTimeSearchLogicTest {

    private val key = LibraryTimeSearchKey(
        buildingId = "1", roomId = "r1", date = "2026-11-07",
        beginMinute = 840, endMinute = 960, beginText = "14:00", endText = "16:00",
    )
    private val room = LibraryRoom("r1", "一层 A 区", 1, "1 楼")

    private fun seat(id: String, status: String = "FREE", enabled: Boolean = true) = LibrarySeat(
        id = id, name = "A$id", row = 1, col = id.hashCode() and 0x3ff,
        type = "seat", enabled = enabled, status = status, localOnly = false,
    )

    private fun timeState() = LibrarySeatViewModel.UiState(
        filters = LibraryFilters(listOf("2026-11-07"), listOf(LibraryBuilding("1", "东馆"))),
        building = LibraryBuilding("1", "东馆"),
        date = "2026-11-07",
        room = room,
        seatMode = RoomSeatMode.TIME,
        timeBegin = "14:00",
        timeFinish = "16:00",
    )

    // ── HH:mm / 分钟 ─────────────────────────────────────────────
    @Test
    fun `HHmm 与分钟互转且不猜半小时规则`() {
        assertEquals(840, parseHhmm("14:00"))
        assertEquals(870, parseHhmm("14:30"))
        assertEquals(843, parseHhmm("14:03"))
        assertEquals("14:03", formatHhmm(843))
        assertNull(parseHhmm("25:00"))
        assertNull(parseHhmm("14:60"))
        assertNull(parseHhmm("abcd"))
        assertNull(parseHhmm("14"))
    }

    @Test
    fun `时段必须完整同日且开始小于结束`() {
        assertTrue(validTimeRange("14:00", "16:00"))
        assertTrue(validTimeRange("14:03", "14:44")) // 允许任意分钟
        assertFalse(validTimeRange(null, "16:00"))
        assertFalse(validTimeRange("14:00", null))
        assertFalse(validTimeRange("16:00", "14:00"))
        assertFalse(validTimeRange("14:00", "14:00"))
    }

    @Test
    fun `查询键包含馆区房间日期时段与模式`() {
        val state = timeState()
        assertEquals(key, state.currentTimeSearchKey())
        assertNull(state.copy(seatMode = RoomSeatMode.SEAT).currentTimeSearchKey())
        assertNull(state.copy(timeFinish = null).currentTimeSearchKey())
        assertNull(state.copy(timeBegin = "16:00", timeFinish = "14:00").currentTimeSearchKey())
        assertNull(state.copy(date = null).currentTimeSearchKey())
        assertNull(state.copy(room = null).currentTimeSearchKey())
    }

    // ── 分页折叠 ────────────────────────────────────────────────
    @Test
    fun `首页一页读完且无失败才 complete 与共 N 个`() {
        val folded = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a"), seat("b")), 1, false),
            1,
        )
        assertTrue(folded.complete)
        assertFalse(folded.hasMore)
        assertEquals(2, folded.seats.size)
        assertEquals(1, folded.loadedPages)
        assertEquals(2, folded.nextPage)
    }

    @Test
    fun `hasMore 为真时不宣称共 N 个且保留下一页`() {
        val folded = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a")), 1, true),
            1,
        )
        assertFalse(folded.complete)
        assertTrue(folded.hasMore)
        assertEquals(2, folded.nextPage)
    }

    @Test
    fun `跨页按稳定 id 去重`() {
        var state = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a"), seat("b")), 1, true),
            1,
        )
        state = foldTimePage(state, LibraryTimeSearchPage(listOf(seat("b"), seat("c")), 2, false), 2)
        assertEquals(listOf("a", "b", "c"), state.seats.map { it.id })
        assertEquals(2, state.loadedPages)
        assertTrue(state.complete)
    }

    @Test
    fun `重复页不推进时停止不死循环并标记部分失败`() {
        var state = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a")), 1, true),
            1,
        )
        state = foldTimePage(state, LibraryTimeSearchPage(listOf(seat("a")), 2, true), 2)
        // 不推进：标记部分失败且保留 hasMore，让「重试」能真正重新发出合法页，
        // 而不是把没读到伪装成结束。
        assertTrue(state.hasMore)
        assertFalse(state.complete)
        assertTrue(state.partialFailure)
        assertEquals(1, state.seats.size)
    }

    @Test
    fun `页号倒退被忽略`() {
        var state = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a")), 1, true),
            1,
        )
        state = foldTimePage(state, LibraryTimeSearchPage(listOf(seat("z")), 1, true), 1)
        assertTrue(state.partialFailure)
        assertEquals(listOf("a"), state.seats.map { it.id })
    }

    @Test
    fun `最多 20 页后停止并提示缩小范围`() {
        var state = LibraryTimeSearchResult(key = key)
        for (p in 1..20) {
            state = foldTimePage(
                state,
                LibraryTimeSearchPage(listOf(seat("s$p")), p, true),
                p,
            )
        }
        assertEquals(20, state.loadedPages)
        assertTrue(state.pageLimitReached)
        assertTrue(state.hasMore)
        assertFalse(state.complete)
        // 第 21 页不再追加
        val extra = foldTimePage(state, LibraryTimeSearchPage(listOf(seat("s21")), 21, true), 21)
        assertEquals(20, extra.seats.size)
        assertTrue(extra.pageLimitReached)
    }

    @Test
    fun `首屏失败不写 0 个可约下一页失败保留同条件结果`() {
        val first = LibraryTimeSearchResult(key = key).asFirstPageFailure("查询失败")
        assertTrue(first.failed)
        assertTrue(first.seats.isEmpty())
        assertFalse(first.complete)

        var loaded = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a"), seat("b")), 1, true),
            1,
        )
        loaded = loaded.asNextPageFailure()
        assertTrue(loaded.partialFailure)
        assertEquals(listOf("a", "b"), loaded.seats.map { it.id })
        assertTrue(loaded.hasMore) // 仍保留加载更多
        assertFalse(loaded.complete)
    }

    @Test
    fun `候选全隐藏但 hasMore 为真仍保留 hasMore 且不宣称全房间没座`() {
        val result = LibraryTimeSearchResult(
            key = key,
            seats = listOf(seat("a"), seat("b")),
            loadedPages = 1,
            nextPage = 2,
            hasMore = true,
            complete = false,
        )
        val visible = result.visibleCandidates(setOf("a", "b"))
        assertTrue(visible.isEmpty())
        assertTrue(result.hasMore)
        assertFalse(result.complete)
    }

    // ── 官方时间 id ─────────────────────────────────────────────
    @Test
    fun `按真实 HHmm 匹配官方 startTimes 与 endTimes 原始 id`() {
        val starts = listOf(LibraryTimeOption("480", "08:00"), LibraryTimeOption("840", "14:00"))
        val ends = listOf(LibraryTimeOption("900", "15:00"), LibraryTimeOption("960", "16:00"))
        assertEquals("840", matchOfficialTime(starts, "14:00")?.id)
        assertEquals("960", matchOfficialTime(ends, "16:00")?.id)
        assertNull(matchOfficialTime(starts, "14:30")) // 不能用显示字符串冒充 id
    }

    // ── 两模式守卫 ──────────────────────────────────────────────
    @Test
    fun `当前占用但未来区间可约不能被 selectable 全局误拦`() {
        val candidate = seat("a", status = "IN_USE", enabled = true)
        assertFalse(candidate.selectable)                 // 按座位模式：不可选
        assertTrue(candidate.timeSelectable)              // 按时段模式：静态字段仍允许
        val result = LibraryTimeSearchResult(
            key = key,
            seats = listOf(candidate),
            loadedPages = 1,
            complete = true,
        )
        val state = timeState().copy(
            timeResult = result,
            timeSelectedSeat = candidate,
            timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
            timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
            timeStart = LibraryTimeOption("840", "14:00"),
            timeStop = LibraryTimeOption("960", "16:00"),
            layout = LibrarySeatLayout(1, 1, listOf(candidate)),
        )
        assertTrue(state.canConfirmTimeReservation())       // 区间模式放行
        assertFalse(state.canConfirmReservation())          // 座位模式守卫不串
    }

    @Test
    fun `非真实座位或静态禁用在两模式都拒绝`() {
        val wall = seat("w").copy(type = "wall")
        val result = LibraryTimeSearchResult(key = key, seats = listOf(wall), complete = true)
        val base = timeState().copy(
            timeResult = result,
            timeSelectedSeat = wall,
            timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
            timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
            timeStart = LibraryTimeOption("840", "14:00"),
            timeStop = LibraryTimeOption("960", "16:00"),
        )
        assertFalse(base.canConfirmTimeReservation())
    }

    @Test
    fun `官方时间映射不匹配或变动时清除后不能确认`() {
        val candidate = seat("a")
        val result = LibraryTimeSearchResult(key = key, seats = listOf(candidate), complete = true)
        val base = timeState().copy(
            timeResult = result,
            timeSelectedSeat = candidate,
            timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
            timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
            timeStart = LibraryTimeOption("840", "14:00"),
            timeStop = LibraryTimeOption("960", "16:00"),
        )
        assertTrue(base.canConfirmTimeReservation())
        // 结束时间不在官方列表：拒绝
        assertFalse(base.copy(timeStop = LibraryTimeOption("999", "23:00")).canConfirmTimeReservation())
        // 时段文字变化：结果键与当前条件不一致，拒绝
        assertFalse(base.copy(timeFinish = "17:00").canConfirmTimeReservation())
    }

    @Test
    fun `旧条件或旧账号结果不能启用确认`() {
        val candidate = seat("a")
        val staleKey = key.copy(date = "2026-11-06")
        val staleResult = LibraryTimeSearchResult(key = staleKey, seats = listOf(candidate), complete = true)
        val state = timeState().copy(
            timeResult = staleResult,
            timeSelectedSeat = candidate,
            timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
            timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
            timeStart = LibraryTimeOption("840", "14:00"),
            timeStop = LibraryTimeOption("960", "16:00"),
        )
        assertFalse(state.canConfirmTimeReservation())
        // 迟到响应上下文与当前代次/token/key 任一不符即失效
        val context = TimeQueryContext(7, "token-old", key)
        assertTrue(context.isCurrent(7, "token-old", key))
        assertFalse(context.isCurrent(8, "token-old", key))       // 代次变化
        assertFalse(context.isCurrent(7, "token-new", key))      // 账号/会话变化
        assertFalse(context.isCurrent(7, "token-old", staleKey)) // 条件变化
    }

    @Test
    fun `选中隐藏即清空后不能确认未知结果不重提`() {
        val candidate = seat("a")
        val result = LibraryTimeSearchResult(key = key, seats = listOf(candidate), complete = true)
        val state = timeState().copy(
            timeResult = result,
            timeSelectedSeat = candidate,
            hiddenSeatIds = setOf("a"),
            reservationSubmitted = true,
            timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
            timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
            timeStart = LibraryTimeOption("840", "14:00"),
            timeStop = LibraryTimeOption("960", "16:00"),
        )
        assertFalse(state.canConfirmTimeReservation())
        assertFalse(state.canConfirmReservation())
        assertTrue(state.copy(reservationSubmitted = false, hiddenSeatIds = emptySet())
            .canConfirmTimeReservation())
    }

    // ── 服务端页号 / 同页去重 / 终页重叠 / 失败可重试 / 隐藏交集 ──

    @Test
    fun `服务端页号与请求不一致保留已读标 partial 不写 complete`() {
        var state = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a")), 1, true),
            1,
        )
        val before = state.seats.map { it.id }
        state = foldTimePage(
            state,
            LibraryTimeSearchPage(listOf(seat("z")), 1, true), // 服务端报 1（倒退）
            2,
        )
        assertEquals(before, state.seats.map { it.id })
        assertTrue(state.partialFailure)
        assertFalse(state.complete)
        assertTrue(state.hasMore) // 可重试
    }

    @Test
    fun `同一页内重复 id 去重不重复累计`() {
        val folded = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(
                listOf(seat("a"), seat("a"), seat("b")), 1, false,
            ),
            1,
        )
        assertEquals(listOf("a", "b"), folded.seats.map { it.id })
        assertTrue(folded.complete)
    }

    @Test
    fun `无效终页全重复不伪装成完整读完`() {
        var state = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a")), 1, true),
            1,
        )
        state = foldTimePage(
            state,
            LibraryTimeSearchPage(listOf(seat("a")), 2, false), // 终页但全是旧 id
            2,
        )
        assertFalse(state.complete)
        assertTrue(state.partialFailure)
        assertEquals(listOf("a"), state.seats.map { it.id })
    }

    @Test
    fun `有效终页与旧页重叠仍按新增成员完成`() {
        var state = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a"), seat("b")), 1, true),
            1,
        )
        state = foldTimePage(
            state,
            LibraryTimeSearchPage(listOf(seat("b"), seat("c")), 2, false),
            2,
        )
        assertEquals(listOf("a", "b", "c"), state.seats.map { it.id })
        assertTrue(state.complete)
        assertFalse(state.partialFailure)
    }

    @Test
    fun `首屏失败与下一页失败都保留可重试的 hasMore`() {
        val first = LibraryTimeSearchResult(key = key).asFirstPageFailure("查询失败")
        assertTrue(first.failed)
        assertTrue(first.hasMore)      // retryTime 会重新 searchByTime
        assertFalse(first.complete)

        val loaded = foldTimePage(
            LibraryTimeSearchResult(key = key),
            LibraryTimeSearchPage(listOf(seat("a")), 1, true),
            1,
        ).asNextPageFailure()
        assertTrue(loaded.hasMore)     // retryTime 会重新 loadMoreTime
        assertTrue(loaded.partialFailure)
        assertFalse(loaded.complete)
    }

    @Test
    fun `已隐藏数只取已加载候选交集不是房间总数`() {
        val result = LibraryTimeSearchResult(
            key = key, seats = listOf(seat("a"), seat("b")), complete = true,
        )
        // 房间隐藏名单含 a、b 和第三项 x（未加载），交集应为 2 而非 3。
        assertEquals(2, result.hiddenAmongLoaded(setOf("a", "b", "x")))
        assertEquals(0, result.hiddenAmongLoaded(emptySet()))
        assertEquals(listOf<LibrarySeat>(), result.visibleCandidates(setOf("a", "b")))
    }

    @Test
    fun `canConfirmTimeReservation 检 reservationSubmitted`() {
        val candidate = seat("a")
        val base = timeState().copy(
            timeResult = LibraryTimeSearchResult(key = key, seats = listOf(candidate), complete = true),
            timeSelectedSeat = candidate,
            timeStartTimes = listOf(LibraryTimeOption("840", "14:00")),
            timeEndTimes = listOf(LibraryTimeOption("960", "16:00")),
            timeStart = LibraryTimeOption("840", "14:00"),
            timeStop = LibraryTimeOption("960", "16:00"),
        )
        assertTrue(base.canConfirmTimeReservation())
        assertFalse(base.copy(reservationSubmitted = true).canConfirmTimeReservation())
    }
}
