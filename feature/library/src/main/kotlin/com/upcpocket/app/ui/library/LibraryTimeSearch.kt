package com.upcpocket.app.ui.library

import com.upcpocket.app.data.library.LibrarySeat
import com.upcpocket.app.data.library.LibraryTimeOption
import com.upcpocket.app.data.library.LibraryTimeSearchKey
import com.upcpocket.app.data.library.LibraryTimeSearchPage
import com.upcpocket.app.data.library.LibraryTimeSearchResult

/**
 * 「按时段找」的纯状态推导。
 *
 * 不联网、不读 DataStore、不依赖 Android：分页折叠、查询键、校验都在
 * UiState 上做纯函数，便于用合成服务响应覆盖重复页/上限/部分失败/旧条件。
 * 字段命名与按座位模式（start/end/startTimes/endTimes）严格分开。
 */

/** 房间页两种模式；默认按座位选，保留原有流程。 */
enum class RoomSeatMode { SEAT, TIME }

/**
 * 一次时段查询/选时的完整上下文。
 *
 * 迟到响应必须同时匹配代次、账号会话 token 与完整查询键；换馆、换号、
 * 失效登录、改时段或切模式后的旧结果不能合并/重新启用确认。
 */
data class TimeQueryContext(
    val generation: Long,
    val token: String?,
    val key: LibraryTimeSearchKey,
)

/** 迟到响应是否仍属于当前查询；任一项变化即失效。 */
fun TimeQueryContext.isCurrent(
    generation: Long,
    token: String?,
    key: LibraryTimeSearchKey?,
): Boolean = this.generation == generation && this.token == token && this.key == key

internal const val TIME_SEARCH_MAX_PAGES = 20
internal const val TIME_SEARCH_BATCH = 25

/** HH:mm → 当天分钟数；不合法返回 null。允许任意分钟，不猜学校半小时规则。 */
fun parseHhmm(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

fun formatHhmm(minute: Int): String = "%02d:%02d".format((minute / 60) % 24, minute % 60)

/** 时段是否可以发起查询：同日、完整、开始 < 结束，不自动填一小时。 */
fun validTimeRange(begin: String?, finish: String?): Boolean {
    val b = begin?.let(::parseHhmm) ?: return false
    val e = finish?.let(::parseHhmm) ?: return false
    return b < e
}

/** 页面当前的完整查询键；缺任何上下文返回 null（不得发起查询）。 */
internal fun LibrarySeatViewModel.UiState.currentTimeSearchKey(): LibraryTimeSearchKey? {
    if (seatMode != RoomSeatMode.TIME) return null
    val building = building?.id ?: return null
    val currentRoom = room?.roomId ?: return null
    val d = date ?: return null
    val beginText = timeBegin ?: return null
    val finishText = timeFinish ?: return null
    val b = parseHhmm(beginText) ?: return null
    val e = parseHhmm(finishText) ?: return null
    if (b >= e) return null
    return LibraryTimeSearchKey(
        buildingId = building,
        roomId = currentRoom,
        date = d,
        beginMinute = b,
        endMinute = e,
        beginText = beginText,
        endText = finishText,
    )
}

/**
 * 把一页真实结果折叠进累积状态。
 *
 * `page`/`hasMore`/名单字段已由 [LibrarySeatProtocol.parseTimeSearchPage] 严格校验，
 * 这里只按「名单是否真空」与「是否有新 id」分四种情况，绝不把合法空终页一刀切拒绝：
 *
 *  1. **空名单 + hasMore=false** → 合法结束：complete=true，保留已加载 seats
 *     （首屏则 seats=[] 显示「暂无可约座位」），不显示加载更多。实测证据：
 *     `batch=1,page=2 → 0 个 / hasMore=false` 即学校真实合法终止形式。
 *  2. **空名单 + hasMore=true** → 不前进：partialFailure + 可重试。
 *  3. **非空名单但全是旧 id（无新增）** → 重复/异常页：partialFailure，不冒充完整。
 *  4. **非空名单含新 id** → 页内 + 跨页去重后正常累积，按 hasMore/上限决定 complete。
 *
 * 另外校验服务端 `page.page` 必须等于请求页且不能小于 [current.nextPage]（页号倒退），
 * 不一致时保留已读结果并标记未完整加载，绝不写 complete。
 */
fun foldTimePage(
    current: LibraryTimeSearchResult,
    page: LibraryTimeSearchPage,
    requestedPage: Int,
    maxPages: Int = TIME_SEARCH_MAX_PAGES,
): LibraryTimeSearchResult {
    // 服务端页号必须与请求页一致，且不能倒退；否则视为不可信页。
    if (page.page != requestedPage || requestedPage < current.nextPage) {
        return current.asNonAdvancingPage()
    }
    if (current.loadedPages >= maxPages) {
        return current.copy(
            hasMore = true,
            complete = false,
            pageLimitReached = true,
        )
    }
    // 页内去重 + 跨页去重。
    val pageIds = linkedSetOf<String>()
    val uniqueOnPage = page.seats.filter { pageIds.add(it.id) }
    val existingIds = current.seats.mapTo(linkedSetOf()) { it.id }
    val fresh = uniqueOnPage.filter { it.id !in existingIds }

    // ── 情况 1/2：本页名单真空（严格解析后确实是 0 个候选）──
    if (uniqueOnPage.isEmpty()) {
        return if (!page.hasMore) {
            // 合法空终页：不丢已读结果，直接完成。
            current.copy(
                seats = current.seats,
                loadedPages = current.loadedPages + 1,
                nextPage = requestedPage + 1,
                hasMore = false,
                complete = true,
                partialFailure = false,
                pageLimitReached = false,
            )
        } else {
            // 空页却说还有更多 = 不前进，保留可重试。
            current.asNonAdvancingPage()
        }
    }

    // ── 情况 3：名单非空但全部是旧 id（重复页），不冒充完整 ──
    if (fresh.isEmpty()) {
        return current.asNonAdvancingPage()
    }

    // ── 情况 4：含新 id，正常去重累积 ──
    val loaded = current.loadedPages + 1
    val accumulated = current.seats + fresh
    return when {
        !page.hasMore -> current.copy(
            seats = accumulated,
            loadedPages = loaded,
            nextPage = requestedPage + 1,
            hasMore = false,
            complete = true,
            partialFailure = false,
            pageLimitReached = false,
        )
        loaded >= maxPages -> current.copy(
            seats = accumulated,
            loadedPages = loaded,
            nextPage = requestedPage + 1,
            hasMore = true,
            complete = false,
            pageLimitReached = true,
        )
        else -> current.copy(
            seats = accumulated,
            loadedPages = loaded,
            nextPage = requestedPage + 1,
            hasMore = true,
            complete = false,
            partialFailure = false,
            pageLimitReached = false,
        )
    }
}

/**
 * 不前进/异常页：保留已读结果，保持可重试。
 *
 * 若已读结果本身还标记 hasMore（服务端曾说有更多），保留它让 loadMoreTime 能
 * 重新发出同一页；否则也置 hasMore=true，配合 partialFailure 让「重试」重新查询
 * 当前条件，而不是把「没读到」伪装成结束。
 */
private fun LibraryTimeSearchResult.asNonAdvancingPage(): LibraryTimeSearchResult =
    copy(
        hasMore = true,
        complete = false,
        partialFailure = true,
        failureMessage = "部分结果未加载",
    )

/** 首屏查询失败：不写 0 个可约，保留失败标记与可重试。 */
fun LibraryTimeSearchResult.asFirstPageFailure(message: String): LibraryTimeSearchResult =
    copy(
        querying = false,
        failed = true,
        failureMessage = message,
        hasMore = true,   // 允许「重试」重新发起同一条件查询，不是空操作
        complete = false,
        seats = emptyList(),
    )

/** 下一页失败：保留同条件已读结果，保持 hasMore 与可重试。 */
fun LibraryTimeSearchResult.asNextPageFailure(): LibraryTimeSearchResult =
    copy(
        querying = false,
        partialFailure = true,
        hasMore = true,
        failureMessage = "部分结果未加载",
    )

/** 候选的静态可选条件：尊重真实字段，但「此刻已占用」不单独否决未来区间可约。 */
val LibrarySeat.timeSelectable: Boolean
    get() = isSeat && enabled && !localOnly

/** 从累积结果中筛掉个人隐藏项，得到可见候选。 */
fun LibraryTimeSearchResult.visibleCandidates(hiddenIds: Set<String>): List<LibrarySeat> =
    seats.filter { it.id !in hiddenIds }

/**
 * 「已隐藏 M」只统计**已加载候选中**被个人隐藏的项，不是整个房间的隐藏总数。
 * 避免把房间级隐藏名单数量误读成「本次结果隐藏了多少」。
 */
fun LibraryTimeSearchResult.hiddenAmongLoaded(hiddenIds: Set<String>): Int =
    seats.count { it.id in hiddenIds }

/**
 * 按时段模式的最终确认守卫。
 *
 * 与按座位模式的 canConfirmReservation() 完全分开：不依赖 layout 的
 * selectable（当前占用不能否决未来区间），只依赖已验证的区间结果、静态
 * 字段、官方时间映射、完整上下文与未隐藏。
 */
internal fun LibrarySeatViewModel.UiState.canConfirmTimeReservation(): Boolean {
    if (seatMode != RoomSeatMode.TIME) return false
    // 与按座位模式一致：已经发出过提交的同一选择不得再次显示可确认。
    if (reservationSubmitted) return false
    if (loading || timeSelecting) return false
    val result = timeResult ?: return false
    if (result.failed || result.querying) return false
    val selected = timeSelectedSeat ?: return false
    val start = timeStart ?: return false
    val stop = timeStop ?: return false
    if (!selected.timeSelectable) return false
    if (selected.id in hiddenSeatIds) return false
    if (result.seats.none { it.id == selected.id }) return false
    // 完整查询键/模式/房间/日期/时段必须仍是当前上下文，迟到的旧结果不能启用确认。
    val key = currentTimeSearchKey() ?: return false
    if (result.key != key) return false
    if (start.value != timeBegin || stop.value != timeFinish) return false
    if (timeStartTimes.none { it.id == start.id }) return false
    if (timeEndTimes.none { it.id == stop.id }) return false
    return timeDurationMinutes(start, stop) != null
}

/** 把官方时间选项按真实 HH:mm 分钟匹配；找不到返回 null，绝不自行算 id。 */
fun matchOfficialTime(
    options: List<LibraryTimeOption>,
    hhmm: String?,
): LibraryTimeOption? {
    val minute = hhmm?.let(::parseHhmm) ?: return null
    return options.firstOrNull { parseHhmm(it.value) == minute }
}
